package ru.qmods.anixq.tv.data.sources

import ru.qmods.anixq.tv.data.*

/** The positive legacy range remains AnimeLib-only; external IDs cannot reach its write API. */
object SourceIds {
    fun validYummyId(id: Int) = id in 1..999_999_999
    fun yummy(id: Int): Int { require(validYummyId(id)); return -id }
    fun yummyRemote(id: Int): Int { require(id in -999_999_999..-1); return -id }
    fun isYummy(slug: String) = slug.startsWith("yummy:")
    fun isShiki(slug: String) = slug.startsWith("shiki:")
    fun validShikiId(id: Int) = id in 1..1_000_000
    fun shikiTitle(id: Int): Int { require(validShikiId(id)); return -1_000_000_000 - id }
    fun shikiEpisode(id: Int, number: Int): Int {
        require(validShikiId(id) && number in 1..1023)
        return -1_002_000_000 - id * 1024 - number
    }
    fun shikiTeam(key: String): Int = -2_100_000_000 - (key.hashCode() and 0x01ffffff)
    fun isShikiTeam(id: Int) = id in -2_133_554_431..-2_100_000_000
    fun shikiId(title: Title): Int? = if (isShiki(title.slug)) {
        title.slug.substringAfter(':').toIntOrNull()?.takeIf(::validShikiId)
    } else (title.shikiId ?: title.malId)?.takeIf(::validShikiId)
    fun catalogSource(slug: String): String = when {
        isYummy(slug) -> "yummy"
        isShiki(slug) -> ShikimoriCatalog.SOURCE
        else -> "animelib"
    }
    fun localTitleId(slug: String): Int = when {
        isYummy(slug) -> slug.substringAfter(':').toIntOrNull()?.takeIf(::validYummyId)?.let(::yummy) ?: 0
        isShiki(slug) -> slug.substringAfter(':').toIntOrNull()?.takeIf(::validShikiId)?.let(::shikiTitle) ?: 0
        else -> slug.substringBefore("--").toIntOrNull() ?: 0
    }
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

data class ResolvedStream(val qualities: Map<String, String>, val headers: Map<String, String>, val mimeTypes: Map<String, String> = emptyMap()) {
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
