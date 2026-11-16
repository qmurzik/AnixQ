// Based on YummyTV — https://github.com/Helandy/YummyTV (CC BY-NC 4.0; see assets/licenses).
// Modified for AnixQ: strict voice/episode selection and independent HTTP transport.
package ru.qmods.anixq.tv.data.sources.extractors

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.data.sources.*

class CvhExtractor(private val http: SourceHttp) : StreamExtractor {
    override val name = "CVH"
    override fun supports(url: String) = url.toHttpUrlOrNullSafe() && url.toHttpUrl().encodedPath.contains("iframeCVH", true)
    override suspend fun resolve(url: String): ResolvedStream {
        val u = url.toHttpUrl()
        val anime = u.queryParameter("anime_id")?.toIntOrNull() ?: error("CVH: нет anime_id")
        val number = u.queryParameter("episode") ?: error("CVH: нет номера серии")
        val code = u.queryParameter("dubbing_code").orEmpty()
        val label = u.queryParameter("dubbing").orEmpty()
        val headers = mapOf("Referer" to "https://ru.yummyani.me/", "User-Agent" to SourceHttp.UA)
        val playlist = JSONObject(http.request("https://plapi.cdnvideohub.com/api/v1/player/sv/playlist?pub=745&id=$anime&aggr=mali", headers).text)
        val item = select(playlist, number, code, label, u.queryParameter("season")) ?: error("CVH: нет выбранной серии и озвучки или доступ ограничен")
        val id = item.str("vkId") ?: error("CVH: отсутствует ID видео")
        val video = JSONObject(http.request("https://plapi.cdnvideohub.com/api/v1/player/sv/video/" + id, headers).text)
        val qualities = parseQualities(video)
        check(qualities.isNotEmpty()) { "CVH: нет доступных MP4 потоков" }
        return ResolvedStream(qualities, mapOf("User-Agent" to SourceHttp.UA))
    }
    companion object {
        fun select(json: JSONObject, number: String, code: String, label: String, season: String? = null): JSONObject? {
            val episodes = json.arr("items").filter { item ->
                season == null || item.int("season")?.toString() == season
            }.filter { item ->
                if (json.optBoolean("isSerial", true)) YummyAnime.sameNumber(item.optString("episode"), number)
                else number.toDoubleOrNull() == 1.0
            }
            // Prioritized, but never fall back to a different voice or season.
            fun equal(a: String?, b: String) = b.isNotBlank() && a.equals(b, true)
            val byCode = episodes.filter { equal(it.str("voiceStudio"), code) }
            if (byCode.isNotEmpty()) return byCode.singleOrNull()
            val clean = label.removePrefix("Озвучка ").trim()
            val byLabel = episodes.filter { equal(it.str("voiceStudio"), clean) || equal(listOfNotNull(it.str("voiceType"), it.str("voiceStudio")).joinToString(" "), clean) }
            return if (byLabel.isNotEmpty()) byLabel.singleOrNull() else if (code.isBlank() && label.isBlank()) episodes.singleOrNull() else null
        }
        fun parseQualities(json: JSONObject): Map<String, String> = buildMap {
            val sources = json.obj("sources") ?: return@buildMap
            for ((key, label) in listOf("mpegLowestUrl" to "240p", "mpegLowUrl" to "360p", "mpegMediumUrl" to "480p", "mpegHighUrl" to "720p", "mpegFullHdUrl" to "1080p", "mpegQhdUrl" to "2K", "mpeg2kUrl" to "2K", "mpeg4kUrl" to "4K")) {
                val raw = sources.str(key)?.let(::fullUrl)?.takeIf { it.toHttpUrlOrNullSafe() } ?: continue
                val u = raw.toHttpUrl()
                val failover = json.str("failoverHost")
                val resolved = if (Regex("\\d+\\.\\d+\\.\\d+\\.\\d+").matches(u.host) && failover != null)
                    u.newBuilder().host(failover).build().toString() else raw
                if (!containsKey(label)) put(label, resolved)
            }
        }
    }
}
