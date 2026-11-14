package ru.qmods.anixq.tv.data

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * "What anime is this?" — trace.moe finds the episode and moment from a whole frame; AnimeTrace recognises
 * characters (works on cropped, mirrored or zoomed pictures). Same services as the iPhone app.
 */
object Trace {
    data class Moment(val anilistId: Int, val malId: Int?, val names: List<String>, val cover: String?, val episode: String?,
                      val from: Double, val similarity: Double, val image: String?, val moments: Int) {
        val title get() = names.firstOrNull() ?: "?"
    }
    data class CharacterMatch(val work: String, val characters: List<String>, val hits: Int)

    class TraceException(message: String) : Exception(message)

    private val client by lazy { Api.http.newBuilder().callTimeout(45, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build() }

    fun jpeg(b: Bitmap, maxSide: Int, quality: Int = 85): ByteArray {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(b.width, b.height))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true) else b
        return ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }

    /** trace.moe: the title, episode and moment of a frame. */
    suspend fun search(frame: Bitmap): List<Moment> = withContext(Dispatchers.IO) {
        val body = jpeg(frame, 1280).toRequestBody("image/jpeg".toMediaType())
        val json = client.newCall(Request.Builder().url("https://api.trace.moe/search?cutBorders&anilistInfo").post(body).build()).execute().use { r ->
            if (r.code == 402 || r.code == 429) throw TraceException("Лимит поиска trace.moe исчерпан, попробуйте позже")
            if (!r.isSuccessful) throw TraceException("trace.moe ответил ${r.code}")
            JSONObject(r.body!!.string())
        }
        json.str("error")?.let { throw TraceException("trace.moe: $it") }
        val byAnime = LinkedHashMap<Int, Moment>()
        for (x in json.arr("result")) {
            val a = x.obj("anilist") ?: continue
            val id = a.int("id") ?: continue
            val seen = byAnime[id]
            if (seen != null) { byAnime[id] = seen.copy(moments = seen.moments + 1); continue }
            val t = a.obj("title")
            val names = listOfNotNull(t?.str("romaji"), t?.str("english"), t?.str("native")) + (a.optJSONArray("synonyms")?.strings() ?: emptyList())
            byAnime[id] = Moment(id, a.int("idMal"), names, a.obj("coverImage")?.let { it.str("large") ?: it.str("medium") },
                x.str("episode") ?: x.int("episode")?.toString(), x.dbl("from") ?: 0.0, x.dbl("similarity") ?: 0.0, x.str("image"), 1)
        }
        byAnime.values.toList()
    }

    /** The AnimeLib title for a trace.moe match: MAL id (= Shikimori id) first, then the names. */
    suspend fun resolve(m: Moment): Title? = m.malId?.let { Franchise.titleFor(it, m.names.take(3)) }
        ?: m.names.take(3).firstNotNullOfOrNull { n -> runCatching { Api.search(n).firstOrNull() }.getOrNull() }

    /** AnimeTrace: characters on a frame → their anime (most votes first). */
    suspend fun characters(frame: Bitmap): List<CharacterMatch> = withContext(Dispatchers.IO) {
        val b64 = Base64.encodeToString(jpeg(frame, 800), Base64.NO_WRAP)
        for (model in listOf("pre_stable", "anime_model_lovelive")) {
            val form = FormBody.Builder().add("is_multi", "1").add("ai_detect", "0").add("model", model).add("base64", b64).build()
            val json = runCatching {
                client.newCall(Request.Builder().url("https://api.animetrace.com/v1/search").header("User-Agent", "Mozilla/5.0 (Linux; Android 14)").post(form).build())
                    .execute().use { r -> JSONObject(r.body!!.string()) }
            }.getOrNull() ?: continue
            val works = LinkedHashMap<String, Pair<MutableList<String>, Int>>()
            for (box in json.arr("data")) {
                box.arr("character").take(3).forEachIndexed { rank, c ->
                    val work = c.str("work") ?: return@forEachIndexed
                    val e = works.getOrPut(work) { mutableListOf<String>() to 0 }
                    c.str("character")?.let { if (it !in e.first) e.first += it }
                    works[work] = e.first to e.second + if (rank == 0) 3 else 1
                }
            }
            val out = works.map { (w, e) -> CharacterMatch(w, e.first, e.second) }.sortedByDescending { it.hits }.take(4)
            if (out.isNotEmpty()) return@withContext out
        }
        emptyList()
    }

    /** The AnimeLib title for a work name from AnimeTrace (usually Japanese): Shikimori search, then AnimeLib's. */
    suspend fun resolve(work: String): Title? {
        val shiki = runCatching {
            withContext(Dispatchers.IO) {
                val url = "https://shikimori.io/api/animes?limit=3&order=popularity&search=" + java.net.URLEncoder.encode(work, "UTF-8")
                Api.http.newCall(Request.Builder().url(url).header("User-Agent", "AnixQ").build()).execute().use { r -> org.json.JSONArray(r.body!!.string()) }
            }
        }.getOrNull()
        shiki?.optJSONObject(0)?.let { a -> a.int("id")?.let { id -> Franchise.titleFor(id, listOfNotNull(a.str("russian"), a.str("name"), work))?.let { return it } } }
        return runCatching { Api.search(work).firstOrNull() }.getOrNull()
    }
}
