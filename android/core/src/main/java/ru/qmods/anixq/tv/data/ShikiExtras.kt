package ru.qmods.anixq.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Shikimori's public pages (no sign-in): trailers, characters, people, friends, seasons — same as the iPhone app. */
object ShikiExtras {
    const val HOST = "https://shikimori.io"

    data class Video(val id: Int, val name: String, val kind: String, val url: String, val image: String?) {
        val kindLabel get() = when (kind) { "pv" -> "Трейлер"; "op" -> "Опенинг"; "ed" -> "Эндинг"; "cm" -> "Реклама"; "op_ed_clip" -> "Клип"; else -> "Видео" }
    }
    data class Person(val id: Int, val name: String, val image: String?, val role: String, val isMain: Boolean, val isCharacter: Boolean)
    data class Work(val shikiId: Int, val name: String, val russian: String, val year: Int?, val role: String) {
        val title get() = russian.ifBlank { name }
    }
    data class PersonPage(val name: String, val altName: String?, val image: String?, val description: String?,
                          val related: List<Person>, val works: List<Work>)
    data class Info(val score: Double?, val episodes: Int?, val episodesAired: Int?, val nextEpisodeAt: Long?, val status: String?)
    data class Friend(val id: Int, val nickname: String, val avatar: String?)
    data class HistoryEntry(val id: Int, val date: Long, val text: String, val shikiId: Int?, val name: String, val russian: String?,
                            val image: String?, val user: Friend?) {
        val title get() = russian?.takeIf { it.isNotBlank() } ?: name
    }
    data class SeasonAnime(val id: Int, val name: String, val russian: String?, val kind: String, val score: Double?, val poster: String?,
                           val studios: List<String>, val genres: List<String>, val episodes: Int, val episodesAired: Int,
                           val nextEpisodeAt: Long?, val airedOn: String?) {
        val title get() = russian?.takeIf { it.isNotBlank() } ?: name
    }

    fun absolute(path: String?): String? = path?.takeIf { it.isNotBlank() && !it.contains("missing") }?.let { if (it.startsWith("http")) it else HOST + it }

