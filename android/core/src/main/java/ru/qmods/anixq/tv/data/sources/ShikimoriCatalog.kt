package ru.qmods.anixq.tv.data.sources

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.data.sources.extractors.CvhExtractor

class ShikimoriCatalog(
    private val http: SourceHttp,
    private val cvh: CvhExtractor,
) : AnimeCatalog, EpisodeSource {
    private val cacheLock = Mutex()
    private val playlists = LinkedHashMap<Int, Pair<Long, JSONObject>>()

    private suspend fun read(path: String, params: Map<String, String> = emptyMap()): String {
        val url = "$SITE/api/$path".toHttpUrl().newBuilder()
        params.forEach { (name, value) -> url.addQueryParameter(name, value) }
        return http.request(url.build().toString(), mapOf("User-Agent" to "AnixQ", "Accept" to "application/json")).text
    }

    override suspend fun catalog(query: CatalogQuery, page: Int): Pair<List<Title>, Boolean> {
        val data = JSONArray(read("animes", parameters(query, page)))
        val titles = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.let(::parseTitle) }.distinctBy { it.id }
        return titles to (data.length() == PAGE_SIZE)
    }

    override suspend fun detail(id: Int): Detail {
        val json = JSONObject(read("animes/$id"))
        val title = parseTitle(json)?.takeIf { it.shikiId == id } ?: error("Шикимори: тайтл не найден")
        val genres = json.arr("genres").ifEmpty { json.arr("genres_v2") }.mapNotNull { it.str("russian") ?: it.str("name") }
        val url = json.str("url")?.takeIf { it.startsWith("/animes/") }?.let { "$SITE$it" }
        return Detail(title, json.str("description")?.replace(Regex("""\[/?[a-z_]+(?:=[^]]*)?]"""), ""), null, genres, url)
    }

    override suspend fun genres(): List<Pair<Int, String>> {
        val data = JSONArray(read("genres"))
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }.mapNotNull { row ->
            val id = row.int("id") ?: return@mapNotNull null
            val name = row.str("russian") ?: row.str("name") ?: return@mapNotNull null
            (id to name).takeIf { row.str("entry_type").equals("Anime", true) }
        }
    }

    private suspend fun playlist(id: Int): JSONObject {
        val now = System.currentTimeMillis()
        cacheLock.withLock { playlists[id]?.takeIf { now - it.first < 120_000 }?.let { return it.second } }
        val fresh = cvh.shikimoriPlaylist(id)
        cacheLock.withLock {
            playlists[id] = System.currentTimeMillis() to fresh
            while (playlists.size > 60) playlists.remove(playlists.keys.first())
        }
        return fresh
    }

    override suspend fun episodes(id: Int): List<Episode> = parseEpisodes(id, playlist(id))

    override suspend fun players(id: Int, number: String): List<Player> = parsePlayers(playlist(id), number)

    companion object {
        const val SITE = "https://shikimori.io"
        const val SOURCE = "shiki"
        private const val PAGE_SIZE = 30
        private val kinds = mapOf(16 to "tv", 14 to "movie", 17 to "movie", 15 to "ova", 20 to "ova", 21 to "ona", 19 to "special")
        private val statuses = mapOf(1 to "ongoing", 2 to "released", 3 to "anons")
        private val sorts = mapOf(CatalogSort.POPULAR to "popularity", CatalogSort.RATING to "ranked", CatalogSort.UPDATED to "updated_at_desc", CatalogSort.NEWEST to "aired_on")

        internal fun parameters(query: CatalogQuery, page: Int): Map<String, String> = buildMap {
            put("limit", PAGE_SIZE.toString())
            put("page", page.toString())
            put("censored", "true")
            if (query.text.isNotBlank()) put("search", query.text.trim())
            else put("order", sorts.getValue(query.sort))
            kinds[query.type]?.let { put("kind", it) }
            statuses[query.status]?.let { put("status", it) }
            query.genre?.let { put("genre", it.toString()) }
            val from = query.yearFrom
            val to = query.yearTo
            if (from != null || to != null) {
                val start = from ?: 1900
                val end = (to ?: 2100).coerceAtLeast(start)
                put("season", if (start == end) start.toString() else "${start}_$end")
            }
        }

        internal fun parseTitle(json: JSONObject): Title? {
            val id = json.int("id")?.takeIf(SourceIds::validShikiId) ?: return null
            val images = json.obj("image")
            fun cover(path: String?): String? = when {
                path.isNullOrBlank() || path.contains("missing_") -> null
                path.startsWith("https://") -> path
                path.startsWith("/") && !path.startsWith("//") -> "$SITE$path"
                else -> null
            }
            val kind = json.str("kind")?.let {
                when (it) { "movie" -> "Фильм"; "tv" -> "TV"; "special", "tv_special" -> "Спешл"; else -> it.uppercase() }
            }
            val episodes = json.int("episodes")?.takeIf { it > 0 }
            val status = statuses.entries.firstOrNull { it.value == json.str("status") }?.key
            return Title(
                id = SourceIds.shikiTitle(id), slug = "shiki:$id", rusName = json.str("russian"), name = json.str("name"),
                cover = cover(images?.str("original")), coverThumb = cover(images?.str("preview")),
                rating = json.dbl("score"), shikiId = id, malId = id, typeLabel = kind, statusId = status,
                year = json.str("aired_on")?.take(4)?.toIntOrNull(), age = json.str("rating")?.uppercase(),
                episodeCount = episodes, episodesAired = json.int("episodes_aired")?.takeIf { it > 0 } ?: episodes.takeIf { status == 2 },
            )
        }

        private fun episodeNumber(playlist: JSONObject, row: JSONObject): String? =
            if (!playlist.optBoolean("isSerial", true)) "1" else row.optString("episode").toDoubleOrNull()?.takeIf { it in 1.0..1023.0 && it % 1.0 == 0.0 }?.toInt()?.toString()

        internal fun parseEpisodes(id: Int, playlist: JSONObject): List<Episode> =
            playlist.arr("items").asSequence().filter { it.str("vkId") != null }
                .mapNotNull { episodeNumber(playlist, it)?.toInt() }.distinct().sorted()
                .mapIndexed { index, number -> Episode(SourceIds.shikiEpisode(id, number), number.toString(), null, index) }.toList()

        internal fun parsePlayers(playlist: JSONObject, number: String): List<Player> =
            playlist.arr("items").mapNotNull { row ->
                if (!YummyAnime.sameNumber(episodeNumber(playlist, row) ?: return@mapNotNull null, number)) return@mapNotNull null
                val video = row.str("vkId") ?: return@mapNotNull null
                val studio = row.str("voiceStudio")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val type = row.str("voiceType")?.trim().orEmpty()
                val subs = type.contains("субтитр", true) || type.contains("subtitle", true) || type.equals("subs", true)
                val name = listOf(type, studio).filter { it.isNotBlank() }.joinToString(" ")
                val key = name.lowercase(java.util.Locale.ROOT)
                Player(Team(SourceIds.shikiTeam(key), name, subs), SOURCE, null, emptyMap(), emptyList(), null, null,
                    source = SOURCE, resolverUrl = CvhExtractor.shikimoriVideoUrl(video), balancer = "CVH")
            }.groupBy { it.team.id }.values.mapNotNull { options ->
                options.distinctBy { it.team.name to it.resolverUrl }.singleOrNull()
            }
    }
}
