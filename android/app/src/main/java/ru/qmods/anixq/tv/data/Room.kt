package ru.qmods.anixq.tv.data

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

/** What a room needs from the player. */
interface RoomPlayer {
    fun roomState(): JSONObject
    fun roomApply(m: JSONObject)
}

/**
 * "Смотреть вместе": the same watch rooms as the iPhone app and the web page
 * (qmurzik.github.io/AnixQ/room/#id). The TV hosts; friends join from AnixQ on iPhone or a browser.
 */
object Room {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: WebSocket? = null
    private var beat: Job? = null
    private var lastSent = 0L
    @Volatile private var connected = false
    @Volatile var applying = false; private set
    private val me = "tv-" + (100000..999999).random()

    data class ChatLine(val name: String, val text: String, val at: Long = System.currentTimeMillis())
    data class Poll(val id: String, val question: String, val options: List<String>, val votes: Map<String, Int> = emptyMap(), val closed: Boolean = false)

    val roomId = MutableStateFlow<String?>(null)
    /** What friends write / send — shown over the TV picture. */
    val chat = MutableStateFlow<List<ChatLine>>(emptyList())
    val reactions = MutableStateFlow<List<Pair<String, Long>>>(emptyList())
    val poll = MutableStateFlow<Poll?>(null)
    val people = MutableStateFlow<Map<String, Pair<String, Long>>>(emptyMap())
    @Volatile var player: RoomPlayer? = null

    fun link(id: String) = "https://qmurzik.github.io/AnixQ/room/#$id"
    private val topic get() = roomId.value?.let { "anixq/rooms/$it" }

    fun create(): String {
        val abc = "abcdefghjkmnpqrstuvwxyz23456789"
        val id = (1..10).map { abc.random() }.joinToString("")
        leave(silently = true)
        roomId.value = id
        connect()
        beat = scope.launch {
            while (isActive) {
                delay(10_000)
                val now = System.currentTimeMillis()
                people.value = people.value.filterValues { now - it.second < 45_000 }
                val playing = player?.roomState()?.optBoolean("playing") == true
                if (playing || now - lastSent > 25_000) sendState()
            }
        }
        return id
    }

    fun leave(silently: Boolean = false) {
        if (roomId.value == null) return
        if (!silently) send(JSONObject().put("type", "leave"))
        beat?.cancel(); ws?.close(1000, null); ws = null; connected = false
        roomId.value = null; people.value = emptyMap(); chat.value = emptyList(); reactions.value = emptyList(); poll.value = null
    }

    private fun connect() {
        val req = Request.Builder().url("wss://public.cloud.shiftr.io").header("Sec-WebSocket-Protocol", "mqtt").build()
        ws = Api.http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(Mqtt.connect("anixq-$me", "public", "public").toByteString())
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Mqtt.parse(bytes.toByteArray()) { type, body ->
                    when (type) {
                        2 -> {
                            connected = true
                            topic?.let { webSocket.send(Mqtt.subscribe(it).toByteString()) }
                            scope.launch { while (isActive && connected) { delay(15_000); webSocket.send(byteArrayOf(0xC0.toByte(), 0).toByteString()) } }
                            sendState()
                        }
                        3 -> {
                            val (t, payload) = Mqtt.publishBody(body)
                            if (t == topic) runCatching { received(JSONObject(String(payload))) }
                        }
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false; ws = null
                if (roomId.value != null) scope.launch { delay(3000); if (roomId.value != null && ws == null) connect() }
            }
        })
    }

    private fun received(m: JSONObject) {
        val from = m.optString("from")
        if (from == me || from.isEmpty()) return
        val name = m.optString("name").ifBlank { "Друг" }
        when (m.optString("type")) {
            "chat" -> m.optString("text").takeIf { it.isNotBlank() }?.let { chat.value = (chat.value + ChatLine(name, it.take(200))).takeLast(30) }
            "react" -> m.optString("emoji").takeIf { it.isNotBlank() }?.let {
                val now = System.currentTimeMillis()
                reactions.value = (reactions.value.filter { now - it.second < 4000 } + (it.take(4) to now)).takeLast(12)
            }
            "poll" -> {
                val opts = m.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                val id = m.optString("pollId")
                val cur = poll.value
                if (cur != null && cur.id == id && m.has("option")) poll.value = cur.copy(closed = true)
                else if (id.isNotEmpty() && opts.isNotEmpty()) poll.value = Poll(id, m.optString("question"), opts)
            }
            "vote" -> poll.value?.let { p -> if (p.id == m.optString("pollId")) poll.value = p.copy(votes = p.votes + (from to m.optInt("option"))) }
        }
        when (m.optString("type")) {
            "leave" -> { people.value = people.value - from; return }
            "join" -> { people.value = people.value + (from to (m.optString("name") to System.currentTimeMillis())); sendState(); return }
            "state" -> {
                people.value = people.value + (from to (m.optString("name") to System.currentTimeMillis()))
                scope.launch(Dispatchers.Main) {
                    applying = true
                    try { player?.roomApply(m) } finally { applying = false }
                }
            }
            else -> people.value = people.value + (from to (m.optString("name") to System.currentTimeMillis()))
        }
    }

    /** Our position after a local action (play/pause/seek/episode change). */
    fun sendState() {
        if (applying || roomId.value == null) return
        val s = player?.roomState() ?: return
        send(s.put("type", "state"))
    }

    private fun send(m: JSONObject) {
        val w = ws ?: return
        val t = topic ?: return
        if (!connected) return
        m.put("from", me).put("name", "AnixQ TV").put("sentAt", System.currentTimeMillis() / 1000.0 + Sync.serverClockOffset)
        lastSent = System.currentTimeMillis()
        w.send(Mqtt.publish(t, m.toString().toByteArray()).toByteString())
    }
}
