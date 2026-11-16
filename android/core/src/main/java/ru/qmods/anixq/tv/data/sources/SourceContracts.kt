package ru.qmods.anixq.tv.data.sources

import ru.qmods.anixq.tv.data.*

/** The positive legacy range remains AnimeLib-only; external IDs cannot reach its write API. */
object SourceIds {
    fun yummy(id: Int): Int { require(id > 0); return -id }
    fun yummyRemote(id: Int): Int { require(id < 0 && id != Int.MIN_VALUE); return -id }
    fun isYummy(slug: String) = slug.startsWith("yummy:")
}
interface AnimeCatalog {
    suspend fun catalog(query: CatalogQuery, page: Int): Pair<List<Title>, Boolean>
    suspend fun detail(id: Int): Detail
    suspend fun genres(): List<Pair<Int, String>>
}
interface EpisodeSource {
    suspend fun episodes(id: Int): List<Episode>
    suspend fun players(id: Int, number: String): List<Player>
}

data class ResolvedStream(val qualities: Map<String, String>, val headers: Map<String, String>) {
    init { require(qualities.isNotEmpty()) }
}
interface StreamExtractor {
    val name: String
    fun supports(url: String): Boolean
    suspend fun resolve(url: String): ResolvedStream
}

fun fullUrl(url: String): String = (if (url.startsWith("//")) "https:$url" else url).replace(" ", "%20")
fun qualityHeight(label: String): Int = when (label.lowercase()) {
    "auto", "авто" -> Int.MAX_VALUE
    "2k" -> 1440
    "4k" -> 2160
    else -> label.removeSuffix("p").toIntOrNull() ?: 0
}
