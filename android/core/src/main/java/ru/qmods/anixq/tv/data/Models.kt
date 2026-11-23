package ru.qmods.anixq.tv.data

import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotEmpty() } else null
fun JSONObject.int(k: String): Int? = if (has(k) && !isNull(k)) when (val v = opt(k)) {
    is Number -> v.toInt(); is String -> v.toIntOrNull(); else -> null
} else null
fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) when (val v = opt(k)) {
    is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null
} else null
fun JSONObject.obj(k: String): JSONObject? = optJSONObject(k)
fun JSONObject.arr(k: String): List<JSONObject> {
    val a = optJSONArray(k) ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}
fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }

/** Catalog item. JSON in the sync file uses the iOS field names (camelCase). */
data class Title(
    val id: Int,
    val slug: String,
    val rusName: String? = null,
    val name: String? = null,
    val engName: String? = null,
    val cover: String? = null,
    val coverThumb: String? = null,
    val rating: Double? = null,
    val shikiId: Int? = null,
    val typeLabel: String? = null,
    val statusId: Int? = null,
    val year: Int? = null,
    val age: String? = null,
    val malId: Int? = null,
    val episodeCount: Int? = null,
    val episodesAired: Int? = null,
    val nextEpisodeAt: Double? = null,
) {
    val title: String get() = rusName ?: name ?: engName ?: "Без названия"
    val poster: String? get() = cover ?: coverThumb
    /** Small cover for cards: decodes ~4× faster on weak TV boxes. */
    val thumb: String? get() = coverThumb ?: cover
    val isOngoing: Boolean get() = statusId == 1
    val episodeInfo: String? get() = episodeCount?.let { count ->
        val aired = episodesAired ?: 0
        val next = nextEpisodeAt?.takeIf { it > 0 }?.let { java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date((it * 1000).toLong())) }
        "$aired / $count эп." + (next?.let { " · следующая $it" } ?: "")
    }
    val meta: String get() = listOfNotNull(typeLabel, year?.toString(), rating?.let { "★ %.1f".format(it) }).joinToString(" · ")

    fun toSync(): JSONObject = JSONObject().apply {
        put("id", id); put("slugURL", slug)
        rusName?.let { put("rusName", it) }; name?.let { put("name", it) }; engName?.let { put("engName", it) }
        cover?.let { put("cover", it) }; coverThumb?.let { put("coverThumb", it) }
        rating?.let { put("rating", it) }; shikiId?.let { put("shikiId", it) }
        typeLabel?.let { put("typeLabel", it) }; statusId?.let { put("statusId", it) }
        year?.let { put("year", it) }; age?.let { put("age", it) }
        malId?.let { put("malId", it) }; episodeCount?.let { put("episodeCount", it) }; episodesAired?.let { put("episodesAired", it) }; nextEpisodeAt?.let { put("nextEpisodeAt", it) }
    }

    companion object {
        fun fromApi(x: JSONObject): Title? {
            val id = x.int("id") ?: return null
            val slug = x.str("slug_url") ?: return null
            val year = x.str("releaseDateString")?.split(" ")?.mapNotNull { it.toIntOrNull() }?.firstOrNull { it > 1900 }
            val shiki = x.str("shikimori_href")?.substringBefore('?')?.trimEnd('/')?.substringAfterLast('/')?.let { Regex("^[a-zA-Z]?(\\d+)(?:-|$)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            return Title(
                id = id, slug = slug, rusName = x.str("rus_name"), name = x.str("name"), engName = x.str("eng_name"),
                cover = x.obj("cover")?.str("default"), coverThumb = x.obj("cover")?.str("thumbnail"),
                rating = x.obj("rating")?.dbl("average"), shikiId = shiki,
                typeLabel = x.obj("type")?.str("label"), statusId = x.obj("status")?.int("id"), year = year,
                age = x.obj("ageRestriction")?.str("label"),
            )
        }

        fun fromSync(x: JSONObject): Title? {
            val id = x.int("id") ?: return null
            val slug = x.str("slugURL") ?: return null
            return Title(id, slug, x.str("rusName"), x.str("name"), x.str("engName"), x.str("cover"), x.str("coverThumb"),
                x.dbl("rating"), x.int("shikiId"), x.str("typeLabel"), x.int("statusId"), x.int("year"), x.str("age"), x.int("malId"), x.int("episodeCount"), x.int("episodesAired"), x.dbl("nextEpisodeAt"))
        }
    }
}

