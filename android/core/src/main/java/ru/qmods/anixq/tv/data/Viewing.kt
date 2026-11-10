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

/**
 * Search that forgives: wrong keyboard layout ("vfubxtcrfz" → "магическая"), Latin ↔ Cyrillic
 * ("naruto" → "наруто"), a typo at the end ("narutp" → "narut"), and Shikimori's own search.
 * Returns the titles and, when a variant was used, what was actually searched for.
 */
object SmartSearch {
    private const val EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`"
    private const val RU = "йцукенгшщзхъфывапролджэячсмитьбюё"

    fun swapLayout(s: String): String = s.map { ch ->
        val lower = ch.lowercaseChar()
        val i = EN.indexOf(lower); val j = RU.indexOf(lower)
        when { i >= 0 -> RU[i]; j >= 0 -> EN[j]; else -> ch }
    }.joinToString("")

    private val latToCyr = listOf("shch" to "щ", "sch" to "щ", "zh" to "ж", "kh" to "х", "ts" to "ц", "ch" to "ч", "sh" to "ш", "yu" to "ю", "ya" to "я", "yo" to "ё",
        "a" to "а", "b" to "б", "v" to "в", "g" to "г", "d" to "д", "e" to "е", "z" to "з", "i" to "и", "y" to "й", "k" to "к", "l" to "л", "m" to "м",
        "n" to "н", "o" to "о", "p" to "п", "r" to "р", "s" to "с", "t" to "т", "u" to "у", "f" to "ф", "h" to "х", "c" to "к", "w" to "в", "x" to "кс", "j" to "дж", "q" to "к")
    private val cyrToLat = mapOf('а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y",
        'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts",
        'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya")

    fun translit(s: String): String {
        val low = s.lowercase()
        return if (low.any { it in 'а'..'я' || it == 'ё' }) low.map { cyrToLat[it] ?: it.toString() }.joinToString("")
        else { var out = low; latToCyr.forEach { (a, b) -> out = out.replace(a, b) }; out }
    }

    suspend fun search(text: String): Pair<List<Title>, String?> {
        val q = text.trim()
        val first = runCatching { Api.search(q) }.getOrDefault(emptyList())
        if (first.isNotEmpty() || q.length < 3) return first to null
        val variants = LinkedHashSet<String>()
        variants += swapLayout(q)
        variants += translit(q)
        if (q.length >= 5) { variants += q.dropLast(1); variants += translit(q.dropLast(1)) }
        if (q.length >= 7) variants += q.dropLast(2)
        for (v in variants.filter { it.isNotBlank() && it != q }) {
            val r = runCatching { Api.search(v) }.getOrDefault(emptyList())
            if (r.isNotEmpty()) return r to v
        }
        // Shikimori's search, then the matching AnimeLib titles.
        val shiki = withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://shikimori.io/api/animes?limit=6&search=" + java.net.URLEncoder.encode(q, "UTF-8")
                Api.http.newCall(Request.Builder().url(url).header("User-Agent", "AnixQ").build()).execute().use { r ->
                    val a = org.json.JSONArray(r.body!!.string())
                    (0 until a.length()).map { a.getJSONObject(it) }
                }
            }.getOrDefault(emptyList())
        }
        val found = shiki.mapNotNull { x -> Franchise.titleFor(x.optInt("id"), listOfNotNull(x.str("russian"), x.str("name"))) }.distinctBy { it.id }
        return found to found.firstOrNull()?.title
    }
}
