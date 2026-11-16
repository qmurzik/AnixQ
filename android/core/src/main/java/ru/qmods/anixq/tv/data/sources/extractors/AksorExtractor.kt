// Based on YummyTV — https://github.com/Helandy/YummyTV (CC BY-NC 4.0; see assets/licenses).
// Modified: OkHttp coroutine transport, AnixQ contracts, bounded fallback preserving all qualities.
package ru.qmods.anixq.tv.data.sources.extractors

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.data.sources.*

class AksorExtractor(private val http: SourceHttp) : StreamExtractor {
    override val name = "Aksor"
    override fun supports(url: String) = runCatching { url.toHttpUrl().host.let { it == "aksor.tv" || it.endsWith(".aksor.tv") } }.getOrDefault(false)
    override suspend fun resolve(url: String): ResolvedStream {
        val page = url.toHttpUrl()
        val hash = page.pathSegments.let { segments -> val index = segments.indexOf("video"); if (index >= 0) segments.getOrNull(index + 1) else segments.lastOrNull() }?.takeIf { it.isNotBlank() } ?: error("Aksor: нет ID видео")
        val headers = mapOf("Referer" to url, "User-Agent" to SourceHttp.UA)
        suspend fun api(endpoint: String) = parseQualities(JSONObject(http.request(endpoint, headers + ("Accept" to "application/json")).text))
        val primary = try { api("https://player.aksor.tv/api/video/$hash") } catch(e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }
        if (primary.isNotEmpty()) return ResolvedStream(primary, headers)
        val html = http.request(url, mapOf("Referer" to "https://yani.tv/")).text
        Regex("""<meta[^>]+name=["']video_url["'][^>]+content=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?.takeIf { !it.contains("{{") && isStream(it) }?.let { return ResolvedStream(mapOf("auto" to fullUrl(it)), headers) }
        val scripts = Regex("""<script[^>]+src=["']([^"']+)["']""").findAll(html).take(4)
        for (script in scripts) {
            val scriptUrl = page.resolve(script.groupValues[1]) ?: continue
            val js = http.request(scriptUrl.toString(), headers).text
            val path = Regex("""["']([^"']*/api)["']""").find(js)?.groupValues?.get(1) ?: continue
            val endpoint = page.resolve("$path/video/$hash") ?: continue
            val qualities = api(endpoint.toString())
            if (qualities.isNotEmpty()) return ResolvedStream(qualities, headers)
        }
        error("Aksor: поток не найден; выберите другой балансер")
    }
    companion object {
        fun parseQualities(json: JSONObject): Map<String, String> = buildMap {
            val q = json.obj("qualities") ?: return@buildMap
            for ((key, label) in listOf("q360" to "360p", "q480" to "480p", "q720" to "720p", "q1080" to "1080p", "q2k" to "2K", "q4k" to "4K")) {
                q.str(key)?.let(::fullUrl)?.takeIf { it.toHttpUrlOrNullSafe() }?.let { put(label, it) }
            }
        }
    }
}
internal fun String.toHttpUrlOrNullSafe() = runCatching { toHttpUrl(); true }.getOrDefault(false)
internal fun isStream(url: String) = Regex("(?i)\\.(m3u8|mp4|mpd|webm)(?:[?#]|$)").containsMatchIn(url)
