package ru.qmods.anixq.mobile.data

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.Mqtt
import ru.qmods.anixq.tv.data.Pairing
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Sync
import ru.qmods.anixq.tv.data.int
import ru.qmods.anixq.tv.data.str
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phone side of "Подключить телефон" on the TV. The TV's QR holds a one-time channel and key.
 * We send what we have (like the iPhone app) and, when this phone lacks GitHub or AnimeLib,
 * ask for the TV's logins; the TV shows who asks and the person allows it on the TV.
 */
object PairSender {
    data class Target(val channel: String, val key: ByteArray)

    /** anixq://pair/<channel>?k=<key>  or the site page …/tv/#c=<channel>&k=<key>. */
    fun parse(text: String): Target? = runCatching {
        val u = Uri.parse(text.trim())
        val (ch, k) = when {
            u.scheme == "anixq" && u.host == "pair" -> (u.pathSegments.firstOrNull() ?: return null) to (u.getQueryParameter("k") ?: return null)
            u.fragment?.contains("c=") == true -> {
                val f = u.fragment!!.split("&").associate { it.substringBefore("=") to it.substringAfter("=", "") }
                (f["c"] ?: return null) to (f["k"] ?: return null)
            }
            else -> return null
        }
        val key = Base64.decode(k, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        if (ch.isEmpty() || key.size != 32) null else Target(ch, key)
    }.getOrNull()

    /** Result for the UI. */
    sealed interface Outcome {
        data object Sent : Outcome
        data class Received(val what: List<String>) : Outcome
    }

    suspend fun exchange(target: Target, onWaiting: () -> Unit): Outcome {
        val want = Store.githubToken == null || Store.libToken == null
        val message = Pairing.credentials("Android (${android.os.Build.MODEL})").put("want", want)
        val topic = "anixq/pair/${target.channel}"
        val sent = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<JSONObject>()
        val req = Request.Builder().url("wss://public.cloud.shiftr.io").header("Sec-WebSocket-Protocol", "mqtt").build()
        val ws = Api.http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(Mqtt.connect("anixq-and-" + target.channel.take(8) + "-" + (1000..9999).random(), "public", "public").toByteString())
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Mqtt.parse(bytes.toByteArray()) { type, body ->
                    when (type) {
                        2 -> {
                            if (want) webSocket.send(Mqtt.subscribe("$topic/back").toByteString())
                            // Retained: the TV gets it even if it reconnects a moment later.
                            webSocket.send(Mqtt.publish(topic, seal(target.key, message), retain = true).toByteString())
                            sent.complete(Unit)
                        }
                        3 -> {
                            val (t, payload) = Mqtt.publishBody(body)
                            if (t == "$topic/back" && payload.isNotEmpty()) open(target.key, String(payload))?.let {
                                webSocket.send(Mqtt.publish("$topic/back", ByteArray(0), retain = true).toByteString())
                                reply.complete(it)
                            }
                        }
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                sent.completeExceptionally(t); reply.completeExceptionally(t)
            }
        })
        try {
            withTimeout(15_000) { sent.await() }
            if (!want) { kotlinx.coroutines.delay(800); return Outcome.Sent }
            onWaiting()
            val r = try { withTimeout(120_000) { reply.await() } }
                catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw Exception("телевизор не ответил — нажмите «Разрешить» на ТВ") }
            return Outcome.Received(apply(r))
        } finally { ws.close(1000, null) }
    }

    /** Takes the TV's logins this phone doesn't have yet. */
    private suspend fun apply(r: JSONObject): List<String> {
        val got = ArrayList<String>()
        r.optJSONObject("animelib")?.let { a ->
            if (Store.libToken == null && a.str("token") != null) {
                Store.setLib(a.str("token"), a.str("refresh"), a.str("username"), a.int("userId")); Api.loadProfile(); got += "AnimeLib"
            }
        }
        r.optJSONObject("shiki")?.let { if (Store.shikiJson == null) { Store.setShiki(it); got += "Shikimori" } }
        r.optJSONObject("github")?.let { g ->
            val t = g.str("token")
            if (Store.githubToken == null && t != null) { Sync.finishLogin(t, g.str("gist")); got += "синхронизация" }
        }
        return got
    }

    private fun seal(key: ByteArray, json: JSONObject): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return Base64.encodeToString(nonce + c.doFinal(json.toString().toByteArray()), Base64.NO_WRAP).toByteArray()
    }

    private fun open(key: ByteArray, b64: String): JSONObject? = runCatching {
        val sealed = Base64.decode(b64.trim(), Base64.DEFAULT)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        JSONObject(String(c.doFinal(sealed, 12, sealed.size - 12)))
    }.getOrNull()
}
