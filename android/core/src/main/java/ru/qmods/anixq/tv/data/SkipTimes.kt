package ru.qmods.anixq.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray

/**
 * Opening / ending times from every provider we know, for any source being played:
 * the player's own marks, AniLibria's (same TV broadcast), AniSkip (community, often recorded on longer
 * Crunchyroll cuts). Times from a cut of another length are moved, not thrown away: the opening keeps its
 * place from the start, the ending its place from the end.
 */
object SkipTimes {
    /** How far a cut may differ from ours and still be used (beyond that it's probably another version). */
    private const val TOLERANCE = 180.0

    /** [s] (recorded on a cut [s.source] seconds long) moved onto our video of [duration] seconds; null if it doesn't fit. */
    fun fit(s: Segment, duration: Double): Segment? {
        if (s.to <= s.from) return null
        val length = s.to - s.from
        if (s.isOpening && length !in 20.0..200.0) return null
        if (s.source <= 0 || duration <= 0) return s
        val diff = duration - s.source
        if (kotlin.math.abs(diff) > TOLERANCE) return null
        val moved = when {
            kotlin.math.abs(diff) < 3 -> s
            s.isOpening -> s                                   // from the start
            s.isEnding -> Segment(s.kind, s.from + diff, s.to + diff, duration)   // from the end
            else -> s
        }
        return moved.takeIf { it.from >= 0 && it.to <= duration + 1 }
    }

    private val shikiIds = HashMap<String, Int?>()

    /** Shikimori (= MyAnimeList) id for titles that came without one, found by name. */
    private suspend fun shikiId(title: Title): Int? {
        title.malId?.let { return it }; title.shikiId?.let { return it }
        val name = title.name ?: title.engName ?: title.rusName ?: return null
        shikiIds[name]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                Api.http.newCall(Request.Builder().url("https://shikimori.io/api/animes?limit=1&search=" + java.net.URLEncoder.encode(name, "UTF-8"))
                    .header("User-Agent", "AnixQ").build()).execute().use { r -> JSONArray(r.body!!.string()).optJSONObject(0)?.optInt("id")?.takeIf { it > 0 } }
            }.getOrNull()
        }.also { shikiIds[name] = it }
    }

    /** Opening and ending for this episode, best provider first. [own] = the playing source's marks. */
    suspend fun find(title: Title, episode: String, duration: Double, own: List<Segment>): List<Segment> = coroutineScope {
        val ownOp = own.firstOrNull { it.isOpening }; val ownEd = own.firstOrNull { it.isEnding }
        if (ownOp != null && ownEd != null) return@coroutineScope own
        val libria = async { runCatching { Libria.marks(title, episode) }.getOrNull() }
        val community = async { shikiId(title)?.let { runCatching { AniSkip.segments(it, episode, duration) }.getOrNull() }.orEmpty() }
        val fromLibria = libria.await()?.first.orEmpty().mapNotNull { fit(it, duration) }
        val fromCommunity = community.await()
        fun pick(test: (Segment) -> Boolean) = (own + fromLibria + fromCommunity).firstOrNull(test)
        listOfNotNull(ownOp ?: pick { it.isOpening }, ownEd ?: pick { it.isEnding })
    }
}
