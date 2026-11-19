package ru.qmods.anixq.tv.data.sources

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.data.sources.extractors.AksorExtractor
import java.util.concurrent.TimeUnit

/** Public AnimeGO player listings for titles whose external ID has been confirmed. */
class AnimeGo(private val base: String = SITE, private val http: SourceHttp = SourceHttp()) {
    private val redirects = SourceHttp(http.client.newBuilder()
        .followRedirects(false).followSslRedirects(false).callTimeout(3, TimeUnit.SECONDS).build())
    private val matchCache = Recent<String, String?>()
    private val videoCache = Recent<String, JSONObject>()

    suspend fun players(title: Title, episode: Episode): List<Player> {
        if (title.malId == null && title.shikiId == null) return emptyList()
        val key = "${title.slug}:${title.malId}:${title.shikiId}"
        val path = matchCache.get(key, 300_000) { match(title) } ?: return emptyList()
        val id = path.substringAfterLast('-')
        val json = videoCache.get(id, 90_000) {
            val body = FormBody.Builder().add("id", id).build()
            val reply = http.request("$base/api/player", mapOf("Referer" to "$base$path", "Accept" to "application/json"), body)
            JSONObject(reply.text).also { check(it.optJSONArray("sources") != null) { "AnimeGO: неверный ответ плеера" } }
        }
        return parsePlayers(json, episode.number, id, title.typeLabel)
    }

    private suspend fun match(title: Title): String? {
        val query = listOfNotNull(title.rusName, title.name, title.engName).firstOrNull { it.isNotBlank() } ?: return null
        val searchUrl = "$base/search".toHttpUrl().newBuilder().addQueryParameter("q", query).build()
        val paths = searchPaths(http.request(searchUrl.toString()).text)
        val confirmed = mutableListOf<String>()
        // Bound concurrent lookups. Only read redirects from AnimeGO, not third-party pages.
        for (batch in paths.chunked(4)) {
            confirmed += coroutineScope { batch.map { path -> async {
                val id = path.substringAfterLast('-')
                val kind = if (title.malId != null) "mal" else "shikimori"
                val external = title.malId ?: title.shikiId ?: return@async null
                val target = try {
                    redirects.request("$base/go/$kind/$id", acceptRedirect = true)
                        .takeIf { it.status in setOf(301, 302, 303, 307, 308) }?.headers?.get("Location")
                } catch (error: SourceHttp.HttpError) {
                    if (error.status == 404) return@async null
                    throw error
                }
                path.takeIf { matchesRedirect(target, kind, external) }
            } }.awaitAll().filterNotNull() }
            if (confirmed.size > 1) return null
        }
        return confirmed.singleOrNull()
    }

    private class Recent<K, V> {
        private val mutex = Mutex()
        private val entries = LinkedHashMap<K, Pair<Long, V>>(32, .75f, true)
        suspend fun get(key: K, ttl: Long, load: suspend () -> V): V = mutex.withLock {
            entries[key]?.takeIf { System.currentTimeMillis() - it.first < ttl }?.let { return@withLock it.second }
            val result = load()
            entries[key] = System.currentTimeMillis() to result
            if (entries.size > 32) entries.remove(entries.keys.first())
            result
        }
    }

    companion object {
        const val SITE = "https://anime-go.org"
        const val SOURCE = "animego"
        private val link = Regex("""href\s*=\s*["'](/anime/[a-z0-9-]+-\d+)["']""", RegexOption.IGNORE_CASE)
        internal fun searchPaths(html: String) = link.findAll(html).map { it.groupValues[1] }.distinct().take(24).toList()

        internal fun matchesRedirect(raw: String?, kind: String, id: Int): Boolean {
            val url = raw?.toHttpUrlOrNull() ?: return false
            if (url.scheme != "https") return false
            val path = when (kind) {
                "mal" -> url.host in setOf("myanimelist.net", "www.myanimelist.net") && url.pathSegments.firstOrNull() == "anime"
                "shikimori" -> url.host in setOf("shikimori.io", "shikimori.one", "shikimori.me", "shikimori.org") && url.pathSegments.firstOrNull() == "animes"
                else -> false
            }
            return path && Regex("""^[a-zA-Z]?(\d+)(?:-|$)""")
                .find(url.pathSegments.getOrNull(1).orEmpty())?.groupValues?.get(1)?.toIntOrNull() == id
        }

        private data class Variant(val provider: String, val id: String, val name: String, val subtitles: Boolean,
                                   val season: Int, val number: String, val url: String)

        internal fun parsePlayers(json: JSONObject, number: String, titleId: String, typeLabel: String? = null): List<Player> {
            val variants = json.arr("sources").flatMap { source ->
                val provider = source.str("key").orEmpty().lowercase()
                if (source.str("kind") != "iframe" || provider !in setOf("kodik", "aksor")) return@flatMap emptyList()
                source.arr("translations").flatMap voiceLoop@ { voice ->
                    val voiceId = voice.str("id") ?: return@voiceLoop emptyList()
                    val name = voice.str("name") ?: return@voiceLoop emptyList()
                    voice.arr("episodes").mapNotNull { episode ->
                        val season = episode.int("season")?.takeIf { it >= 0 } ?: return@mapNotNull null
                        val num = episode.str("num")?.takeIf { it.toBigDecimalOrNull() != null } ?: return@mapNotNull null
                        val url = episode.str("link")?.let(::fullUrl)?.toHttpUrlOrNull() ?: return@mapNotNull null
                        if (url.scheme != "https") return@mapNotNull null
                        val accepted = if (provider == "aksor") AksorExtractor.supportsUrl(url.toString())
                        else url.host in setOf("kodikplayer.com", "kodik.info", "kodik.biz", "kodik.cc", "kodik.online") &&
                            url.pathSegments.firstOrNull() in setOf("seria", "video")
                        if (!accepted) return@mapNotNull null
                        Variant(provider, voiceId, name, voice.str("type") == "sub", season, num, url.toString())
                    }
                }
            }
            val regular = variants.map { it.season }.toSet() - 0
            // Season zero can appear beside a normal season with overlapping episode numbers.
            val standalone = typeLabel?.trim()?.lowercase() in setOf(
                "movie", "фильм", "полнометражный фильм", "ova", "special", "спецвыпуск", "спешл"
            )
            val season = regular.singleOrNull()
                ?: if (regular.isEmpty() && standalone) 0 else return emptyList()
            return variants.filter { it.season == season && YummyAnime.sameNumber(it.number, number) }
                .groupBy { it.provider to it.id }.values.mapNotNull { rows ->
                    val variant = rows.distinct().singleOrNull() ?: return@mapNotNull null
                    // Negative team IDs in a dedicated band, stable across episodes.
                    val hash = "$titleId:${variant.provider}:${variant.id}".hashCode() and 0x1fffffff
                    val team = Team(-1_000_000_000 - hash, variant.name, variant.subtitles)
                    Player(team, SOURCE, null, emptyMap(), emptyList(), null, null, source = SOURCE,
                        resolverUrl = variant.url, balancer = if (variant.provider == "aksor") "Aksor" else "Kodik")
                }.groupBy { it.team.id }.values.mapNotNull { it.singleOrNull() }
        }
    }
}
