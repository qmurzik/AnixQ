package ru.qmods.anixq.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Opening / ending times from AniSkip (community database, by MAL id = Shikimori id).
 * Used when AnimeLib has no timings for the episode, so "Пропустить опенинг" works almost everywhere.
 */
object AniSkip {
    private val cache = HashMap<String, List<Segment>>()

    suspend fun segments(malId: Int, episode: String, duration: Double): List<Segment> = withContext(Dispatchers.IO) {
        val ep = episode.toDoubleOrNull()?.toInt() ?: return@withContext emptyList()
        val key = "$malId/$ep"
        val all = cache[key] ?: runCatching {
            Api.http.newCall(Request.Builder()
                .url("https://api.aniskip.com/v2/skip-times/$malId/$ep?types[]=op&types[]=ed&episodeLength=0").build())
                .execute().use { r ->
                    if (!r.isSuccessful) return@use emptyList()
                    val a = JSONObject(r.body!!.string()).optJSONArray("results") ?: return@use emptyList()
                    (0 until a.length()).mapNotNull { i ->
                        val x = a.optJSONObject(i) ?: return@mapNotNull null
                        val iv = x.optJSONObject("interval") ?: return@mapNotNull null
                        val kind = if (x.optString("skipType") == "op") "opening" else if (x.optString("skipType") == "ed") "ending" else return@mapNotNull null
                        // episodeLength tells which cut the times belong to; keep it to compare with ours.
                        Segment(kind, iv.optDouble("startTime"), iv.optDouble("endTime"), x.optDouble("episodeLength", 0.0))
                    }
                }
        }.getOrDefault(emptyList()).also { cache[key] = it }
        // Only trust times recorded on a cut of (almost) the same length as the video we play.
        val fit = all.filter { s -> s.source <= 0 || duration <= 0 || kotlin.math.abs(s.source - duration) < 25 }
        fit.groupBy { it.isOpening }.values.mapNotNull { it.firstOrNull() }
    }
}
