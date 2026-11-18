// Based on YummyTV — https://github.com/Helandy/YummyTV (CC BY-NC 4.0; see assets/licenses).
// Modified: cancellable transport, episode identity validation, isolated bounded fallbacks.
package ru.qmods.anixq.tv.data.sources.extractors

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.data.sources.*
import java.io.IOException
import java.util.concurrent.TimeUnit

class AksorExtractor(http: SourceHttp) : StreamExtractor {
    // Leave time for HTML/script recovery within AnimeSources' overall 25-second deadline.
    private val http = SourceHttp(http.client.newBuilder().callTimeout(6, TimeUnit.SECONDS).build())
    override val name = "Aksor"
    override fun supports(url: String) = supportsUrl(url)
    override suspend fun resolve(url: String) = resolveEpisode(url, null, null)

    suspend fun resolveEpisode(url: String, episode: String?, animeId: Int?): ResolvedStream {
        val page = fullUrl(url.trim()).toHttpUrl()
        require(supportsUrl(page.toString())) { "Aksor: неверный адрес плеера" }
        val hash = page.pathSegments.let { segments ->
            val index = segments.indexOf("video")
            if (index >= 0) segments.getOrNull(index + 1) else segments.lastOrNull()
        }?.takeIf { it.isNotBlank() && it != "video" } ?: error("Aksor: нет ID видео")
        val headers = mapOf("Referer" to page.toString(), "User-Agent" to SourceHttp.UA)
        val attempted = HashSet<String>()
        suspend fun api(endpoint: String): Map<String, String>? {
            if (!attempted.add(endpoint)) return null
            return recover {
                val json = JSONObject(http.request(endpoint, headers + ("Accept" to "application/json")).text)
                // A stale/cross-linked URL must never silently play another episode or season.
                if (json.str("id")?.let { it != hash } == true ||
                    (episode != null && json.str("episode")?.let { !YummyAnime.sameNumber(it, episode) } == true) ||
                    (animeId != null && json.int("anime_id")?.let { it != animeId } == true)) {
                    throw WrongEpisode()
                }
                parseQualities(json).takeIf { it.isNotEmpty() }
            }
        }
        api("https://player.aksor.tv/api/video/$hash")?.let { return ResolvedStream(it, headers) }
        val html = recover { http.request(page.toString(), mapOf("Referer" to "https://yani.tv/")).text }
            ?: throw unavailable()
        Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { tag ->
            val attrs = attributes(tag.value)
            if (attrs["name"].equals("video_url", true)) {
                streamUrl(attrs["content"].orEmpty())?.let { return ResolvedStream(mapOf("auto" to it), headers) }
            }
        }
        // Broken analytics/advertising scripts cannot abort the player script fallback.
        val scripts = Regex("""<script\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
            .mapNotNull { attributes(it.value)["src"]?.let(page::resolve) }.distinct().take(6)
        for (script in scripts) {
            val js = recover { http.request(script.toString(), headers).text } ?: continue
            val paths = Regex("""["']([^"']*/api)["']""").findAll(js).map { it.groupValues[1] }.take(4)
            for (path in paths) {
                val endpoint = page.resolve("${path.trimEnd('/')}/video/$hash") ?: continue
                if (!supportsUrl(endpoint.toString())) continue
                api(endpoint.toString())?.let { return ResolvedStream(it, headers) }
            }
        }
        throw unavailable()
    }

    private class WrongEpisode : IOException("Aksor вернул другую серию. Обновите список серий или выберите другой источник")
    private fun unavailable() = IOException("Aksor: поток недоступен. Если источник вернул заглушку, войдите в YummyAnime в настройках; либо выберите другой балансер")
    private suspend fun <T> recover(block: suspend () -> T): T? = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: WrongEpisode) { throw e }
        catch (_: Exception) { null }

    companion object {
        fun supportsUrl(url: String): Boolean = runCatching {
            fullUrl(url.trim()).toHttpUrl().host.let { it == "aksor.tv" || it.endsWith(".aksor.tv") }
        }.getOrDefault(false)
        private fun attributes(tag: String): Map<String, String> = Regex("""([\w-]+)\s*=\s*["']([^"']*)["']""")
            .findAll(tag).associate { it.groupValues[1].lowercase() to it.groupValues[2].replace("&amp;", "&").replace("&#38;", "&") }
        private fun streamUrl(raw: String): String? = raw.trim().takeIf { !it.contains("{{") }
            ?.let(::fullUrl)?.takeIf { it.toHttpUrlOrNullSafe() && isStream(it) }
        fun parseQualities(json: JSONObject): Map<String, String> = buildMap {
            val q = json.obj("qualities") ?: return@buildMap
            for ((key, label) in listOf("q360" to "360p", "q480" to "480p", "q720" to "720p", "q1080" to "1080p", "q2k" to "2K", "q4k" to "4K")) {
                q.str(key)?.let(::streamUrl)?.let { put(label, it) }
            }
        }
    }
}
internal fun String.toHttpUrlOrNullSafe() = runCatching { toHttpUrl(); true }.getOrDefault(false)
internal fun isStream(url: String) = Regex("(?i)\\.(m3u8|mp4|mpd|webm)(?:[?#]|$)").containsMatchIn(url)
