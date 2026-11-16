// Protocol handling adapted from YummyTV (CC BY-NC 4.0; see assets/licenses/YummyTV-NOTICE).
package ru.qmods.anixq.tv.data.sources.extractors

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.data.sources.*

class DirectExtractor : StreamExtractor {
    override val name = "Прямой поток"
    override fun supports(url: String) = url.toHttpUrlOrNullSafe() && isStream(url)
    override suspend fun resolve(url: String) = ResolvedStream(mapOf("auto" to url), emptyMap())
}
class SibnetExtractor(private val http: SourceHttp) : StreamExtractor {
    override val name = "Sibnet"
    override fun supports(url: String) = url.toHttpUrlOrNullSafe() && url.toHttpUrl().host == "video.sibnet.ru"
    override suspend fun resolve(url: String): ResolvedStream {
        val html = http.request(url, mapOf("Referer" to "https://yani.tv/")).text
        val src = Regex("""(?:src\s*:\s*|<source[^>]+src=)["']([^"']+)["']""").findAll(html)
            .mapNotNull { url.toHttpUrl().resolve(it.groupValues[1])?.toString() }.firstOrNull(::isStream)
            ?: error("Sibnet: поток недоступен")
        return ResolvedStream(mapOf("auto" to src), mapOf("Referer" to url, "Origin" to "https://video.sibnet.ru", "User-Agent" to SourceHttp.UA))
    }
}
class RutubeExtractor(private val http: SourceHttp) : StreamExtractor {
    override val name = "Rutube"
    override fun supports(url: String) = url.toHttpUrlOrNullSafe() && url.toHttpUrl().host.let { it == "rutube.ru" || it.endsWith(".rutube.ru") }
    override suspend fun resolve(url: String): ResolvedStream {
        val id = Regex("[a-fA-F0-9]{32}").find(url.toHttpUrl().encodedPath)?.value ?: error("Rutube: нет ID видео")
        val headers = mapOf("Referer" to url, "Origin" to "https://rutube.ru", "User-Agent" to SourceHttp.UA)
        val json = JSONObject(http.request("https://rutube.ru/api/play/options/$id/?no_404=true", headers).text)
        val b = json.obj("video_balancer") ?: error("Rutube: видео ограничено или недоступно")
        val stream = b.str("m3u8") ?: b.str("default") ?: error("Rutube: нет прямого потока")
        val qualities = linkedMapOf("auto" to fullUrl(stream))
        val master = http.request(fullUrl(stream), headers).text
        qualities.putAll(parseHls(master, fullUrl(stream)))
        return ResolvedStream(qualities, headers)
    }
    companion object {
        fun parseHls(text: String, base: String): Map<String, String> = buildMap {
            var height: String? = null
            for (line in text.lineSequence().map { it.trim() }) {
                if (line.startsWith("#EXT-X-STREAM-INF")) height = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.get(1)
                else if (line.isNotBlank() && !line.startsWith("#")) { height?.let { h -> base.toHttpUrl().resolve(line)?.let { put("${h}p", it.toString()) } }; height = null }
            }
        }
    }
}
class VkExtractor(private val http: SourceHttp) : StreamExtractor {
    override val name = "VK Видео"
    override fun supports(url: String): Boolean {
        if (!url.toHttpUrlOrNullSafe()) return false
        val u = url.toHttpUrl()
        return u.host in listOf("vk.com", "vkvideo.ru", "vkvideo.com", "vk.ru") || u.encodedPath.contains("iframevk", true)
    }
    override suspend fun resolve(url: String): ResolvedStream {
        val headers = mapOf("Referer" to url, "User-Agent" to SourceHttp.UA)
        var pageUrl = url
        var html = http.request(pageUrl, headers).text
        val iframe = Regex("""(?:src=["']|["'])(https?://(?:vk\.com|vkvideo\.ru)/video_ext\.php[^"']+)""").find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
        if (iframe != null) { pageUrl = iframe; html = http.request(iframe, headers).text }
        check(!html.contains("video_ext_msg")) { "VK: видео удалено, закрыто или недоступно в регионе" }
        val normalized = html.replace("\\/", "/").replace("&amp;", "&")
        val qualities = buildMap {
            Regex("""["']?(?:url|mp4_)(\d{3,4})["']?\s*[:=]\s*["']([^"']+)["']""").findAll(normalized).forEach {
                val stream = fullUrl(it.groupValues[2])
                if (stream.toHttpUrlOrNullSafe()) put("${it.groupValues[1]}p", stream)
            }
            Regex("""["']hls(?:_fmp4)?["']\s*:\s*["']([^"']+)["']""").find(normalized)?.groupValues?.get(1)?.let {
                if (fullUrl(it).toHttpUrlOrNullSafe()) put("auto", fullUrl(it))
            }
        }
        check(qualities.isNotEmpty()) { "VK: прямые ссылки не предоставлены; выберите другой балансер" }
        return ResolvedStream(qualities, mapOf("Referer" to pageUrl, "User-Agent" to SourceHttp.UA))
    }
}
