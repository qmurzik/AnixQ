package ru.qmods.anixq.mobile.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.qmods.anixq.tv.data.Api
import ru.qmods.anixq.tv.data.Episode
import ru.qmods.anixq.tv.data.Player
import ru.qmods.anixq.tv.data.Segment
import ru.qmods.anixq.tv.data.Team
import ru.qmods.anixq.tv.data.Title
import java.io.File

/**
 * Episodes saved on the phone for watching offline. The system download manager does the work
 * (background, notification, resumes after a network drop); we keep what's needed to play offline:
 * the title, the episode list, the dub and its opening/ending times.
 */
object Downloads {
    enum class State { QUEUED, RUNNING, DONE, FAILED }

    data class Item(
        val title: Title, val episode: Episode, val team: Team, val quality: String, val file: String,
        val systemId: Long, val segments: List<Segment>, val state: State = State.QUEUED, val progress: Float = 0f, val bytes: Long = 0,
    ) {
        val key get() = episode.id
    }

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs get() = app.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    val items = MutableStateFlow<Map<Int, Item>>(emptyMap())
    /** Full episode lists of downloaded titles (to open them with no network). */
    private val episodeLists = HashMap<Int, List<Episode>>()
    private var watching = false

    var quality: Int
        get() = prefs.getInt("quality", 720)
        set(v) { prefs.edit().putInt("quality", v).apply() }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        load()
        watch()
    }

    private val dm get() = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun item(episodeId: Int): Item? = items.value[episodeId]
    fun localFile(episodeId: Int): File? = item(episodeId)?.takeIf { it.state == State.DONE }?.let { File(it.file) }?.takeIf { it.exists() }
    fun episodes(titleId: Int): List<Episode>? = episodeLists[titleId]
    val activeCount get() = items.value.values.count { it.state == State.QUEUED || it.state == State.RUNNING }

    /** Saved episode as a player the regular player code understands (no network needed). */
    fun offlinePlayer(episodeId: Int): Player? {
        val it = item(episodeId) ?: return null
        val f = localFile(episodeId) ?: return null
        return Player(it.team, "animelib", null, mapOf("Скачано" to Uri.fromFile(f).toString()), it.segments, null, null)
    }

    /** Queue episodes of a title with a dub; resolves the video address for each first. */
    fun enqueue(title: Title, allEpisodes: List<Episode>, episodes: List<Episode>, teamId: Int?, onResult: (String) -> Unit) {
        episodeLists[title.id] = allEpisodes
        scope.launch {
            var added = 0; var missing = 0
            for (ep in episodes) {
                if (items.value[ep.id]?.state.let { it == State.DONE || it == State.RUNNING || it == State.QUEUED }) continue
                try {
                    val players = Api.players(ep.id)
                    val p = players.firstOrNull { it.team.id == teamId && it.isNative } ?: players.firstOrNull { it.isNative }
                    if (p == null) { missing++; continue }
                    val keys = p.hrefs.keys.sortedByDescending { it.removeSuffix("p").toIntOrNull() ?: 0 }
                    val q = keys.firstOrNull { (it.removeSuffix("p").toIntOrNull() ?: 0) <= quality } ?: keys.last()
                    val href = p.hrefs.getValue(q)
                    val server = Api.rankServers(href).firstOrNull() ?: continue
                    val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: app.filesDir, "anixq").apply { mkdirs() }
                    val file = File(dir, "${title.id}_${ep.id}_${p.team.id}.mp4")
                    val req = DownloadManager.Request(Uri.parse(Api.videoUrl(server, href)))
                        .addRequestHeader("Referer", "${Api.SITE}/")
                        .setTitle("${title.title} — серия ${ep.number}")
                        .setDescription("AnixQ · ${p.team.name} · $q")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        .setDestinationUri(Uri.fromFile(file))
                        .setAllowedOverMetered(true)
                    val id = dm.enqueue(req)
                    put(Item(title, ep, p.team, q, file.path, id, p.segments))
                    added++
                } catch (e: Exception) { missing++ }
            }
            save()
            withContext(Dispatchers.Main) {
                onResult(when {
                    added > 0 && missing == 0 -> "Скачиваем: $added ${if (added == 1) "серия" else "серий"}"
                    added > 0 -> "Скачиваем $added, для $missing нет видео без рекламы"
                    missing > 0 -> "Для этих серий нет видео AnimeLib — скачать нельзя"
                    else -> "Уже скачано"
                })
            }
            watch()
        }
    }

    fun remove(episodeId: Int) {
        val it = item(episodeId) ?: return
        runCatching { dm.remove(it.systemId) }
        File(it.file).delete()
        items.value = items.value - episodeId
        save()
    }

    fun removeTitle(titleId: Int) = items.value.values.filter { it.title.id == titleId }.forEach { remove(it.key) }

    private fun put(i: Item) { items.value = items.value + (i.key to i) }

    /** Progress from the system download manager while something is downloading. */
    private fun watch() {
        if (watching) return
        watching = true
        scope.launch {
            while (isActive) {
                val active = items.value.values.filter { it.state == State.QUEUED || it.state == State.RUNNING }
                if (active.isEmpty()) break
                for (it in active) {
                    runCatching {
                        dm.query(DownloadManager.Query().setFilterById(it.systemId)).use { c ->
                            if (!c.moveToFirst()) { put(it.copy(state = State.FAILED)); return@use }
                            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            val st = when (status) {
                                DownloadManager.STATUS_SUCCESSFUL -> State.DONE
                                DownloadManager.STATUS_FAILED -> State.FAILED
                                DownloadManager.STATUS_RUNNING -> State.RUNNING
                                else -> State.QUEUED
                            }
                            put(it.copy(state = st, bytes = if (st == State.DONE) File(it.file).length() else done,
                                progress = if (total > 0) done.toFloat() / total else 0f))
                        }
                    }
                }
                save()
                delay(1000)
            }
            watching = false
        }
    }

    // MARK: storage

    private fun save() {
        val a = JSONArray()
        items.value.values.forEach { i ->
            a.put(JSONObject().put("title", i.title.toSync())
                .put("ep", JSONObject().put("id", i.episode.id).put("n", i.episode.number).put("name", i.episode.name ?: "").put("i", i.episode.index))
                .put("team", JSONObject().put("id", i.team.id).put("name", i.team.name).put("subs", i.team.isSubtitles))
                .put("q", i.quality).put("file", i.file).put("sys", i.systemId).put("state", i.state.name).put("bytes", i.bytes)
                .put("seg", JSONArray().apply { i.segments.forEach { s -> put(JSONObject().put("k", s.kind).put("f", s.from).put("t", s.to)) } }))
        }
        val lists = JSONObject()
        episodeLists.forEach { (tid, eps) ->
            lists.put(tid.toString(), JSONArray().apply { eps.forEach { e -> put(JSONObject().put("id", e.id).put("n", e.number).put("name", e.name ?: "").put("i", e.index)) } })
        }
        prefs.edit().putString("items", a.toString()).putString("lists", lists.toString()).apply()
    }

    private fun load() {
        runCatching {
            val lists = JSONObject(prefs.getString("lists", "{}")!!)
            lists.keys().forEach { k ->
                val a = lists.getJSONArray(k)
                episodeLists[k.toInt()] = (0 until a.length()).map { i -> a.getJSONObject(i).let { e -> Episode(e.getInt("id"), e.getString("n"), e.optString("name").ifEmpty { null }, e.getInt("i")) } }
            }
            val a = JSONArray(prefs.getString("items", "[]"))
            items.value = (0 until a.length()).mapNotNull { i ->
                val x = a.getJSONObject(i)
                val t = Title.fromSync(x.getJSONObject("title")) ?: return@mapNotNull null
                val e = x.getJSONObject("ep"); val tm = x.getJSONObject("team")
                val seg = x.optJSONArray("seg") ?: JSONArray()
                Item(t, Episode(e.getInt("id"), e.getString("n"), e.optString("name").ifEmpty { null }, e.getInt("i")),
                    Team(tm.getInt("id"), tm.getString("name"), tm.optBoolean("subs")), x.getString("q"), x.getString("file"), x.getLong("sys"),
                    (0 until seg.length()).map { j -> seg.getJSONObject(j).let { s -> Segment(s.getString("k"), s.getDouble("f"), s.getDouble("t")) } },
                    State.valueOf(x.getString("state")), if (x.getString("state") == "DONE") 1f else 0f, x.optLong("bytes"))
            }.associateBy { it.key }
        }
    }
}
