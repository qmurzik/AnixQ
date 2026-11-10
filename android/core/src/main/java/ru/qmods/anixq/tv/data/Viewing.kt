package ru.qmods.anixq.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/** "Без спойлеров": names of episodes not watched yet are hidden. */
object Spoilers {
    fun title(ep: Episode, watched: Boolean): String = if (Store.noSpoilers && !watched) "Серия ${ep.number}" else ep.displayTitle
    fun title(titleId: Int, ep: Episode): String = title(ep, Store.isWatched(titleId, ep.number) || Store.progress(ep.id) > 0.92)
}

object Marathon {
    /** Episodes in a row before a break is suggested. */
    const val BREAK_AFTER = 3
}

/** Back after a break: where you stopped, and the playback offset to bring the context back. */
object Recap {
    const val DAYS = 3
    const val REWIND = 30.0

    fun daysAway(titleId: Int): Int? {
        val e = Store.lastEntry(titleId) ?: return null
        if (e.progress >= 0.92) return null
        val now = System.currentTimeMillis() / 1000.0 + Sync.serverClockOffset
        val days = ((now - e.at) / 86_400).toInt()
        return days.takeIf { it >= DAYS }
    }

    /** Start position adjusted for a long break, plus the message to show (null when unchanged). */
    fun adjust(titleId: Int, episodeId: Int, startAt: Double): Pair<Double, String?> {
        val e = Store.lastEntry(titleId) ?: return startAt to null
        val days = daysAway(titleId) ?: return startAt to null
        if (e.episodeId != episodeId || startAt < 60) return startAt to null
        return (startAt - REWIND) to "Не смотрели $days дн. — продолжаем на 30 с раньше, чтобы вспомнить"
    }
}

/** Watch order of a franchise from Shikimori, with the matching AnimeLib titles. */
object Franchise {
    data class Node(val shikiId: Int, val name: String, val kind: String?, val year: Int?, val date: Long, val weight: Int) {
        val isMain get() = weight >= 3 || kind == "TV Сериал" || kind == "Фильм"
    }

    private val nodesCache = HashMap<Int, List<Node>>()
    private val titles = HashMap<Int, Title?>()

    suspend fun nodes(shikiId: Int): List<Node> = nodesCache[shikiId] ?: withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("https://shikimori.io/api/animes/$shikiId/franchise").header("User-Agent", "AnixQ").build()
            Api.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use emptyList()
                JSONObject(r.body!!.string()).arr("nodes").mapNotNull { n ->
                    val id = n.int("id") ?: return@mapNotNull null
                    val kind = n.str("kind")
                    // Promo clips and music videos aren't part of a watch order.
                    if (kind == "Проморолик" || kind == "Клип" || kind == "Реклама") return@mapNotNull null
                    Node(id, n.str("name") ?: "", kind, n.int("year"), n.optLong("date"), n.int("weight") ?: 1)
                }.sortedBy { it.date }
            }
        }.getOrDefault(emptyList()).let { if (it.size > 1) it else emptyList() }
    }.also { nodesCache[shikiId] = it }

    fun cached(shikiId: Int): Title? = titles[shikiId]

    /** The AnimeLib title for a Shikimori entry (search by name, matched by the Shikimori id). */
    suspend fun title(node: Node): Title? = titleFor(node.shikiId, listOf(node.name))

    suspend fun titleFor(shikiId: Int, names: List<String>): Title? {
        if (titles.containsKey(shikiId)) return titles[shikiId]
        // Already in the library? No search needed.
        (Store.history.map { it.title } + Store.bookmarks.values.map { it.title }).firstOrNull { it.shikiId == shikiId }?.let { titles[shikiId] = it; return it }
        var t: Title? = null
        for (n in names.filter { it.isNotBlank() }) {
            t = runCatching { Api.search(n).firstOrNull { it.shikiId == shikiId } }.getOrNull()
            if (t != null) break
        }
        titles[shikiId] = t
        return t
    }
}

/** Loads a title page and its episode list ahead (cursor resting on it, finger touching it): opens instantly. */
object Prefetch {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val recent = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun title(slug: String) {
        val now = System.currentTimeMillis()
        if ((recent[slug] ?: 0) > now - 5 * 60_000) return
        recent[slug] = now
        scope.launch {
            kotlinx.coroutines.coroutineScope {
                launch { runCatching { Api.detail(slug) } }
                launch { runCatching { Api.episodes(slug) } }
            }
        }
    }
}


/** Airing schedule (Shikimori calendar), the same source as the iPhone's "Расписание". */
object Schedule {
    data class Item(val shikiId: Int, val name: String, val russian: String?, val episode: Int, val at: Long,
                    val image: String, val kind: String?, val score: Double?, val total: Int?, val aired: Int?) {
        val title get() = russian?.takeIf { it.isNotBlank() } ?: name
        /** Placeholder Title until the AnimeLib one is found (negative id = not resolved). */
        fun asTitle() = Title(id = -shikiId, slug = "", rusName = title, cover = image, coverThumb = image, shikiId = shikiId, rating = score)
    }

    private var cache: Pair<Long, List<Item>>? = null

    suspend fun load(): List<Item> {
        cache?.let { if (System.currentTimeMillis() - it.first < 30 * 60_000) return it.second }
        val list = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("https://shikimori.io/api/calendar").header("User-Agent", "AnixQ").build()
                Api.http.newCall(req).execute().use { r ->
                    val a = org.json.JSONArray(r.body!!.string())
                    (0 until a.length()).mapNotNull { i ->
                        val x = a.getJSONObject(i); val an = x.optJSONObject("anime") ?: return@mapNotNull null
                        val id = an.int("id") ?: return@mapNotNull null
                        val at = parseTime(x.str("next_episode_at") ?: return@mapNotNull null) ?: return@mapNotNull null
                        Item(id, an.str("name") ?: "", an.str("russian"), x.int("next_episode") ?: 0, at,
                            "https://shikimori.io/system/animes/preview/$id.jpg", an.str("kind"), an.dbl("score"), an.int("episodes"), an.int("episodes_aired"))
                    }.sortedBy { it.at }
                }
            }.getOrDefault(emptyList())
        }
        if (list.isNotEmpty()) cache = System.currentTimeMillis() to list
        return list
    }

    /** "2026-11-10T14:30:00.000+03:00" (no java.time on Android 6). */
    private fun parseTime(s: String): Long? = runCatching {
        val fixed = s.replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US).parse(fixed)?.time
    }.getOrNull()

    /** Titles you follow (watching / planned / history) are marked in the schedule. */
    fun isMine(shikiId: Int): Boolean =
        Store.bookmarks.values.any { it.title.shikiId == shikiId && it.status != Bookmark.DROPPED } || Store.history.any { it.title.shikiId == shikiId }
}
