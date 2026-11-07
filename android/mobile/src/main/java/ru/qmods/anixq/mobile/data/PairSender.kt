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
import ru.qmods.anixq.tv.data.Store
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phone side of "Подключить телефон" on the TV: the TV shows a QR with a one-time channel and key;
 * we send our accounts there, encrypted with that key (only that TV can read them) — exactly what
 * the iPhone app does.
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

    /** What the TV receives: the same fields the iPhone sends. */
    fun payload(): JSONObject = JSONObject().apply {
        put("from", "Android (${android.os.Build.MODEL})")
        Store.libToken?.let { t ->
            put("animelib", JSONObject().put("token", t).put("refresh", Store.libRefresh ?: JSONObject.NULL)
                .put("username", Store.libUser ?: JSONObject.NULL).put("userId", Store.libUserId ?: JSONObject.NULL))
        }
        Store.shikiJson?.let { put("shiki", it) }
        Store.githubToken?.let { t -> put("github", JSONObject().put("token", t).put("gist", Store.gistId ?: JSONObject.NULL)) }
    }

    suspend fun send(target: Target, message: JSONObject = payload()) {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(target.key, "AES"), GCMParameterSpec(128, nonce))
        val sealed = Base64.encodeToString(nonce + c.doFinal(message.toString().toByteArray()), Base64.NO_WRAP).toByteArray()
        val done = CompletableDeferred<Unit>()
        val req = Request.Builder().url("wss://public.cloud.shiftr.io").header("Sec-WebSocket-Protocol", "mqtt").build()
        val ws = Api.http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(Mqtt.connect("anixq-and-" + target.channel.take(8) + "-" + (1000..9999).random(), "public", "public").toByteString())
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Mqtt.parse(bytes.toByteArray()) { type, _ ->
                    if (type == 2) {
                        // Retained: the TV gets it even if it reconnects a moment later.
                        webSocket.send(Mqtt.publish("anixq/pair/${target.channel}", sealed, retain = true).toByteString())
                        done.complete(Unit)
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.completeExceptionally(t) }
        })
        try { withTimeout(12_000) { done.await() }; kotlinx.coroutines.delay(800) } finally { ws.close(1000, null) }
    }
}
