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
    /** titleId → when it was removed from "Продолжить просмотр" (synced, so it doesn't come back from another device). */
    val hiddenHistory = HashMap<Int, Double>()
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

    /** Colour tuning of the picture (JSON, see ui.Picture) and the sound mode (ui.SoundMode ordinal). */
    var picture: String?
        get() = prefs.getString("picture", null)
        set(v) { prefs.edit().putString("picture", v).apply() }
    var soundMode: Int
        get() = prefs.getInt("soundMode", 0)
        set(v) { prefs.edit().putInt("soundMode", v).apply() }

    /** Marathon: openings/endings skipped, episodes back to back, a break suggested every few. */
    var marathon: Boolean
        get() = prefs.getBoolean("marathon", false)
        set(v) { prefs.edit().putBoolean("marathon", v).apply(); bump() }
    /** Hide the names of episodes not watched yet. */
    var noSpoilers: Boolean
        get() = prefs.getBoolean("noSpoilers", false)
        set(v) { prefs.edit().putBoolean("noSpoilers", v).apply(); bump() }

    /** Phone: keep playing (sound) with the screen off / app in the background. */
    var backgroundAudio: Boolean
        get() = prefs.getBoolean("backgroundAudio", false)
        set(v) { prefs.edit().putBoolean("backgroundAudio", v).apply(); bump() }

    var subtitles: Boolean
        get() = prefs.getBoolean("subtitles", true)
        set(v) { prefs.edit().putBoolean("subtitles", v).apply() }

    /** Video upscaler: 0 off, 1 sharpen, 2 upscale + sharpen. */
    var upscale: Int
        get() = prefs.getInt("upscale", 0)
        set(v) { prefs.edit().putInt("upscale", v).apply() }

    var hoverPreview: Boolean
        get() = prefs.getBoolean("hoverPreview", true)
        set(v) { prefs.edit().putBoolean("hoverPreview", v).apply(); bump() }

    /** The screensaver plays muted clips of episodes (otherwise a slideshow of covers). */
    /** Your score for a title (1–10), set at the end of the last episode; also sent to Shikimori. */
    fun rating(titleId: Int): Int? = prefs.getInt("rating.$titleId", 0).takeIf { it > 0 }
    fun setRating(titleId: Int, score: Int) { prefs.edit().putInt("rating.$titleId", score).apply(); bump() }
    /** The end-of-title "Как вам?" was offered already (once per title, whatever the answer). */
    fun ratePrompted(titleId: Int) = prefs.getBoolean("ratePrompted.$titleId", false)
    fun markRatePrompted(titleId: Int) { prefs.edit().putBoolean("ratePrompted.$titleId", true).apply() }

    /** Phone: open the player straight in landscape (otherwise it follows how the phone is held). */
    var playerFullscreen: Boolean
        get() = prefs.getBoolean("playerFullscreen", false)
        set(v) { prefs.edit().putBoolean("playerFullscreen", v).apply(); bump() }

    /** Skip the ending (and the preview after it) by itself. */
    var autoSkipEnding: Boolean
        get() = prefs.getBoolean("autoSkipEnding", false)
        set(v) { prefs.edit().putBoolean("autoSkipEnding", v).apply(); bump() }

    /** Phone player gestures: brightness / volume swipes, seeking by a horizontal swipe. */
    var gestureLevels: Boolean
        get() = prefs.getBoolean("gestureLevels", true)
        set(v) { prefs.edit().putBoolean("gestureLevels", v).apply(); bump() }
    var gestureSeek: Boolean
        get() = prefs.getBoolean("gestureSeek", true)
        set(v) { prefs.edit().putBoolean("gestureSeek", v).apply(); bump() }

    /** Subtitle size (1 = normal) and a dark box behind them. */
    var subtitleScale: Float
        get() = prefs.getFloat("subtitleScale", 1f)
        set(v) { prefs.edit().putFloat("subtitleScale", v).apply(); bump() }
    var subtitleBox: Boolean
        get() = prefs.getBoolean("subtitleBox", false)
        set(v) { prefs.edit().putBoolean("subtitleBox", v).apply(); bump() }

    /** Timecoded comments flying over the video. */
    var danmaku: Boolean
        get() = prefs.getBoolean("danmaku", true)
        set(v) { prefs.edit().putBoolean("danmaku", v).apply(); bump() }

    /** AniLibria dub from AniLibria's own servers when they have the episode. */
    var libriaDirect: Boolean
        get() = prefs.getBoolean("libriaDirect", true)
        set(v) { prefs.edit().putBoolean("libriaDirect", v).apply(); bump() }

    /** Phone: notice the TV nearby over Bluetooth and offer to continue there. */
    var nearbyTv: Boolean
        get() = prefs.getBoolean("nearbyTv", false)
        set(v) { prefs.edit().putBoolean("nearbyTv", v).apply(); bump() }

    /** Phone: pause the TV while a call rings or is on. */
    var callPause: Boolean
        get() = prefs.getBoolean("callPause", false)
        set(v) { prefs.edit().putBoolean("callPause", v).apply(); bump() }

    var nearbyNotifiedAt: Long
        get() = prefs.getLong("nearbyNotifiedAt", 0)
        set(v) { prefs.edit().putLong("nearbyNotifiedAt", v).apply() }

    var callPausedAt: Long
        get() = prefs.getLong("callPausedAt", 0)
        set(v) { prefs.edit().putLong("callPausedAt", v).apply() }

    var screensaverVideo: Boolean
        get() = prefs.getBoolean("screensaverVideo", true)
        set(v) { prefs.edit().putBoolean("screensaverVideo", v).apply(); bump() }

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
    /** Low-memory boxes: background video previews off by default (they can still be enabled in Settings). */
    var weakDevice = false; private set

    fun init(context: Context, name: String? = null) {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            weakDevice = am.isLowRamDevice || mi.totalMem < 3L * 1024 * 1024 * 1024
        }
        if (name == null && loadedName != null) return
        val n = name ?: run { Profiles.init(context); Profiles.prefsName(Profiles.current.value!!) }
        if (n == loadedName) return
        loadedName = n
        positions.clear(); history.clear(); watched.clear(); bookmarks.clear(); removedBookmarks.clear(); hiddenHistory.clear()
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

    /** "tv" on the TV app, "and" on the phone app (set before [init]). */
    var devicePrefix = "tv"
    /** Shown to the other devices ("device" in sync documents). */
    var deviceName = "Android TV"

    /** Stable id of this device for the live channel. */
    val deviceId: String get() = prefs.getString("deviceId", null) ?: (devicePrefix + "-" + java.util.UUID.randomUUID().toString().take(8)).also {
        prefs.edit().putString("deviceId", it).apply()
    }

    /** Sync document with just one title (what changed), sent live to the other devices. Built directly: cheap. */
    fun exportFor(titleId: Int): JSONObject {
        val titles = JSONObject(); val progress = JSONObject(); val w = JSONObject(); val b = JSONObject()
        history.firstOrNull { it.title.id == titleId }?.let { e ->
            titles.put(titleId.toString(), e.title.toSync())
            val p = positions[e.episodeId]
            progress.put(e.episodeId.toString(), JSONObject().put("pos", p?.pos ?: e.pos).put("dur", p?.dur ?: e.dur)
                .put("at", maxOf(p?.at ?: 0.0, e.at)).put("title", titleId).put("ep", e.ep).put("epName", e.epName)
                .put("team", e.teamId).put("teamName", e.teamName))
        }
        watched[titleId]?.takeIf { it.isNotEmpty() }?.let { set ->
            w.put(titleId.toString(), JSONObject().put("eps", JSONArray(set.sortedBy { it.toDoubleOrNull() ?: 0.0 }))
                .put("at", history.firstOrNull { it.title.id == titleId }?.at ?: now()))
        }
        bookmarks.values.firstOrNull { it.title.id == titleId }?.let { e ->
            titles.put(titleId.toString(), e.title.toSync())
            b.put(e.title.slug, JSONObject().put("title", titleId).put("status", e.status.raw).put("at", e.at))
        }
        removedBookmarks.forEach { (slug, at) -> if (bookmarks.values.none { it.title.slug == slug } && history.any { it.title.id == titleId && it.title.slug == slug })
            b.put(slug, JSONObject().put("status", JSONObject.NULL).put("at", at)) }
        return JSONObject().put("v", 1).put("device", deviceName).put("updated", 0)
            .put("titles", titles).put("progress", progress).put("watched", w).put("bookmarks", b)
            .put("hidden", JSONObject().apply { hiddenHistory.forEach { (tid, at) -> put(tid.toString(), at) } })
    }

    private fun live(titleId: Int) { runCatching { Remote.publishLive(exportFor(titleId)) } }

    fun updateProgress(title: Title, ep: Episode, team: Team, pos: Double, dur: Double) {
        val t = now()
        positions[ep.id] = Pos(pos, dur, t)
        history.removeAll { it.title.id == title.id }
        history.add(0, WatchEntry(title, ep.id, ep.number, ep.name ?: "", team.id, team.name, pos, dur, t))
        changed()
        live(title.id)
    }

    fun markWatched(title: Title, ep: Episode) {
        val set = watched.getOrPut(title.id) { mutableSetOf() }
        if (set.add(ep.number)) { changed(); live(title.id) }
        if (bookmark(title.slug) == null || bookmark(title.slug) == Bookmark.PLANNED) setBookmark(title, Bookmark.WATCHING)
    }

    /** Episodes 1…[count] marked as watched (list import); true if anything changed. */
    fun markWatchedUpTo(title: Title, count: Int): Boolean {
        if (count <= 0) return false
        val set = watched.getOrPut(title.id) { mutableSetOf() }
        val added = set.addAll((1..count).map { it.toString() })
        if (added) { changed(); live(title.id) }
        return added
    }

    /** Settings and local data for a backup file (everything in the main preferences). */
    // Sign-ins and keys never go into a backup file (anyone could read it); only settings and preferences.
    private val secret = Regex("token|shiki|github|auth|cookie|key|secret|channel|lib\\.|gist|deviceId|^rc\\.|^gh\\.|^library$|home\\.cache", RegexOption.IGNORE_CASE)
    fun exportPrefs(): JSONObject = JSONObject().apply {
        prefs.all.forEach { (k, v) -> if (v != null && !secret.containsMatchIn(k) && v !is Set<*>) put(k, JSONObject().put("t", v.javaClass.simpleName).put("v", v)) }
    }
    fun importPrefs(o: JSONObject) {
        val e = prefs.edit()
        o.keys().forEach { k ->
            if (secret.containsMatchIn(k)) return@forEach
            val x = o.optJSONObject(k) ?: return@forEach
            when (x.optString("t")) {
                "Boolean" -> e.putBoolean(k, x.optBoolean("v")); "Integer" -> e.putInt(k, x.optInt("v")); "Long" -> e.putLong(k, x.optLong("v"))
                "Float" -> e.putFloat(k, x.optDouble("v").toFloat()); "String" -> e.putString(k, x.optString("v"))
            }
        }
        e.apply(); bump()
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
        live(title.id)
    }

    fun setDubs(list: List<String>) { dubs = list; dubsAt = now(); changed() }

    fun useDub(name: String) { if (name !in dubs) setDubs(dubs + name) }

    fun clearHistory() { history.forEach { hiddenHistory[it.title.id] = now() }; history.clear(); changed() }

    /** Remove a title from "Продолжить просмотр" (positions and watched marks stay). */
    fun removeFromHistory(titleId: Int) {
        if (history.removeAll { it.title.id == titleId }) { hiddenHistory[titleId] = now(); changed() }
    }

    /** Local changes not yet pushed to the gist (true at start so the first sync pushes once). */
    @Volatile var dirty = true

    private fun changed() {
        bump()
        dirty = true
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1500)
            // Build on the main thread (where the data changes), write on IO: no concurrent-modification crashes.
            val text = kotlinx.coroutines.withContext(Dispatchers.Main) { exportSync().toString() }
            prefs.edit().putString("library", text).apply()
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
        return JSONObject().put("v", 1).put("device", deviceName).put("updated", 0)
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

        d.optJSONObject("hidden")?.let { h ->
            h.keys().forEach { k ->
                val tid = k.toIntOrNull() ?: return@forEach
                val at = h.optDouble(k, 0.0)
                if (at > (hiddenHistory[tid] ?: 0.0)) {
                    hiddenHistory[tid] = at
                    if (history.removeAll { it.title.id == tid && it.at <= at }) changed = true
                }
            }
        }
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
                    if ((local?.at ?: 0.0) < at && at > (hiddenHistory[t.id] ?: 0.0)) {
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
            val text = exportSync().toString()
            scope.launch { prefs.edit().putString("library", text).apply() }
        }
        return changed
    }
}