data class Detail(val title: Title, val summary: String?, val background: String?, val genres: List<String>, val sourceUrl: String? = null)

data class Episode(val id: Int, val number: String, val name: String?, val index: Int) {
    val displayTitle: String get() = name?.takeIf { it.isNotBlank() && it != number } ?: "Серия $number"
    val numberValue: Double get() = number.toDoubleOrNull() ?: (index + 1).toDouble()
}

data class Team(val id: Int, val name: String, val isSubtitles: Boolean)

data class Segment(val kind: String, val from: Double, val to: Double, val source: Double = 0.0) {
    val isOpening get() = kind.lowercase().let { it.contains("open") || it.contains("опен") }
    val isEnding get() = kind.lowercase().let { it.contains("end") || it.contains("энд") }
}

data class Subtitle(val url: String, val format: String, val label: String)

data class Player(
    val team: Team,
    val player: String,
    val kodikSrc: String?,
    val hrefs: Map<String, String>,
    val segments: List<Segment>,
    val playerId: Int?,
    val serverTimecode: Double?,
    val subtitles: List<Subtitle> = emptyList(),
    /** Where the video comes from: AnimeLib's servers, or AniLibria's own (see Libria). */
    val source: String = "animelib",
    val headers: Map<String, String> = if (source == "animelib") mapOf("Referer" to "${Api.SITE}/", "Origin" to Api.SITE) else emptyMap(),
    val resolverUrl: String? = null,
    val balancer: String? = null,
    /** When this team's video of the episode appeared on AnimeLib (ms), for "how fast does this dub come out". */
    val addedAt: Long? = null,
    val mimeTypes: Map<String, String> = emptyMap(),
) {
    val isYummy get() = source == "yummy"
    val canResolve get() = resolverUrl != null
    val isExternalEmbed get() = isYummy || source == "animego" || source == "shiki"
    val sourceLabel get() = playbackSourceLabel(source)
    val choiceLabel get() = listOfNotNull(sourceLabel, balancer, team.name).joinToString(" · ")
    val isLibria get() = source == Libria.SOURCE
    val isNative get() = player.equals("animelib", true) && hrefs.isNotEmpty()
    val isKodik get() = player.equals("kodik", true) && kodikSrc != null
}

fun playbackSourceLabel(source: String) = when (source) {
    "yummy" -> "YummyAnime"
    "animego" -> "AnimeGO"
    "shiki" -> "Шикимори"
    "anilibria" -> "AniLibria"
    else -> "AnimeLib"
}

/** A dub / subtitle team offered for a title, with the players it has. */
data class TeamOption(val team: Team, val native: Boolean, val kodik: Boolean, val sourceLabel: String = "AnimeLib", val balancer: String? = null, val source: String = "animelib") {
    val balancerKey get() = balancer ?: if (source == "animelib" && !native && kodik) "kodik" else "native"
    val balancerLabel get() = balancer?.let(PlaybackLabels::balancer) ?: if (balancerKey == "kodik") "Kodik" else if (source == "anilibria") "Свой сервер" else "Основной"
    val dubLabel get() = PlaybackLabels.dub(team.name)
    val label get() = dubLabel
}

enum class Bookmark(val raw: Int, val title: String) {
    WATCHING(21, "Смотрю"), PLANNED(22, "Запланировано"), DROPPED(23, "Брошено"), COMPLETED(24, "Просмотрено"),
    FAVORITE(25, "Любимые"), REWATCHING(26, "Пересматриваю"), ON_HOLD(27, "Отложено");

    companion object { fun of(raw: Int?) = entries.firstOrNull { it.raw == raw } }
}

enum class CatalogSort(val key: String, val title: String) {
    POPULAR("views", "Популярные"), RATING("rating_score", "По рейтингу"),
    UPDATED("last_episode_at", "Новые серии"), NEWEST("created_at", "Новинки")
}

data class CatalogQuery(
    val sort: CatalogSort = CatalogSort.POPULAR,
    val status: Int? = null,      // 1 ongoing, 2 finished, 3 announce
    val type: Int? = null,        // 16 TV, 14 movie, 15 OVA ... (AnimeLib ids)
    val genre: Int? = null,
    val text: String = "",
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val episodesMin: Int? = null,
    val episodesMax: Int? = null,
    val source: String = "animelib",
) {
    val extraFilters get() = listOfNotNull(genre, yearFrom ?: yearTo, episodesMin ?: episodesMax).size
}
