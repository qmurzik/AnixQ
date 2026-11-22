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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
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
 * Episodes saved on the phone for watching offline. Progressive files use the system downloader.
 * Adaptive streams use a Media3 segment cache. Both retain the metadata needed to play offline:
 * the title, the episode list, the dub and its opening/ending times.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object Downloads {
    enum class State { QUEUED, RUNNING, DONE, FAILED }

    data class Item(
        val title: Title, val episode: Episode, val team: Team, val quality: String, val file: String,
        val systemId: Long, val segments: List<Segment>, val state: State = State.QUEUED, val progress: Float = 0f, val bytes: Long = 0, val source: String = "animelib",
        val mediaUrl: String? = null,
    ) {
        val key get() = episode.id
    }

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs get() = app.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    val items = MutableStateFlow<Map<Int, Item>>(emptyMap())
    /** Full episode lists of downloaded titles (to open them with no network). */
    private val episodeLists = java.util.concurrent.ConcurrentHashMap<Int, List<Episode>>()
    private val adaptiveJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val streams by lazy { OfflineStreams(app) }
    private val queueLock = Mutex()
    private val adaptiveSlots = Semaphore(2)
    private val watching = java.util.concurrent.atomic.AtomicBoolean(false)

    var quality: Int
        get() = prefs.getInt("quality", 720)
        set(v) { prefs.edit().putInt("quality", v).apply() }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        load()
        watch()
    }

    private fun storedChoice(title: Title, players: List<Player>) = ru.qmods.anixq.tv.data.Store.playbackChoice(title, players)

    private val dm get() = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun item(episodeId: Int): Item? = items.value[episodeId]
    fun localFile(episodeId: Int): File? = item(episodeId)?.takeIf { it.state == State.DONE && it.mediaUrl == null }?.let { File(it.file) }?.takeIf { it.exists() }
    fun episodes(titleId: Int): List<Episode>? = episodeLists[titleId]
    val activeCount get() = items.value.values.count { it.state == State.QUEUED || it.state == State.RUNNING }

    /** Saved episode as a player the regular player code understands (no network needed). */
    fun offlinePlayer(episodeId: Int): Player? {
        val it = item(episodeId) ?: return null
        if (it.state != State.DONE) return null
        val url = it.mediaUrl?.takeIf { _ -> File(it.file).isDirectory }
            ?: localFile(episodeId)?.let { file -> Uri.fromFile(file).toString() }
            ?: return null
        return Player(it.team, "animelib", null, mapOf("Скачано" to url), it.segments, null, null, source = it.source, headers = emptyMap())
    }

    fun offlineDataSource(episodeId: Int, url: String): androidx.media3.datasource.DataSource.Factory? =
        item(episodeId)?.takeIf { it.state == State.DONE && it.mediaUrl == url }
            ?.let { streams.readOnly(File(it.file)) }

    /** Queue episodes of a title with a dub; resolves the video address for each first. */
    fun enqueue(title: Title, allEpisodes: List<Episode>, episodes: List<Episode>, teamId: Int?, onResult: (String) -> Unit) {
        episodeLists[title.id] = allEpisodes
        scope.launch {
            queueLock.withLock {
                var added = 0; var missing = 0
                for (ep in episodes) {
                    if (items.value[ep.id]?.state.let { it == State.DONE || it == State.RUNNING || it == State.QUEUED }) continue
                    try {
                        val players = ru.qmods.anixq.tv.data.sources.AnimeSources.players(title, ep)
                        val sources = ru.qmods.anixq.tv.data.sources.AnimeSources
                        val choice = players.firstOrNull { it.team.id == teamId } ?: storedChoice(title, players)
                            ?: players.firstOrNull { it.isNative } ?: players.firstOrNull(sources::supported)
                        val p = choice?.let {
                            if (it.isKodik && !it.canResolve) {
                                val kodik = sources.extractors.filterIsInstance<ru.qmods.anixq.tv.data.sources.extractors.KodikExtractor>().firstOrNull()
                                val url = Api.kodikUrl(it.kodikSrc!!)
                                val stream = kodik?.takeIf { extractor -> extractor.supports(url) }?.resolve(url, "${Api.SITE}/")
                                stream?.let { result -> it.copy(hrefs = result.qualities, headers = result.headers) }
                            } else sources.resolve(it, title, ep)
                        }
                        if (p == null) { missing++; continue }
                        val keys = p.hrefs.keys.filter { key ->
                            val href = p.hrefs.getValue(key)
                            !href.startsWith("http") || OfflineStreams.isAdaptive(href) ||
                                Uri.parse(href).path.orEmpty().endsWith(".mp4", ignoreCase = true)
                        }.sortedByDescending { ru.qmods.anixq.tv.data.sources.qualityHeight(it) }
                        if (keys.isEmpty()) { missing++; continue }
                        val q = keys.firstOrNull { (ru.qmods.anixq.tv.data.sources.qualityHeight(it)) <= quality } ?: keys.last()
                        val href = p.hrefs.getValue(q)
                        val direct = href.startsWith("http")
                        val downloadUrl = if (direct) href else {
                            val server = Api.rankServers(href).firstOrNull()
                            if (server == null) { missing++; continue }
                            Api.videoUrl(server, href)
                        }
                        if (items.value[ep.id]?.state == State.FAILED) remove(ep.id)
                        if (OfflineStreams.isAdaptive(downloadUrl)) {
                            val folder = File(app.filesDir, "offline/${ep.id}-${java.util.UUID.randomUUID()}")
                            put(Item(title, ep, p.team, q, folder.path, -1, p.segments, source = p.source, mediaUrl = downloadUrl))
                            downloadStream(items.value.getValue(ep.id), p.headers)
                            added++
                            continue
                        }
                        val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: app.filesDir, "anixq").apply { mkdirs() }
                        val file = File(dir, "${title.id}_${ep.id}_${p.team.id}.mp4")
                        val req = DownloadManager.Request(Uri.parse(downloadUrl))
                            .apply { p.headers.forEach { (key, value) -> addRequestHeader(key, value) } }
                            .setTitle("${title.title} — серия ${ep.number}")
                            .setDescription("AnixQ · ${p.team.name} · $q")
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                            .setDestinationUri(Uri.fromFile(file))
                            .setAllowedOverMetered(true)
                        val id = dm.enqueue(req)
                        put(Item(title, ep, p.team, q, file.path, id, p.segments, source = p.source))
                        added++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { missing++ }
                }
                save()
                withContext(Dispatchers.Main) {
                    onResult(when {
                        added > 0 && missing == 0 -> "Скачиваем: $added ${if (added == 1) "серия" else "серий"}"
                        added > 0 -> "Скачиваем $added. Не удалось добавить: $missing"
                        missing > 0 -> "Не удалось начать скачивание. Попробуйте другую озвучку или источник."
                        else -> "Уже скачано"
                    })
                }
                watch()
            }
        }
    }

    private fun downloadStream(entry: Item, headers: Map<String, String>) {
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val folder = File(entry.file)
            try {
                adaptiveSlots.withPermit {
                    try {
                        val downloader = streams.downloader(folder, checkNotNull(entry.mediaUrl), headers)
                        try {
                            runInterruptible {
                                downloader.download { _, bytes, percent ->
                                    update(entry.copy(state = State.RUNNING, bytes = bytes, progress = (percent / 100).coerceIn(0f, 1f)))
                                }
                            }
                        } finally {
                            downloader.cancel()
                        }
                    } finally {
                        streams.close(folder)
                    }
                }
                update(entry.copy(state = State.DONE, bytes = folder.walkTopDown().filter(File::isFile).sumOf(File::length), progress = 1f))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("AnixQ", "Segment download failed: ${e.javaClass.simpleName}")
                update(entry.copy(state = State.FAILED))
            } finally {
                adaptiveJobs.remove(entry.file)
                save()
            }
        }
        adaptiveJobs[entry.file] = job
        job.start()
    }

    fun remove(episodeId: Int) {
        val it = item(episodeId) ?: return
        items.update { current -> if (current[episodeId]?.file == it.file) current - episodeId else current }
        if (it.mediaUrl != null) {
            val job = adaptiveJobs[it.file]
            job?.cancel()
            scope.launch {
                job?.join()
                runCatching { streams.delete(File(it.file)) }
            }
        } else {
            runCatching { dm.remove(it.systemId) }
            File(it.file).delete()
        }
        save()
    }

    fun removeTitle(titleId: Int) = items.value.values.filter { it.title.id == titleId }.forEach { remove(it.key) }

    private fun put(i: Item) { items.update { it + (i.key to i) } }

    private fun update(i: Item) {
        items.update { current ->
            val existing = current[i.key]
            if (existing?.file == i.file && existing.systemId == i.systemId) current + (i.key to i) else current
        }
    }

    /** Progress from the system download manager while something is downloading. */
    private fun watch() {
        if (!watching.compareAndSet(false, true)) return
        scope.launch {
            while (isActive) {
                val active = items.value.values.filter { it.mediaUrl == null && (it.state == State.QUEUED || it.state == State.RUNNING) }
                if (active.isEmpty()) break
                for (it in active) {
                    runCatching {
                        dm.query(DownloadManager.Query().setFilterById(it.systemId)).use { c ->
                            if (!c.moveToFirst()) { update(it.copy(state = State.FAILED)); return@use }
                            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            val st = when (status) {
                                DownloadManager.STATUS_SUCCESSFUL -> State.DONE
                                DownloadManager.STATUS_FAILED -> State.FAILED
                                DownloadManager.STATUS_RUNNING -> State.RUNNING
                                else -> State.QUEUED
                            }
                            update(it.copy(state = st, bytes = if (st == State.DONE) File(it.file).length() else done,
                                progress = if (total > 0) done.toFloat() / total else 0f))
                        }
                    }
                }
                save()
                delay(1000)
            }
            watching.set(false)
            if (items.value.values.any { it.mediaUrl == null && (it.state == State.QUEUED || it.state == State.RUNNING) }) watch()
        }
    }

    // MARK: storage

    @Synchronized
    private fun save() {
        val a = JSONArray()
        items.value.values.forEach { i ->
            a.put(JSONObject().put("title", i.title.toSync()).put("source", i.source)
                .put("ep", JSONObject().put("id", i.episode.id).put("n", i.episode.number).put("name", i.episode.name ?: "").put("i", i.episode.index))
                .put("team", JSONObject().put("id", i.team.id).put("name", i.team.name).put("subs", i.team.isSubtitles))
                .put("mediaUrl", i.mediaUrl).put("q", i.quality).put("file", i.file).put("sys", i.systemId).put("state", i.state.name).put("bytes", i.bytes)
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
                    State.valueOf(x.getString("state")), if (x.getString("state") == "DONE") 1f else 0f, x.optLong("bytes"), x.optString("source", "animelib"), x.optString("mediaUrl").takeIf(String::isNotBlank))
            }.map { item ->
                if (item.mediaUrl != null && item.state in setOf(State.QUEUED, State.RUNNING)) item.copy(state = State.FAILED)
                else item
            }.associateBy { it.key }
        }
    }
}
