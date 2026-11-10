package ru.qmods.anixq.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * AniLibria's own servers for the AniLibria dub: when AnimeLib is slow (or only has it in Kodik), the same
 * episode comes straight from AniLibria as HLS — no ads, usually faster. Same matching as the web room.
 */
object Libria {
    private const val API = "https://anilibria.top/api/v1"
    /** titleId → AniLibria release (episodes), or JSONObject.NULL when they don't have it. */
    private val releases = HashMap<Int, Any>()

    fun isLibria(teamName: String?) = teamName != null && Regex("ani\\s*libria|анилибри", RegexOption.IGNORE_CASE).containsMatchIn(teamName)

    private fun norm(s: String?) = (s ?: "").lowercase().replace('ё', 'е').replace(Regex("[^a-zа-я0-9]+"), "")

    private fun get(url: String): String? = runCatching {
        Api.http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    private fun release(title: Title): JSONObject? {
        synchronized(releases) { releases[title.id]?.let { return it as? JSONObject } }
        val names = listOfNotNull(title.rusName, title.name, title.engName).map(::norm).filter { it.isNotEmpty() }.toSet()
        fun matches(r: JSONObject): Boolean {
            val n = r.optJSONObject("name") ?: return names.isEmpty()
            return names.isEmpty() || norm(n.optString("main")) in names || norm(n.optString("english")) in names
        }
        // AnimeLib's slug ("123--jujutsu-kaisen-anime") is usually AniLibria's alias; the name decides.
        val alias = title.slug.substringAfter("--").removeSuffix("-anime")
        var found = get("$API/anime/releases/$alias")?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf(::matches)
        if (found == null) {
            val query = title.rusName ?: title.name ?: alias
            val url = "$API/app/search/releases".toHttpUrl().newBuilder().addQueryParameter("query", query).build().toString()
            val list = get(url)?.let { runCatching { JSONArray(it) }.getOrNull() }
            val hit = list?.let { l -> (0 until l.length()).mapNotNull { l.optJSONObject(it) }.firstOrNull(::matches) }
            found = hit?.let { h -> get("$API/anime/releases/${h.optInt("id")}")?.let { runCatching { JSONObject(it) }.getOrNull() } }
        }
        synchronized(releases) { releases[title.id] = found ?: JSONObject.NULL }
        return found
    }

    /**
     * The AniLibria version of [base] (AnimeLib's AniLibria player, native or Kodik) for this episode:
     * same team / ids for progress, hrefs are AniLibria HLS links. Null if they don't have it (or don't answer in 6 s).
     */
    suspend fun player(title: Title, episodeNumber: String, base: Player): Player? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(6_000) {
            val r = release(title) ?: return@withTimeoutOrNull null
            val n = episodeNumber.toDoubleOrNull() ?: return@withTimeoutOrNull null
            val eps = r.optJSONArray("episodes") ?: return@withTimeoutOrNull null
            val ep = (0 until eps.length()).mapNotNull { eps.optJSONObject(it) }.firstOrNull { it.optDouble("ordinal", -1.0) == n }
                ?: return@withTimeoutOrNull null
            val hrefs = linkedMapOf<String, String>()
            for (q in listOf("1080", "720", "480")) ep.optString("hls_$q").takeIf { it.startsWith("http") }?.let { hrefs["${q}p"] = it }
            if (hrefs.isEmpty()) return@withTimeoutOrNull null
            // AniLibria's own marks fit their file exactly; AnimeLib's are for AnimeLib's copy.
            val segs = run {
                listOfNotNull(ep.optJSONObject("opening")?.let { o ->
                    val a = o.optDouble("start", Double.NaN); val b = o.optDouble("stop", Double.NaN)
                    if (!a.isNaN() && !b.isNaN() && b > a) Segment("opening", a, b) else null
                }, ep.optJSONObject("ending")?.let { o ->
                    val a = o.optDouble("start", Double.NaN); val b = o.optDouble("stop", Double.NaN)
                    if (!a.isNaN() && !b.isNaN() && b > a) Segment("ending", a, b) else null
                })
            }.ifEmpty { base.segments }
            base.copy(player = "animelib", hrefs = hrefs, segments = segs, source = SOURCE)
        }
    }

    const val SOURCE = "anilibria"
}