    private suspend fun get(path: String): Any? = withContext(Dispatchers.IO) {
        runCatching {
            Api.http.newCall(Request.Builder().url(HOST + path).header("User-Agent", "AnixQ").header("Accept", "application/json").build())
                .execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val t = r.body?.string().orEmpty().trim()
                    if (t.startsWith("[")) JSONArray(t) else if (t.startsWith("{")) JSONObject(t) else null
                }
        }.getOrNull()
    }

    private suspend fun graphql(query: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            Api.http.newCall(Request.Builder().url("$HOST/api/graphql").header("User-Agent", "AnixQ")
                .post(JSONObject().put("query", query).toString().toRequestBody("application/json".toMediaType())).build())
                .execute().use { r -> if (r.isSuccessful) JSONObject(r.body!!.string()).optJSONObject("data") else null }
        }.getOrNull()
    }

    private fun list(a: Any?): List<JSONObject> = (a as? JSONArray)?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } } ?: emptyList()

    fun parseDate(s: String?): Long? = s?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    /** HTML and Shikimori BBCode → plain text. */
    fun plain(s: String): String = s
        .replace(Regex("\\[(character|anime|person|manga|ranobe)=\\d+\\]"), "")
        .replace(Regex("\\[/?[a-z_]+(=[^\\]]*)?\\]"), "")
        .replace("</p><p>", "\n").replace(Regex("<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .trim()

    suspend fun info(id: Int): Info? = (get("/api/animes/$id") as? JSONObject)?.let { x ->
        Info(x.dbl("score")?.takeIf { it > 0 }, x.int("episodes"), x.int("episodes_aired"), parseDate(x.str("next_episode_at")), x.str("status"))
    }

    suspend fun videos(id: Int): List<Video> = list(get("/api/animes/$id/videos")).mapNotNull { v ->
        val vid = v.int("id") ?: return@mapNotNull null
        val url = v.str("url") ?: return@mapNotNull null
        Video(vid, v.str("name") ?: "", v.str("kind") ?: "other", url, v.str("image_url")?.replace("http://", "https://"))
    }.filter { it.kind != "episode_preview" }

    /** Characters (main first) and key staff. */
    suspend fun roles(id: Int): Pair<List<Person>, List<Person>> {
        val characters = ArrayList<Person>(); val staff = ArrayList<Person>()
        val key = setOf("Director", "Original Creator", "Series Composition", "Music", "Character Design", "Chief Animation Director")
        for (r in list(get("/api/animes/$id/roles"))) {
            val rolesEn = r.optJSONArray("roles")?.strings() ?: emptyList()
            val rolesRu = r.optJSONArray("roles_russian")?.strings() ?: rolesEn
            val isMain = "Main" in rolesEn
            fun person(o: JSONObject, isCharacter: Boolean): Person? {
                val pid = o.int("id") ?: return null
                return Person(pid, o.str("russian") ?: o.str("name") ?: "", absolute(o.obj("image")?.str("preview")), rolesRu.joinToString(", "), isMain, isCharacter)
            }
            r.obj("character")?.let { person(it, true) }?.let { characters += it }
                ?: r.obj("person")?.takeIf { rolesEn.any { it in key } }?.let { person(it, false) }?.let { staff += it }
        }
        return characters.sortedByDescending { it.isMain }.take(30) to staff.take(12)
    }

    suspend fun person(id: Int, isCharacter: Boolean): PersonPage? {
        val x = get(if (isCharacter) "/api/characters/$id" else "/api/people/$id") as? JSONObject ?: return null
        fun img(o: JSONObject?) = absolute(o?.obj("image")?.str("original"))
        val related = ArrayList<Person>(); val works = ArrayList<Work>()
        if (isCharacter) {
            x.arr("seyu").forEach { s -> s.int("id")?.let { related += Person(it, s.str("russian") ?: s.str("name") ?: "", img(s), "Сейю", false, false) } }
            x.arr("animes").forEach { a -> a.int("id")?.let { works += Work(it, a.str("name") ?: "", a.str("russian") ?: "", a.str("aired_on")?.take(4)?.toIntOrNull(), a.str("role") ?: "") } }
        } else {
            x.arr("roles").take(30).forEach { r ->
                r.arr("characters").forEach { c ->
                    val cid = c.int("id") ?: return@forEach
                    if (related.none { it.id == cid }) related += Person(cid, c.str("russian") ?: c.str("name") ?: "", img(c), "Персонаж", false, true)
                }
            }
            x.arr("works").forEach { w ->
                val a = w.obj("anime") ?: return@forEach
                a.int("id")?.let { works += Work(it, a.str("name") ?: "", a.str("russian") ?: "", a.str("aired_on")?.take(4)?.toIntOrNull(), w.str("role") ?: "") }
            }
        }
        val desc = plain(x.str("description") ?: "")
        return PersonPage(x.str("russian") ?: x.str("name") ?: "", x.str("japanese") ?: x.str("name"), img(x),
            desc.ifBlank { null }, related.take(30), works.sortedByDescending { it.year ?: 0 })
    }

    suspend fun friends(userId: Int): List<Friend> = list(get("/api/users/$userId/friends?limit=100")).mapNotNull { x ->
        x.int("id")?.let { Friend(it, x.str("nickname") ?: "—", absolute(x.obj("image")?.str("x80") ?: x.str("avatar"))) }
    }

    suspend fun history(user: Friend, limit: Int = 15): List<HistoryEntry> = list(get("/api/users/${user.id}/history?limit=$limit")).mapNotNull { x ->
        val id = x.int("id") ?: return@mapNotNull null
        val date = parseDate(x.str("created_at")) ?: return@mapNotNull null
        val t = x.obj("target")
        if (t?.str("url")?.contains("/animes/") == false) return@mapNotNull null
        HistoryEntry(id, date, plain(x.str("description") ?: ""), t?.int("id"), t?.str("name") ?: "", t?.str("russian"),
            absolute(t?.obj("image")?.str("preview")), user)
    }

    /** "2026_fall", "2026_summer"… — 50 most popular of the season. */
    suspend fun season(season: String): List<SeasonAnime> {
        val q = """{ animes(season: "$season", limit: 50, order: popularity, kind: "tv,ona,movie,ova") {
            id name russian kind score status episodes episodesAired nextEpisodeAt
            airedOn { date } poster { mainUrl } studios { name } genres { russian } } }"""
        val items = graphql(q)?.optJSONArray("animes") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val x = items.optJSONObject(i) ?: return@mapNotNull null
            val id = x.int("id") ?: return@mapNotNull null
            SeasonAnime(id, x.str("name") ?: "", x.str("russian"), x.str("kind") ?: "", x.dbl("score")?.takeIf { it > 0 },
                absolute(x.obj("poster")?.str("mainUrl")), x.arr("studios").mapNotNull { it.str("name") },
                x.arr("genres").mapNotNull { it.str("russian") }, x.int("episodes") ?: 0, x.int("episodesAired") ?: 0,
                parseDate(x.str("nextEpisodeAt")), x.obj("airedOn")?.str("date"))
        }
    }

    /** Current season key and the 3 before it ("2026_fall" → name "Осень 2026"). */
    fun seasons(): List<Pair<String, String>> {
        val c = java.util.Calendar.getInstance()
        var y = c.get(java.util.Calendar.YEAR); var s = c.get(java.util.Calendar.MONTH) / 3   // 0 winter … 3 fall
        val keys = listOf("winter", "spring", "summer", "fall"); val names = listOf("Зима", "Весна", "Лето", "Осень")
        return (0 until 4).map {
            val r = "${y}_${keys[s]}" to "${names[s]} $y"
            s -= 1; if (s < 0) { s = 3; y -= 1 }
            r
        }
    }
}
