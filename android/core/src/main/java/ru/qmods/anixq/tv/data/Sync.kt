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
                delay(120_000)   // live channel carries the real-time part
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
        // Real-time progress goes over the live channel (Remote); the gist is only durable storage:
        // write it at most every 10 minutes (and at once when leaving the app / the player).
        val wait = maxOf(10_000L, 600_000L - (System.currentTimeMillis() - lastPushAt), backoffUntil - System.currentTimeMillis())
        pushJob = scope.launch { delay(wait); sync() }
    }

    private var lastPushAt = 0L
    /** Rate-limited by GitHub (403/429): stay quiet for a while. */
    @Volatile private var backoffUntil = 0L
    private var strikes = 0
    @Volatile private var retryAfterMs = 0L

    fun syncNow() { scope.launch { sync() } }

    /** Profile switch: stop syncing the old profile's gist. */
    fun stopLoop() { loop?.cancel(); loop = null; pushJob?.cancel(); etag = null; lastHash = ""; status.value = "Выключено" }

    /** Push right away (leaving the player, app going to background). */
    fun flush() {
        // At most one extra gist write a minute (the live channel already carries the exact position).
        if (!isOn || System.currentTimeMillis() - lastPushAt < 60_000) return
        pushJob?.cancel(); scope.launch { sync() }
    }

    /** Make sure we have the other devices' latest position before starting playback (≤ 3 s wait). */
    suspend fun pullFresh() {
        if (!isOn || System.currentTimeMillis() - lastPull < 4_000) return
        if (Remote.online.value && System.currentTimeMillis() - lastPull < 300_000) return   // live channel is up to date
        kotlinx.coroutines.withTimeoutOrNull(3_000) { sync() }
    }

    // MARK: Sign-in on the TV (GitHub device flow)

    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** The app is back on screen: check the GitHub sign-in right away instead of waiting. */
    fun wakeLogin() { if (deviceLogin.value != null) wake.trySend(Unit) }

    private var loginJob: Job? = null

    fun signInOnTv() {
        if (loginJob?.isActive == true) return
        loginJob = scope.launch {
            try {
                status.value = "Получаем код…"
                val code = post("https://github.com/login/device/code", JSONObject().put("client_id", CLIENT_ID).put("scope", "gist"))
                val device = code.str("device_code") ?: return@launch
                deviceLogin.value = (code.str("user_code") ?: "") to (code.str("verification_uri") ?: "https://github.com/login/device")
                var interval = code.int("interval") ?: 5
                val deadline = System.currentTimeMillis() + (code.int("expires_in") ?: 900) * 1000L
                while (System.currentTimeMillis() < deadline) {
                    // Wake early when the app comes back from the browser (see wakeLogin).
                    kotlinx.coroutines.withTimeoutOrNull(interval * 1000L) { wake.receive() }
                    delay(1_000)
                    // A phone in the background may have no network for a moment: just try again.
                    val r = try {
                        post("https://github.com/login/oauth/access_token", JSONObject().put("client_id", CLIENT_ID)
                            .put("device_code", device).put("grant_type", "urn:ietf:params:oauth:grant-type:device_code"))
                    } catch (e: Exception) { Log.d("AnixQ", "github poll: ${e.message}"); continue }
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
        if (System.currentTimeMillis() < backoffUntil) return@withLock
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
                lastPushAt = System.currentTimeMillis()
                lastHash = hash
                etag = null
            }
            lastSync.value = System.currentTimeMillis()
            lastPull = System.currentTimeMillis()
            strikes = 0
            status.value = "Синхронизировано"
        } catch (e: Exception) {
            if (e.message == "GitHub 403" || e.message == "GitHub 429") {
                // Wait as long as GitHub asks (Retry-After / reset time), doubling on repeats, up to an hour.
                strikes = (strikes + 1).coerceAtMost(6)
                val asked = retryAfterMs
                val wait = maxOf(asked, 120_000L shl (strikes - 1)).coerceAtMost(3_600_000L)
                backoffUntil = System.currentTimeMillis() + wait
                Store.dirty = true
                status.value = "Живая синхронизация работает; GitHub-копия обновится через ${wait / 60_000} мин"
            } else status.value = "Нет связи с GitHub"
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
                if (r.code == 403 || r.code == 429) {
                    retryAfterMs = r.header("Retry-After")?.toLongOrNull()?.times(1000)
                        ?: r.header("X-RateLimit-Reset")?.toLongOrNull()?.let { it * 1000 - System.currentTimeMillis() }?.takeIf { r.header("X-RateLimit-Remaining") == "0" }
                        ?: 0L
                }
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
    /** A phone asks for this device's logins ("want"): show who, call approve(true/false). */
    private val onRequest: ((from: String, approve: (Boolean) -> Unit) -> Unit)? = null,
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
            val want = json.optBoolean("want") && onRequest != null
            // Clear the retained message from the relay.
            ws?.send(Mqtt.publish(topic, ByteArray(0), retain = true).toByteString())
            if (!want) { finished = true; ws?.close(1000, null) }
            val from = json.str("from") ?: "телефона"
            CoroutineScope(Dispatchers.Main).launch {
                json.optJSONObject("animelib")?.let { a ->
                    Store.setLib(a.str("token"), a.str("refresh"), a.str("username"), a.int("userId"))
                    Api.loadProfile()
                }
                json.optJSONObject("shiki")?.let { Store.setShiki(it) }
                json.optJSONObject("github")?.let { g ->
                    g.str("token")?.let { Sync.finishLogin(it, g.str("gist")) }
                }
                if (!want) { onDone(from); return@launch }
                onRequest!!.invoke(from) { ok ->
                    // Answer on <topic>/back, sealed with the same one-time key (only that phone has it).
                    if (ok) ws?.send(Mqtt.publish("$topic/back", seal(credentials()).toByteArray(), retain = true).toByteString())
                    finished = true
                    CoroutineScope(Dispatchers.IO).launch { delay(2_000); ws?.close(1000, null) }
                    if (ok) onDone(from) else onError("Отклонено")
                }
            }
        } catch (e: Exception) {
            onError("Не удалось расшифровать: ${e.message}")
        }
    }

    private fun seal(json: JSONObject): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return Base64.encodeToString(nonce + c.doFinal(json.toString().toByteArray()), Base64.NO_WRAP)
    }

    fun stop() { finished = true; pinger?.cancel(); ws?.close(1000, null) }

    companion object {
        /** This device's logins in the pairing format (what the iPhone sends). */
        fun credentials(from: String = Store.deviceName): JSONObject = JSONObject().apply {
            put("from", from)
            Store.libToken?.let { t ->
                put("animelib", JSONObject().put("token", t).put("refresh", Store.libRefresh ?: JSONObject.NULL)
                    .put("username", Store.libUser ?: JSONObject.NULL).put("userId", Store.libUserId ?: JSONObject.NULL))
            }
            Store.shikiJson?.let { put("shiki", it) }
            Store.githubToken?.let { t -> put("github", JSONObject().put("token", t).put("gist", Store.gistId ?: JSONObject.NULL)) }
        }
    }
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
