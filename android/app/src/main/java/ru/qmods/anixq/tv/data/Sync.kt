package ru.qmods.anixq.tv.data

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Same GitHub Gist sync as the iOS app (docs/sync-format.md). */
object Sync {
    const val CLIENT_ID = "Ov23liCzZBzFN0H5bIuO"
    private const val FILE = "anixq-sync.json"
    private const val DESCRIPTION = "AnixQ sync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var loop: Job? = null
    private var pushJob: Job? = null
    private var etag: String? = null
    private var lastHash = ""

    val status = MutableStateFlow("Выключено")
    val lastSync = MutableStateFlow(0L)
    /** Device-flow sign-in on the TV itself: code + URL to show. */
    val deviceLogin = MutableStateFlow<Pair<String, String>?>(null)

    val isOn get() = Store.githubToken != null && Store.gistId != null

    fun start() {
        if (!isOn) { status.value = "Выключено"; return }
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                sync()
                delay(10_000)
            }
        }
    }

    /** Seconds to add to the local clock to get GitHub's time. */
    @Volatile var serverClockOffset = 0.0
    private var lastPull = 0L

    /** Local change: push within ~4 s. Not reset by further changes, so playback progress can't postpone it forever. */
    fun changed() {
        if (!isOn) return
        if (pushJob?.isActive == true) return
        pushJob = scope.launch { delay(4_000); sync() }
    }

    fun syncNow() { scope.launch { sync() } }

    /** Profile switch: stop syncing the old profile's gist. */
    fun stopLoop() { loop?.cancel(); loop = null; pushJob?.cancel(); etag = null; lastHash = ""; status.value = "Выключено" }

    /** Push right away (leaving the player, app going to background). */
    fun flush() { if (isOn) { pushJob?.cancel(); scope.launch { sync() } } }

    /** Make sure we have the other devices' latest position before starting playback (≤ 3 s wait). */
    suspend fun pullFresh() {
        if (!isOn || System.currentTimeMillis() - lastPull < 4_000) return
        kotlinx.coroutines.withTimeoutOrNull(3_000) { sync() }
    }

    // MARK: Sign-in on the TV (GitHub device flow)

    fun signInOnTv() {
        scope.launch {
            try {
                val code = post("https://github.com/login/device/code", JSONObject().put("client_id", CLIENT_ID).put("scope", "gist"))
                val device = code.str("device_code") ?: return@launch
                deviceLogin.value = (code.str("user_code") ?: "") to (code.str("verification_uri") ?: "https://github.com/login/device")
                var interval = code.int("interval") ?: 5
                val deadline = System.currentTimeMillis() + (code.int("expires_in") ?: 900) * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(interval * 1000L)
                    val r = post("https://github.com/login/oauth/access_token", JSONObject().put("client_id", CLIENT_ID)
                        .put("device_code", device).put("grant_type", "urn:ietf:params:oauth:grant-type:device_code"))
                    r.str("access_token")?.let { token ->
                        finishLogin(token, null)
                        deviceLogin.value = null
                        return@launch
                    }
                    when (r.str("error")) {
                        "slow_down" -> interval += 5
                        "expired_token", "access_denied" -> { deviceLogin.value = null; status.value = "Вход отменён"; return@launch }
                    }
                }
                deviceLogin.value = null
            } catch (e: Exception) {
                deviceLogin.value = null
                status.value = "Ошибка входа: ${e.message}"
            }
        }
    }

    suspend fun finishLogin(token: String, gist: String?) {
        Store.setGithub(token, gist)
        try {
            val me = api("GET", "/user") as? JSONObject
            Store.setGithub(token, gist, me?.str("login"))
            if (gist == null) findOrCreateGist()
        } catch (e: Exception) { Log.d("AnixQ", "github: ${e.message}") }
        etag = null
        status.value = "Подключено"
        start()
        syncNow()
    }

    fun signOut() {
        loop?.cancel(); loop = null
        Store.setGithub(null, null, null)
        lastHash = ""
        status.value = "Выключено"
    }

    private suspend fun findOrCreateGist() {
        val list = api("GET", "/gists?per_page=100") as? JSONArray
        if (list != null) for (i in 0 until list.length()) {
            val g = list.optJSONObject(i) ?: continue
            if (g.str("description") == DESCRIPTION) { Store.setGithub(Store.githubToken, g.str("id")); return }
        }
        val content = Store.exportSync().toString()
        val body = JSONObject().put("description", DESCRIPTION).put("public", false)
            .put("files", JSONObject().put(FILE, JSONObject().put("content", content)))
        val created = api("POST", "/gists", body) as? JSONObject
        Store.setGithub(Store.githubToken, created?.str("id"))
    }

    // MARK: Pull → merge → push

    suspend fun sync() = lock.withLock {
        val gist = Store.gistId ?: return@withLock
        try {
            val (code, text, tag) = raw("GET", "/gists/$gist", etag = etag)
            if (code == 404) { Store.setGithub(Store.githubToken, null); findOrCreateGist(); return@withLock }
            if (code == 200) {
                val file = JSONObject(text).optJSONObject("files")?.optJSONObject(FILE)
                var content = file?.str("content") ?: ""
                if (file?.optBoolean("truncated") == true) file.str("raw_url")?.let { u ->
                    content = Api.http.newCall(Request.Builder().url(u).build()).execute().use { it.body?.string().orEmpty() }
                }
                // Parse the (possibly large) document off the main thread; only the merge runs there.
                val parsed = content.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (parsed != null) withContext(Dispatchers.Main) {
                    if (Store.importSync(parsed)) {
                        // Show what you watched on the phone in the TV home screen's "Continue" row too.
                        Store.continueList.take(4).forEach { e ->
                            WatchNext.update(e.title, Episode(e.episodeId, e.ep, e.epName, 0), e.teamId, e.pos, e.dur, hasNext = true)
                        }
                    }
                }
                etag = tag
            }
            // First sync on this TV: create the phone-remote channel if the gist has none yet.
            withContext(Dispatchers.Main) { if (Store.remoteChannel.isEmpty()) Store.ensureRemote(); Remote.ensureConnected() }
            // Nothing changed here since the last push: skip building the whole document (it ran every 10 s on the UI thread).
            if (!Store.dirty && lastHash.isNotEmpty()) {
                lastSync.value = System.currentTimeMillis(); lastPull = System.currentTimeMillis(); status.value = "Синхронизировано"
                return@withLock
            }
            val doc = withContext(Dispatchers.Main) { Store.dirty = false; Store.exportSync() }
            val hash = sha(doc.toString())
            if (hash != lastHash) {
                doc.put("updated", System.currentTimeMillis() / 1000.0)
                api("PATCH", "/gists/$gist", JSONObject().put("files", JSONObject().put(FILE, JSONObject().put("content", doc.toString()))))
                lastHash = hash
                etag = null
            }
            lastSync.value = System.currentTimeMillis()
            lastPull = System.currentTimeMillis()
            status.value = "Синхронизировано"
        } catch (e: Exception) {
            status.value = "Нет связи с GitHub"
            Log.d("AnixQ", "sync: ${e.message}")
        }
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // MARK: HTTP

    private suspend fun post(url: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        Api.http.newCall(req).execute().use { JSONObject(it.body?.string().orEmpty().ifBlank { "{}" }) }
    }

    private suspend fun raw(method: String, path: String, body: JSONObject? = null, etag: String? = null): Triple<Int, String, String?> =
        withContext(Dispatchers.IO) {
            val token = Store.githubToken ?: throw ApiException("Нет входа в GitHub")
            val rb = Request.Builder().url("https://api.github.com$path")
                .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "AnixQ-TV")
            etag?.let { rb.header("If-None-Match", it) }
            if (method == "GET") rb.get() else rb.method(method, (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
            Api.http.newCall(rb.build()).execute().use { r ->
                // TV boxes often have a drifting clock: measure it against GitHub so "newest wins" compares real times.
                r.headers.getDate("Date")?.let { serverClockOffset = (it.time - System.currentTimeMillis()) / 1000.0 }
                if (r.code == 401) { withContext(Dispatchers.Main) { signOut() }; status.value = "Вход в GitHub истёк"; throw ApiException("401") }
                if (r.code !in 200..299 && r.code != 304 && r.code != 404) throw ApiException("GitHub ${r.code}")
                Triple(r.code, r.body?.string().orEmpty(), r.header("ETag"))
            }
        }

    private suspend fun api(method: String, path: String, body: JSONObject? = null): Any? {
        val (code, text, _) = raw(method, path, body)
        if (code == 404 || text.isBlank()) return null
        return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
    }
}

/**
 * "Войти с телефона": the TV shows a QR code with a one-time channel and key; AnixQ on the phone
 * encrypts the logins with that key (AES-256-GCM) and publishes them over the same MQTT relay the
 * watch rooms use. Only this TV can decrypt them.
 */
class Pairing(
    private val onDone: (String) -> Unit,
    private val onError: (String) -> Unit,
    /** Login and password typed on a phone/PC (site page) for the TV to sign in with. */
    private val onCredentials: ((String, String) -> Unit)? = null,
) {
    private val channel: String
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private var ws: WebSocket? = null
    private var pinger: Job? = null
    private var failures = 0
    @Volatile private var finished = false

    init {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        channel = (1..16).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
    }

    val topic get() = "anixq/pair/$channel"
    private val keyText get() = Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    /** Opens AnixQ on an iPhone directly. */
    val qrText: String get() = "anixq://pair/$channel?k=$keyText"
    /** Site page for any phone or computer: sign in to AnimeLib there, or hand over to AnixQ on iPhone. The key stays in the #fragment. */
    val webUrl: String get() = "https://qmurzik.github.io/AnixQ/tv/#c=$channel&k=$keyText"

    fun start() {
        val req = Request.Builder().url("wss://public.cloud.shiftr.io").header("Sec-WebSocket-Protocol", "mqtt").build()
        ws = Api.http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(Mqtt.connect("anixq-tv-$channel", "public", "public").toByteString())
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Mqtt.parse(bytes.toByteArray()) { type, body ->
                    when (type) {
                        2 -> {
                            webSocket.send(Mqtt.subscribe(topic).toByteString())
                            // Keep-alive is 30 s: ping well inside it so the relay never drops us.
                            pinger?.cancel()
                            pinger = CoroutineScope(Dispatchers.IO).launch {
                                while (isActive && !finished) { delay(15_000); webSocket.send(byteArrayOf(0xC0.toByte(), 0).toByteString()) }
                            }
                        }
                        3 -> {
                            val (t, payload) = Mqtt.publishBody(body)
                            if (t == topic && payload.isNotEmpty() && !finished) handle(String(payload))
                        }
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                pinger?.cancel()
                if (finished) return
                // Reconnect quietly; the QR stays valid.
                CoroutineScope(Dispatchers.IO).launch { delay(3000); if (!finished) start() }
                if (++failures >= 3) onError("Нет связи с сервером сопряжения, пробуем снова…")
            }
        })
    }

    private fun handle(b64: String) {
        try {
            val sealed = Base64.decode(b64.trim(), Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
            val json = JSONObject(String(cipher.doFinal(sealed, 12, sealed.size - 12)))
            val creds = json.optJSONObject("credentials")
            if (creds != null && onCredentials != null) {
                // Keep listening: the password may be wrong and the user will send it again.
                ws?.send(Mqtt.publish(topic, ByteArray(0), retain = true).toByteString())
                val l = creds.str("login") ?: ""; val p = creds.optString("password")
                CoroutineScope(Dispatchers.Main).launch { onCredentials.invoke(l, p) }
                return
            }
            finished = true
            // Clear the retained message from the relay.
            ws?.send(Mqtt.publish(topic, ByteArray(0), retain = true).toByteString())
            ws?.close(1000, null)
            CoroutineScope(Dispatchers.Main).launch {
                json.optJSONObject("animelib")?.let { a ->
                    Store.setLib(a.str("token"), a.str("refresh"), a.str("username"), a.int("userId"))
                    Api.loadProfile()
                }
                json.optJSONObject("shiki")?.let { Store.setShiki(it) }
                json.optJSONObject("github")?.let { g ->
                    g.str("token")?.let { Sync.finishLogin(it, g.str("gist")) }
                }
                onDone(json.str("from") ?: "телефона")
            }
        } catch (e: Exception) {
            onError("Не удалось расшифровать: ${e.message}")
        }
    }

    fun stop() { finished = true; pinger?.cancel(); ws?.close(1000, null) }
}

/** Minimal MQTT 3.1.1 packets (QoS 0), as in the iOS app. */
object Mqtt {
    private fun str(s: String): ByteArray { val b = s.toByteArray(); return byteArrayOf((b.size shr 8).toByte(), (b.size and 0xFF).toByte()) + b }
    private fun len(n0: Int): ByteArray {
        var n = n0; val out = ArrayList<Byte>()
        do { var d = n % 128; n /= 128; if (n > 0) d = d or 0x80; out.add(d.toByte()) } while (n > 0)
        return out.toByteArray()
    }
    private fun packet(h: Int, body: ByteArray) = byteArrayOf(h.toByte()) + len(body.size) + body

    fun connect(clientId: String, user: String?, pass: String?): ByteArray {
        var flags = 0x02
        if (user != null) flags = flags or 0x80
        if (pass != null) flags = flags or 0x40
        val vh = str("MQTT") + byteArrayOf(4, flags.toByte(), 0, 30)
        var pl = str(clientId)
        if (user != null) pl += str(user)
        if (pass != null) pl += str(pass)
        return packet(0x10, vh + pl)
    }
    fun subscribe(topic: String) = packet(0x82, byteArrayOf(0, 1) + str(topic) + byteArrayOf(0))
    fun publish(topic: String, payload: ByteArray, retain: Boolean = false) = packet(if (retain) 0x31 else 0x30, str(topic) + payload)

    fun parse(data: ByteArray, handler: (Int, ByteArray) -> Unit) {
        var i = 0
        while (i < data.size) {
            val h = data[i].toInt() and 0xFF
            var mult = 1; var l = 0; var j = i + 1
            while (j < data.size) {
                val b = data[j].toInt() and 0xFF
                l += (b and 0x7F) * mult; mult *= 128; j++
                if (b and 0x80 == 0) break
            }
            if (j + l > data.size) return
            handler(h shr 4, data.copyOfRange(j, j + l))
            i = j + l
        }
    }

    fun publishBody(body: ByteArray): Pair<String, ByteArray> {
        val tl = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        return String(body, 2, tl) to body.copyOfRange(2 + tl, body.size)
    }
}
