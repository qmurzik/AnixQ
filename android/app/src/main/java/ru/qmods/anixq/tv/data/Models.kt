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
) {
    val title: String get() = rusName ?: name ?: engName ?: "Без названия"
    val poster: String? get() = cover ?: coverThumb
    /** Small cover for cards: decodes ~4× faster on weak TV boxes. */
    val thumb: String? get() = coverThumb ?: cover
    val isOngoing: Boolean get() = statusId == 1
    val meta: String get() = listOfNotNull(typeLabel, year?.toString(), rating?.let { "★ %.1f".format(it) }).joinToString(" · ")

    fun toSync(): JSONObject = JSONObject().apply {
        put("id", id); put("slugURL", slug)
        rusName?.let { put("rusName", it) }; name?.let { put("name", it) }; engName?.let { put("engName", it) }
        cover?.let { put("cover", it) }; coverThumb?.let { put("coverThumb", it) }
        rating?.let { put("rating", it) }; shikiId?.let { put("shikiId", it) }
        typeLabel?.let { put("typeLabel", it) }; statusId?.let { put("statusId", it) }
        year?.let { put("year", it) }; age?.let { put("age", it) }
    }

    companion object {
        fun fromApi(x: JSONObject): Title? {
            val id = x.int("id") ?: return null
            val slug = x.str("slug_url") ?: return null
            val year = x.str("releaseDateString")?.split(" ")?.mapNotNull { it.toIntOrNull() }?.firstOrNull { it > 1900 }
            val shiki = x.str("shikimori_href")?.split("/")?.lastOrNull()?.takeWhile { it.isDigit() }?.toIntOrNull()
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
                x.dbl("rating"), x.int("shikiId"), x.str("typeLabel"), x.int("statusId"), x.int("year"), x.str("age"))
        }
    }
}

data class Detail(val title: Title, val summary: String?, val background: String?, val genres: List<String>)

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
) {
    val isNative get() = player.equals("animelib", true) && hrefs.isNotEmpty()
    val isKodik get() = player.equals("kodik", true) && kodikSrc != null
}

/** A dub / subtitle team offered for a title, with the players it has. */
data class TeamOption(val team: Team, val native: Boolean, val kodik: Boolean) {
    val label get() = team.name + if (native) "" else " · Kodik"
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
)
