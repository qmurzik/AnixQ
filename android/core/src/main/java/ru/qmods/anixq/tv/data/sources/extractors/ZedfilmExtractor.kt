// Protocol adapted from YummyTV (CC BY-NC 4.0; see assets/licenses/YummyTV-NOTICE).
package ru.qmods.anixq.tv.data.sources.extractors

import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import ru.qmods.anixq.tv.data.str
import ru.qmods.anixq.tv.data.sources.*

/** Static public video_Init metadata only; no browser, DRM or access-control bypass. */
class ZedfilmExtractor(private val http: SourceHttp) : StreamExtractor {
    override val name = "Zedfilm"
    override fun supports(url: String) = url.toHttpUrlOrNullSafe() && url.toHttpUrl().host.let { host ->
        listOf("zedfilm.ru", "hlamer.ru").any { host == it || host.endsWith(".$it") }
    }
    override suspend fun resolve(url: String): ResolvedStream {
        val headers = mapOf("Referer" to url, "Origin" to "https://hlamer.ru", "User-Agent" to SourceHttp.UA)
        val page = http.request(url, headers + mapOf("Referer" to "https://yani.tv/", "Accept-Language" to "ru-RU,ru;q=0.9,en;q=0.8")).text
        val source = parse(page, url) ?: error("Zedfilm: прямой поток недоступен")
        return ResolvedStream(mapOf("auto" to source), headers)
    }
    companion object {
        fun parse(page: String, base: String): String? {
            val payload = Regex("""(?i)video_Init\(\s*["']([^"']+)["']""").find(page)?.groupValues?.get(1)
            val data = payload?.decodeBase64()?.utf8()?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
            return listOfNotNull(data.str("url"), data.str("url2")).firstNotNullOfOrNull { raw ->
                val normalized = raw.replace("\\/", "/").replace("&amp;", "&")
                val url = if (normalized.startsWith("/") && !normalized.startsWith("//")) "https://hlamer.ru$normalized" else base.toHttpUrl().resolve(normalized)?.toString()
                url?.takeIf(::isStream)
            }
        }
    }
}
