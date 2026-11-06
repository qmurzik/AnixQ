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
    val shikiJson: JSONObject? get() = prefs.getString("shiki.json", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?.takeIf { it.str("token") != null || it.str("cookies") != null }

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

    /** Home rows from the last run: shown instantly at start, refreshed in the background. */
    fun loadHomeCache(): List<Pair<String, List<Title>>> = runCatching {
        val a = JSONArray(prefs.getString("home.cache", "[]"))
        (0 until a.length()).map { i ->
            val r = a.getJSONObject(i); val t = r.getJSONArray("t")
            r.getString("n") to (0 until t.length()).mapNotNull { Title.fromSync(t.getJSONObject(it)) }
        }.filter { it.second.isNotEmpty() }
    }.getOrDefault(emptyList())

    fun saveHomeCache(rows: List<Pair<String, List<Title>>>) {
        val a = JSONArray()
        rows.forEach { (n, ts) -> a.put(JSONObject().put("n", n).put("t", JSONArray().apply { ts.forEach { put(it.toSync()) } })) }
        scope.launch { prefs.edit().putString("home.cache", a.toString()).apply() }
    }

    /** Last searches, newest first (kept between launches). */
    var recentSearches: List<String>
        get() = prefs.getString("search.recent", "")!!.split("\n").filter { it.isNotBlank() }
        set(v) { prefs.edit().putString("search.recent", v.take(8).joinToString("\n")).apply() }

    // Phone remote channel (shared through the gist; see Remote)
    val remoteChannel: String get() = prefs.getString("rc.channel", "") ?: ""
    val remoteKey: String get() = prefs.getString("rc.key", "") ?: ""
    private val remoteAt: Double get() = prefs.getString("rc.at", null)?.toDoubleOrNull() ?: 0.0

    fun ensureRemote() {
        if (remoteChannel.isNotEmpty() && remoteKey.isNotEmpty()) return
        val r = java.security.SecureRandom()
        val abc = "abcdefghjkmnpqrstuvwxyz23456789"
        val ch = (1..20).map { abc[r.nextInt(abc.length)] }.joinToString("")
        val key = android.util.Base64.encodeToString(ByteArray(32).also { r.nextBytes(it) },
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        setRemote(ch, key, now())
        changed()
    }

    private fun setRemote(ch: String, key: String, at: Double) {
        prefs.edit().putString("rc.channel", ch).putString("rc.key", key).putString("rc.at", at.toString()).commit()
    }

    var subtitles: Boolean
        get() = prefs.getBoolean("subtitles", true)
        set(v) { prefs.edit().putBoolean("subtitles", v).apply() }

    var screensaver: Boolean
        get() = prefs.getBoolean("screensaver", true)
        set(v) { prefs.edit().putBoolean("screensaver", v).apply(); bump() }

    var navSounds: Boolean
        get() = prefs.getBoolean("navSounds", true)
        set(v) { prefs.edit().putBoolean("navSounds", v).apply(); bump() }

    /** Episodes watched to the end and hours spent, for the "Моё" header. */
    fun stats(): Triple<Int, Int, Int> {
        val eps = watched.values.sumOf { it.size }
        val seconds = positions.values.sumOf { if (it.progress > 0.9) it.dur else it.pos }
        val titles = watched.count { it.value.isNotEmpty() }
        return Triple(eps, (seconds / 3600).toInt(), titles)
    }

    private var loadedName: String? = null

    /** Load a profile's data ([name] = its SharedPreferences file). Re-calling with the same name is a no-op. */
    fun init(context: Context, name: String? = null) {
        if (name == null && loadedName != null) return
        val n = name ?: run { Profiles.init(context); Profiles.prefsName(Profiles.current.value!!) }
        if (n == loadedName) return
        loadedName = n
        positions.clear(); history.clear(); watched.clear(); bookmarks.clear(); removedBookmarks.clear()
        dubs = emptyList(); dubsAt = 0.0
        prefs = context.getSharedPreferences(n, Context.MODE_PRIVATE)
        prefs.getString("library", null)?.let { runCatching { load(JSONObject(it)) } }
        bump()
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
            .apply { if (remoteChannel.isNotEmpty()) put("remote", JSONObject().put("channel", remoteChannel).put("key", remoteKey).put("at", remoteAt)) }
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
        d.optJSONObject("remote")?.let { r ->
            val at = r.optDouble("at", 0.0)
            val ch = r.str("channel"); val k = r.str("key")
            if (ch != null && k != null && (remoteChannel.isEmpty() || at > remoteAt) && (ch != remoteChannel || k != remoteKey)) {
                setRemote(ch, k, at); changed = true
                if (!initial) Remote.restart()
            }
        }
        if (changed && !initial) {
            bump()
            prefs.edit().putString("library", exportSync().toString()).apply()
        }
        return changed
    }
}
