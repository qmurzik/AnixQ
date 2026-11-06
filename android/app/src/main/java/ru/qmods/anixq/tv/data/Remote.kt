package ru.qmods.anixq.tv.data

import android.content.Context
import android.content.Intent
import android.media.AudioManager
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
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What the player exposes to the phone remote. */
interface RemoteTarget {
    fun remoteCommand(cmd: JSONObject)
    fun remoteState(): JSONObject
}

/**
 * Phone ↔ TV over the internet (any network, any city): end-to-end encrypted JSON through the same
 * public MQTT relay as pairing. The channel and key live in the private GitHub Gist, so a phone
 * synced with the same GitHub account finds this TV by itself.
 *   anixq/rc/<channel>/cmd    phone → TV  (play, toggle, seek, next, prev, skip, volume, stop)
 *   anixq/rc/<channel>/state  TV → phone  (retained: what is on screen now)
 */
object Remote {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: WebSocket? = null
    private var pinger: Job? = null
    private var app: Context? = null
    @Volatile private var connected = false
    @Volatile private var wanted = false
    private val seen = LinkedHashSet<String>()

    val online = MutableStateFlow(false)
    /** The open player, if any. */
    @Volatile var target: RemoteTarget? = null
    /** Opens a player in the running UI; null when the activity isn't on screen. */
    @Volatile var navigator: ((JSONObject) -> Unit)? = null

    private val cmdTopic get() = "anixq/rc/${Store.remoteChannel}/cmd"
    private val stateTopic get() = "anixq/rc/${Store.remoteChannel}/state"
    /** Live progress between devices (retained per device, so a device coming online gets the latest at once). */
    private val livePrefix get() = "anixq/rc/${Store.remoteChannel}/live/"
    private val myLiveTopic get() = livePrefix + Store.deviceId

    fun start(context: Context) {
        app = context.applicationContext
        wanted = true
        // The channel is created only after the first sync (so a second TV adopts nothing by accident).
        if (ws == null && Store.remoteChannel.isNotEmpty()) connect()
    }

    /** Called after each sync: connect once a channel exists. */
    fun ensureConnected() { if (wanted && ws == null && Store.remoteChannel.isNotEmpty()) connect() }

    /** Channel changed (adopted from another device): reconnect on the new topics. */
    fun restart() { if (wanted) { val c = app; stop(); c?.let { start(it) } } }

    fun stop() { wanted = false; pinger?.cancel(); ws?.close(1000, null); ws = null; connected = false; online.value = false }

    private fun connect() {
        val req = Request.Builder().url("wss://public.cloud.shiftr.io").header("Sec-WebSocket-Protocol", "mqtt").build()
        ws = Api.http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(Mqtt.connect("anixq-tvrc-" + Store.remoteChannel.take(8) + "-" + (1000..9999).random(), "public", "public").toByteString())
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Mqtt.parse(bytes.toByteArray()) { type, body ->
                    when (type) {
                        2 -> {
                            connected = true; online.value = true
                            webSocket.send(Mqtt.subscribe(cmdTopic).toByteString())
                            webSocket.send(Mqtt.subscribe(livePrefix + "#").toByteString())
                            pendingLive?.let { publishLiveNow(it) }
                            pinger?.cancel()
                            pinger = scope.launch { while (isActive && connected) { delay(15_000); webSocket.send(byteArrayOf(0xC0.toByte(), 0).toByteString()) } }
                            publishState()
                        }
                        3 -> {
                            val (t, payload) = Mqtt.publishBody(body)
                            if (t == cmdTopic && payload.isNotEmpty()) open(String(payload))?.let { handle(it) }
                            else if (t.startsWith(livePrefix) && t != myLiveTopic && payload.isNotEmpty())
                                open(String(payload))?.let { doc -> scope.launch(Dispatchers.Main) { Store.importSync(doc) } }
                        }
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped()
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped()
        })
    }

    private fun dropped() {
        connected = false; online.value = false; pinger?.cancel(); ws = null
        if (wanted) scope.launch { delay(4_000); if (wanted && ws == null) connect() }
    }

    // MARK: crypto (AES-256-GCM, nonce ‖ ciphertext ‖ tag, base64 — same as pairing)

    private fun key() = SecretKeySpec(Base64.decode(Store.remoteKey, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), "AES")

    private fun open(b64: String): JSONObject? = try {
        val sealed = Base64.decode(b64.trim(), Base64.DEFAULT)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        JSONObject(String(c.doFinal(sealed, 12, sealed.size - 12)))
    } catch (e: Exception) { Log.d("AnixQ", "rc: bad message"); null }

    private fun seal(json: JSONObject): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, nonce))
        return Base64.encodeToString(nonce + c.doFinal(json.toString().toByteArray()), Base64.NO_WRAP)
    }

    // MARK: commands

    private fun handle(m: JSONObject) {
        // Ignore replays and stale commands (clock-tolerant: 2 minutes).
        val id = m.optString("id")
        val at = m.optDouble("at", 0.0)
        val now = System.currentTimeMillis() / 1000.0 + Sync.serverClockOffset
        if (id.isNotEmpty() && !seen.add(id)) return
        while (seen.size > 200) seen.remove(seen.first())
        if (at > 0 && kotlin.math.abs(now - at) > 120) return
        scope.launch(Dispatchers.Main) {
            when (m.optString("cmd")) {
                "play" -> {
                    val nav = navigator
                    if (nav != null) nav(m) else launchActivity(m)
                }
                "volume" -> (app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, if (m.optInt("dir") > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "ping" -> publishState()
                else -> target?.remoteCommand(m)
            }
        }
    }

    private fun launchActivity(m: JSONObject) {
        val ctx = app ?: return
        runCatching {
            ctx.startActivity(Intent(ctx, Class.forName("ru.qmods.anixq.tv.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("rc_play", m.toString()))
        }.onFailure { Log.w("AnixQ", "rc: can't open activity: ${it.message}") }
    }

    @Volatile private var pendingLive: JSONObject? = null

    /** Our latest change (a small sync document for one title) to the other devices — instant, no GitHub. */
    fun publishLive(doc: JSONObject) {
        if (Store.remoteChannel.isEmpty()) return
        if (connected) publishLiveNow(doc) else pendingLive = doc
    }

    private fun publishLiveNow(doc: JSONObject) {
        val w = ws ?: return
        pendingLive = null
        w.send(Mqtt.publish(myLiveTopic, seal(doc).toByteArray(), retain = true).toByteString())
    }

    /** Tell the phone what is on screen (retained, so a remote opened later sees it at once). */
    fun publishState() {
        val w = ws ?: return
        if (!connected) return
        val s = (target?.remoteState() ?: JSONObject().put("state", "idle"))
            .put("at", System.currentTimeMillis() / 1000.0 + Sync.serverClockOffset).put("device", android.os.Build.MODEL)
        w.send(Mqtt.publish(stateTopic, seal(s).toByteArray(), retain = true).toByteString())
    }
}
