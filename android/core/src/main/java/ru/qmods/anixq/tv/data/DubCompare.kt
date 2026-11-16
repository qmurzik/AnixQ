package ru.qmods.anixq.tv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * "Какую озвучку выбрать": for each team — up to which episode it has, whether there's an ad-free version,
 * subtitles or voice, and how long after an episode first appears its version usually follows.
 * Looks at the last few episodes only (one request each), so it's quick.
 */
object DubCompare {
    data class Row(
        val team: Team, val latest: String?, val latestIndex: Int, val total: Int, val adFree: Boolean, val kodik: Boolean,
        /** Median delay after the first version of an episode, in hours (null = not enough data). */
        val delayHours: Double?, val checked: Int,
    ) {
        val delayLabel: String? get() = delayHours?.let { h ->
            when {
                h < 2 -> "одной из первых"
                h < 24 -> "через ~${h.toInt()} ч"
                else -> "через ~${"%.1f".format(h / 24).replace(".0", "").replace('.', ',')} дн."
            }
        }
    }

    private val cache = HashMap<Int, List<Row>>()

    suspend fun load(title: Title, episodes: List<Episode>, sample: Int = 6): List<Row> {
        cache[title.id]?.let { return it }
        if (episodes.isEmpty()) return emptyList()
        val picked = episodes.indices.toList().takeLast(sample)
        val perEpisode = coroutineScope {
            picked.map { i -> async { i to runCatching { Api.players(episodes[i].id) }.getOrDefault(emptyList()) } }.awaitAll()
        }
        val byTeam = LinkedHashMap<Int, MutableList<Pair<Int, Player>>>()
        perEpisode.forEach { (i, ps) -> ps.forEach { p -> byTeam.getOrPut(p.team.id) { mutableListOf() } += i to p } }
        val firstSeen = perEpisode.associate { (i, ps) -> i to ps.mapNotNull { it.addedAt }.minOrNull() }
        val rows = byTeam.values.map { list ->
            val team = list.first().second.team
            val last = list.maxOf { it.first }
            val delays = list.mapNotNull { (i, p) -> val a = p.addedAt; val f = firstSeen[i]; if (a != null && f != null) (a - f) / 3_600_000.0 else null }.sorted()
            Row(team, episodes[last].number, last, episodes.size, list.any { it.second.isNative }, list.any { it.second.isKodik },
                delays.takeIf { it.size >= 2 }?.let { it[it.size / 2] }, list.map { it.first }.distinct().size)
        }.sortedWith(compareByDescending<Row> { it.latestIndex }.thenByDescending { it.adFree }.thenBy { it.delayHours ?: 9e9 })
        cache[title.id] = rows
        return rows
    }
}
