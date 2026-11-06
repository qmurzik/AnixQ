package ru.qmods.anixq.tv.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class Pos(val pos: Double, val dur: Double, val at: Double) {
    val progress: Double get() = if (dur > 0) (pos / dur).coerceIn(0.0, 1.0) else 0.0
}

data class WatchEntry(
    val title: Title, val episodeId: Int, val ep: String, val epName: String,
    val teamId: Int, val teamName: String, val pos: Double, val dur: Double, val at: Double,
) {
    val progress: Double get() = if (dur > 0) (pos / dur).coerceIn(0.0, 1.0) else 0.0
}

data class BookmarkEntry(val title: Title, val status: Bookmark, val at: Double)

/** Everything the user did on this TV; mirrored to the GitHub Gist by [Sync]. */
object Store {
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    /** Bumped on every change: screens observe it to refresh. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    val positions = HashMap<Int, Pos>()
    val history = ArrayList<WatchEntry>()
    val watched = HashMap<Int, MutableSet<String>>()
    val bookmarks = HashMap<String, BookmarkEntry>()
    val removedBookmarks = HashMap<String, Double>()
    var dubs: List<String> = emptyList(); private set
    private var dubsAt = 0.0

    // Settings
    var autoSkipOpening: Boolean
        get() = prefs.getBoolean("autoSkip", false)
        set(v) { prefs.edit().putBoolean("autoSkip", v).apply(); bump() }
    var autoNext: Boolean
        get() = prefs.getBoolean("autoNext", true)
        set(v) { prefs.edit().putBoolean("autoNext", v).apply(); bump() }
    var preferredQuality: Int
        get() = prefs.getInt("quality", 1080)
        set(v) { prefs.edit().putInt("quality", v).apply(); bump() }

    // Accounts (TV storage is private to the app)
    val libToken get() = prefs.getString("lib.token", null)
    val libRefresh get() = prefs.getString("lib.refresh", null)
    val libUser get() = prefs.getString("lib.user", null)
    val libUserId get() = prefs.getInt("lib.userId", 0).takeIf { it > 0 }
    val githubToken get() = prefs.getString("gh.token", null)
    val gistId get() = prefs.getString("gh.gist", null)
    val githubLogin get() = prefs.getString("gh.login", null)
    val shikiNickname get() = prefs.getString("shiki.nickname", null)

    fun setLib(token: String?, refresh: String?, user: String?, userId: Int?) {
        prefs.edit().putString("lib.token", token).putString("lib.refresh", refresh).putString("lib.user", user)
            .putInt("lib.userId", userId ?: 0).apply()
        Api.clearCache()
        bump()
    }

    fun setGithub(token: String?, gist: String?, login: String? = githubLogin) {
        prefs.edit().putString("gh.token", token).putString("gh.gist", gist).putString("gh.login", login).apply()
        bump()
    }

    fun setShiki(json: JSONObject?) {
        prefs.edit().putString("shiki.json", json?.toString()).putString("shiki.nickname", json?.str("nickname")).apply()
        bump()
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("anixq", Context.MODE_PRIVATE)
        prefs.getString("library", null)?.let { runCatching { load(JSONObject(it)) } }
    }

    private fun bump() { _version.value += 1 }

    private fun now() = System.currentTimeMillis() / 1000.0 + Sync.serverClockOffset

    // MARK: Queries

    fun resume(episodeId: Int): Double? = positions[episodeId]?.takeIf { it.pos > 5 && it.progress < 0.95 }?.pos
    fun progress(episodeId: Int): Double = positions[episodeId]?.progress ?: 0.0
    fun lastEntry(titleId: Int): WatchEntry? = history.firstOrNull { it.title.id == titleId }
    fun isWatched(titleId: Int, number: String) = watched[titleId]?.contains(number) == true
    fun bookmark(slug: String) = bookmarks[slug]?.status
    fun titles(status: Bookmark) = bookmarks.values.filter { it.status == status }.sortedByDescending { it.at }.map { it.title }
    val continueList: List<WatchEntry> get() = history.sortedByDescending { it.at }.take(20)

    // MARK: Changes

    fun updateProgress(title: Title, ep: Episode, team: Team, pos: Double, dur: Double) {
        val t = now()
        positions[ep.id] = Pos(pos, dur, t)
        history.removeAll { it.title.id == title.id }
        history.add(0, WatchEntry(title, ep.id, ep.number, ep.name ?: "", team.id, team.name, pos, dur, t))
        changed()
    }

    fun markWatched(title: Title, ep: Episode) {
        val set = watched.getOrPut(title.id) { mutableSetOf() }
        if (set.add(ep.number)) changed()
        if (bookmark(title.slug) == null || bookmark(title.slug) == Bookmark.PLANNED) setBookmark(title, Bookmark.WATCHING)
    }

    fun setBookmark(title: Title, status: Bookmark?) {
        if (status == null) {
            bookmarks.remove(title.slug)
            removedBookmarks[title.slug] = now()
        } else {
            bookmarks[title.slug] = BookmarkEntry(title, status, now())
            removedBookmarks.remove(title.slug)
        }
        changed()
    }

    fun setDubs(list: List<String>) { dubs = list; dubsAt = now(); changed() }

    fun useDub(name: String) { if (name !in dubs) setDubs(dubs + name) }

    fun clearHistory() { history.clear(); changed() }

    private fun changed() {
        bump()
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            prefs.edit().putString("library", exportSync().toString()).apply()
        }
        Sync.changed()
    }

    private fun load(d: JSONObject) {
        importSync(d, initial = true)
    }

    // MARK: Sync document (docs/sync-format.md)

    fun exportSync(): JSONObject {
        val titles = JSONObject()
        history.forEach { titles.put(it.title.id.toString(), it.title.toSync()) }
        bookmarks.values.forEach { titles.put(it.title.id.toString(), it.title.toSync()) }
        val progress = JSONObject()
        positions.forEach { (eid, p) -> progress.put(eid.toString(), JSONObject().put("pos", p.pos).put("dur", p.dur).put("at", p.at)) }
        history.forEach { e ->
            val p = progress.optJSONObject(e.episodeId.toString()) ?: JSONObject().put("pos", e.pos).put("dur", e.dur).put("at", e.at)
            p.put("at", maxOf(p.optDouble("at", 0.0), e.at))
            p.put("title", e.title.id).put("ep", e.ep).put("epName", e.epName).put("team", e.teamId).put("teamName", e.teamName)
            progress.put(e.episodeId.toString(), p)
        }
        val w = JSONObject()
        watched.forEach { (tid, set) ->
            if (set.isNotEmpty()) w.put(tid.toString(), JSONObject()
                .put("eps", JSONArray(set.sortedBy { it.toDoubleOrNull() ?: 0.0 }))
                .put("at", history.firstOrNull { it.title.id == tid }?.at ?: 0.0))
        }
        val b = JSONObject()
        bookmarks.forEach { (slug, e) -> b.put(slug, JSONObject().put("title", e.title.id).put("status", e.status.raw).put("at", e.at)) }
        removedBookmarks.forEach { (slug, at) -> if (!bookmarks.containsKey(slug)) b.put(slug, JSONObject().put("status", JSONObject.NULL).put("at", at)) }
        return JSONObject().put("v", 1).put("device", "Android TV").put("updated", 0)
            .put("titles", titles).put("progress", progress).put("watched", w).put("bookmarks", b)
            .put("prefs", JSONObject().put("dubs", JSONArray(dubs)).put("at", dubsAt))
    }

    /** Newest wins per key; watched sets are united. Returns true if anything changed. */
    fun importSync(d: JSONObject, initial: Boolean = false): Boolean {
        var changed = false
        val titles = HashMap<Int, Title>()
        d.optJSONObject("titles")?.let { t -> t.keys().forEach { k -> t.optJSONObject(k)?.let(Title::fromSync)?.let { titles[it.id] = it } } }
        fun title(id: Int?): Title? = id?.let { titles[it] ?: history.firstOrNull { e -> e.title.id == it }?.title ?: bookmarks.values.firstOrNull { b -> b.title.id == it }?.title }

        d.optJSONObject("progress")?.let { pr ->
            pr.keys().forEach { k ->
                val eid = k.toIntOrNull() ?: return@forEach
                val p = pr.optJSONObject(k) ?: return@forEach
                val at = p.optDouble("at", 0.0); val dur = p.optDouble("dur", 0.0); val pos = p.optDouble("pos", 0.0)
                if ((positions[eid]?.at ?: 0.0) < at && dur > 0) { positions[eid] = Pos(pos, dur, at); changed = true }
                val t = title(p.int("title"))
                val ep = p.str("ep"); val team = p.int("team")
                if (t != null && ep != null && team != null) {
                    val local = history.firstOrNull { it.title.id == t.id }
                    if ((local?.at ?: 0.0) < at) {
                        history.removeAll { it.title.id == t.id }
                        history.add(WatchEntry(t, eid, ep, p.str("epName") ?: "", team, p.str("teamName") ?: "", pos, dur, at))
                        changed = true
                    }
                }
            }
        }
        if (changed) history.sortByDescending { it.at }
        d.optJSONObject("watched")?.let { w ->
            w.keys().forEach { k ->
                val tid = k.toIntOrNull() ?: return@forEach
                val eps = w.optJSONObject(k)?.optJSONArray("eps")?.strings() ?: return@forEach
                val set = watched.getOrPut(tid) { mutableSetOf() }
                if (set.addAll(eps)) changed = true
            }
        }
        d.optJSONObject("bookmarks")?.let { b ->
            b.keys().forEach { slug ->
                val x = b.optJSONObject(slug) ?: return@forEach
                val at = x.optDouble("at", 0.0)
                val localAt = bookmarks[slug]?.at ?: removedBookmarks[slug] ?: 0.0
                if (at <= localAt) return@forEach
                val status = if (x.isNull("status")) null else Bookmark.of(x.int("status"))
                val t = title(x.int("title"))
                if (status != null && t != null) {
                    bookmarks[slug] = BookmarkEntry(t, status, at); removedBookmarks.remove(slug); changed = true
                } else if (x.isNull("status")) {
                    if (bookmarks.remove(slug) != null) changed = true
                    removedBookmarks[slug] = at
                }
            }
        }
        d.optJSONObject("prefs")?.let { p ->
            val at = p.optDouble("at", 0.0)
            if (at > dubsAt) { dubs = p.optJSONArray("dubs")?.strings() ?: dubs; dubsAt = at; changed = true }
        }
        if (changed && !initial) {
            bump()
            prefs.edit().putString("library", exportSync().toString()).apply()
        }
        return changed
    }
}
