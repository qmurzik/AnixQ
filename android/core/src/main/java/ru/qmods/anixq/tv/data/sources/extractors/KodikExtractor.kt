// Based on YummyTV — https://github.com/Helandy/YummyTV (CC BY-NC 4.0; see assets/licenses).
// Modified for AnixQ contracts, cancellable HTTP and host-scoped session cookies.
package ru.qmods.anixq.tv.data.sources.extractors

import okio.ByteString.Companion.decodeBase64
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.data.sources.*

class KodikExtractor(private val http: SourceHttp) : StreamExtractor {
    override val name = "Kodik"
    override fun supports(url: String) = url.toHttpUrlOrNullSafe() && url.toHttpUrl().host.contains("kodik")
    override suspend fun resolve(url: String): ResolvedStream {
        val page = url.toHttpUrl()
        val headers = mapOf("Referer" to url, "User-Agent" to SourceHttp.UA)
        val reply = http.request(url, mapOf("Referer" to "https://yani.tv/"))
        val html = reply.text.replace("\n", "")
        fun field(pattern: String): String = Regex(pattern).find(html)?.groupValues?.get(1) ?: error("Kodik: изменился формат страницы")
        val params = JSONObject(field("""\burlParams\s*=\s*'([^']+)'"""))
        val type = field("""\b(?:videoInfo|vInfo)\.type\s*=\s*'([^']+)'""")
        val hash = field("""\b(?:videoInfo|vInfo)\.hash\s*=\s*'([^']+)'""")
        val id = field("""\b(?:videoInfo|vInfo)\.id\s*=\s*'([^']+)'""")
        val script = field("""src=["']([^"']*/assets/js/app\.player_single[^"']+)["']""")
        val scriptUrl = page.resolve(script) ?: error("Kodik: неверный адрес скрипта")
        // Never send page cookies to a different script or CDN host.
        val cookies = reply.headers.values("Set-Cookie").mapNotNull { okhttp3.Cookie.parse(page, it) }
            .filter { it.matches(page) }.joinToString("; ") { "${it.name}=${it.value}" }
        val js = http.request(scriptUrl.toString(), headers).text
        val path = Regex("""atob\(["']([A-Za-z0-9+/=]+)["']\)""").findAll(js).mapNotNull {
            it.groupValues[1].decodeBase64()?.utf8()
        }.firstOrNull { it.startsWith("/") && !it.startsWith("//") && it.length <= 10 } ?: "/ftor"
        val endpoint = page.resolve(path) ?: error("Kodik: неверный API")
        val body = FormBody.Builder().apply {
            listOf("d", "d_sign", "pd", "pd_sign", "ref_sign").forEach { add(it, params.optString(it)) }
            addEncoded("ref", params.optString("ref"))
            add("bad_user", "true"); add("cdn_is_working", "true"); add("type", type); add("hash", hash); add("id", id); add("info", "{}")
        }.build()
        val json = JSONObject(http.request(endpoint.toString(), headers + mapOf("Cookie" to cookies, "X-Requested-With" to "XMLHttpRequest"), body).text)
        val links = json.obj("links") ?: error("Kodik: видео недоступно или требуется авторизация")
        val qualities = buildMap {
            for (q in listOf(240, 360, 480, 720, 1080)) {
                val src = links.optJSONArray(q.toString())?.optJSONObject(0)?.str("src") ?: continue
                val decoded = if (src.contains("//")) src else decode(src) ?: continue
                val full = fullUrl(decoded)
                if (full.toHttpUrlOrNullSafe()) {
                    // Some responses label one lower-resolution manifest as every quality.
                    val actual = Regex("""/(\d+)\.mp4:hls:manifest\.m3u8""").find(full)?.groupValues?.get(1) ?: q.toString()
                    put("${actual}p", full)
                }
            }
        }
        check(qualities.isNotEmpty()) { "Kodik: нет поддерживаемого прямого потока" }
        return ResolvedStream(qualities, mapOf("User-Agent" to SourceHttp.UA))
    }
    private fun decode(src: String): String? = runCatching {
        val rotated = src.map { c -> when(c) {
            in 'A'..'Z' -> ('A'.code + (c.code - 'A'.code + 18) % 26).toChar()
            in 'a'..'z' -> ('a'.code + (c.code - 'a'.code + 18) % 26).toChar()
            else -> c
        } }.joinToString("")
        (rotated + "=".repeat((4 - rotated.length % 4) % 4)).decodeBase64()?.utf8()
    }.getOrNull()
}
