// Based on YummyTV — https://github.com/Helandy/YummyTV (CC BY-NC 4.0; see assets/licenses).
// Modified for AnixQ: strict voice/episode selection and independent HTTP transport.
package ru.qmods.anixq.tv.data.sources.extractors

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.data.sources.*

class CvhExtractor(private val http: SourceHttp) : StreamExtractor {
    override val name = "CVH"
    override fun supports(url: String) = url.toHttpUrlOrNullSafe() && url.toHttpUrl().let {
        it.encodedPath.contains("iframeCVH", true) || (it.host == API_HOST && it.encodedPath.startsWith("${API_PATH}/video/"))
    }

    override suspend fun resolve(url: String): ResolvedStream {
        val target = url.toHttpUrl()
        if (target.host == API_HOST && target.encodedPath.startsWith("${API_PATH}/video/")) {
            val id = target.pathSegments.last().takeIf { it.isNotBlank() } ?: error("CVH: отсутствует ID видео")
            return resolveVideo(id, shikiHeaders, adaptive = true)
        }
        val u = url.toHttpUrl()
        val anime = u.queryParameter("anime_id")?.toIntOrNull() ?: error("CVH: нет anime_id")
        val number = u.queryParameter("episode") ?: error("CVH: нет номера серии")
        val code = u.queryParameter("dubbing_code").orEmpty()
        val label = u.queryParameter("dubbing").orEmpty()
        val headers = mapOf("Referer" to "https://ru.yummyani.me/", "User-Agent" to SourceHttp.UA)
        val playlist = JSONObject(http.request("https://plapi.cdnvideohub.com/api/v1/player/sv/playlist?pub=745&id=$anime&aggr=mali", headers).text)
        val item = select(playlist, number, code, label, u.queryParameter("season")) ?: error("CVH: нет выбранной серии и озвучки или доступ ограничен")
        val id = item.str("vkId") ?: error("CVH: отсутствует ID видео")
        return resolveVideo(id, headers, adaptive = false)
    }

    suspend fun shikimoriPlaylist(id: Int): JSONObject {
        require(SourceIds.validShikiId(id))
        return JSONObject(http.request("$API/playlist?pub=3058&id=$id&aggr=mali", shikiHeaders).text)
    }

    private suspend fun resolveVideo(id: String, headers: Map<String, String>, adaptive: Boolean): ResolvedStream {
        val video = JSONObject(http.request(shikimoriVideoUrl(id), headers).text)
        val qualities = parseQualities(video, adaptive)
        check(qualities.isNotEmpty()) { "CVH: нет доступных потоков" }
        val mimeTypes = if (adaptive) qualities.mapValues { (quality, url) ->
            when {
                quality != "auto" -> "video/mp4"
                mediaUrl(video, "hlsUrl") == url -> "application/x-mpegURL"
                else -> "application/dash+xml"
            }
        } else emptyMap()
        return ResolvedStream(qualities, mapOf("User-Agent" to SourceHttp.UA), mimeTypes)
    }

    companion object {
        private const val API_HOST = "plapi.cdnvideohub.com"
        private const val API_PATH = "/api/v1/player/sv"
        private const val API = "https://$API_HOST$API_PATH"
        private val shikiHeaders = mapOf("Referer" to "${ShikimoriCatalog.SITE}/", "Origin" to ShikimoriCatalog.SITE, "User-Agent" to SourceHttp.UA)

        fun shikimoriVideoUrl(id: String): String = "$API/video".toHttpUrl().newBuilder().addPathSegment(id).build().toString()


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
        private fun mediaUrl(json: JSONObject, key: String): String? {
            val raw = json.obj("sources")?.str(key)?.let(::fullUrl)?.takeIf { it.toHttpUrlOrNullSafe() } ?: return null
            val u = raw.toHttpUrl()
            val failover = json.str("failoverHost")
            return if (Regex("""\d+\.\d+\.\d+\.\d+""").matches(u.host) && failover != null)
                u.newBuilder().host(failover).build().toString() else raw
        }

        fun parseQualities(json: JSONObject, adaptive: Boolean = false): Map<String, String> = buildMap {
            for ((key, label) in listOf("mpegLowestUrl" to "240p", "mpegLowUrl" to "360p", "mpegMediumUrl" to "480p", "mpegHighUrl" to "720p", "mpegFullHdUrl" to "1080p", "mpegQhdUrl" to "2K", "mpeg2kUrl" to "2K", "mpeg4kUrl" to "4K")) {
                if (!containsKey(label)) mediaUrl(json, key)?.let { put(label, it) }
            }
            if (adaptive) (mediaUrl(json, "hlsUrl") ?: mediaUrl(json, "dashUrl"))?.let { put("auto", it) }
        }
    }
}
