package ru.qmods.anixq.tv.data

import kotlinx.coroutines.launch

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiException(message: String) : Exception(message)

/** AnimeLib (lib.social) API, the same calls the iOS app makes. */
object Api {
    /** All official API addresses; one that fails moves to the back (see [demote]). */
    private val hostOrder = java.util.concurrent.CopyOnWriteArrayList(listOf(
        // AnixQ's own cache on Cloudflare first (edge/worker.js); demoted automatically if unreachable.
        "https://anixq-edge.qmurzik7.workers.dev", "https://api.cdnlibs.org", "https://api.lib.social", "https://api.imglib.info", "https://hapi.hentaicdn.org"))
    private fun demote(h: String) { if (hostOrder.remove(h)) hostOrder.add(h) }
    const val SITE = "https://anilib.me"
    private const val UA = "Mozilla/5.0 (Linux; Android 12; AndroidTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    private val fallbackServers = listOf("https://video1.cdnlibs.org/.аs/", "https://video2.cdnlibs.org/", "https://video1.imglib.info/")

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val cache = HashMap<String, Pair<Long, Any>>()
    /** Set by the app: where answers are saved to open instantly next time. */
    @Volatile var diskDir: java.io.File? = null
    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** How long a saved answer may be shown while a fresh one loads (null = not saved). */
    private fun keepFor(path: String): Long? = when {
        path.startsWith("/anime/") -> 7 * 86_400_000L
        path == "/episodes" -> 30 * 60_000L
        path.startsWith("/episodes/") -> 86_400_000L
        else -> null
    }

    private fun diskFile(key: String): java.io.File? = diskDir?.let { d ->
        java.io.File(d, "api").apply { mkdirs() }.let { java.io.File(it, Integer.toHexString(key.hashCode()) + "_" + key.length + ".json") }
    }

    private fun parse(text: String): Any = when {
        text.isBlank() -> JSONObject()
        text.trimStart().startsWith("[") -> JSONArray(text)
        else -> JSONObject(text)
    }

    private suspend fun request(
        path: String, query: List<Pair<String, String>> = emptyList(), method: String = "GET",
        body: JSONObject? = null, auth: Boolean = true, retried: Boolean = false, fresh: Boolean = false,
    ): Any = withContext(Dispatchers.IO) {
        val key = path + query.joinToString("&") { "${it.first}=${it.second}" } + (if (Store.libToken != null) "#u" else "")
        if (method == "GET" && !fresh) synchronized(cache) { cache[key]?.let { if (System.currentTimeMillis() - it.first < 5 * 60_000) return@withContext it.second } }
        // Seen before: answer from the saved copy at once, refresh it in the background.
        val keep = if (method == "GET") keepFor(path) else null
        if (keep != null && !fresh) diskFile(key)?.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < keep }?.let { f ->
            runCatching { parse(f.readText()) }.getOrNull()?.let { stale ->
                synchronized(cache) { cache[key] = System.currentTimeMillis() to stale }
                bg.launch { runCatching { request(path, query, method, body, auth, retried, fresh = true) } }
                return@withContext stale
            }
        }
        var last: Exception = ApiException("Нет связи с AnimeLib")
        for (host in hostOrder.toList()) {
            try {
                val url = (host + "/api" + path).toHttpUrl().newBuilder().apply {
                    query.forEach { addQueryParameter(it.first, it.second) }
                }.build()
                val rb = Request.Builder().url(url)
                    .header("Site-Id", "5").header("Referer", "$SITE/").header("Origin", SITE)
                    .header("Accept", "application/json").header("User-Agent", UA)
                val token = Store.libToken
                if (auth && token != null) rb.header("Authorization", "Bearer $token")
                when (method) {
                    "GET" -> rb.get()
                    else -> rb.method(method, (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
                }
                http.newCall(rb.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.code == 401 && auth && token != null && !retried) {
                        if (refresh()) return@withContext request(path, query, method, body, auth, true)
                        throw ApiException("Войдите в AnimeLib заново")
                    }
                    // Server error or a protection page (HTML, e.g. DDoS-Guard geo-block): try the next address.
                    val html = resp.header("Content-Type")?.contains("html") == true
                    if (resp.code >= 500 || html) { demote(host); last = ApiException("AnimeLib ответил ${resp.code}"); return@use null }
                    if (resp.code !in 200..299) throw ApiException("AnimeLib ответил ${resp.code}")
                    val parsed: Any = parse(text)
                    if (method == "GET") synchronized(cache) { cache[key] = System.currentTimeMillis() to parsed }
                    if (keep != null) runCatching { diskFile(key)?.writeText(text) }
                    return@withContext parsed
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                last = e
                demote(host)
            }
        }
        throw last
    }

    private suspend fun refresh(): Boolean {
        val r = Store.libRefresh ?: return false
        return try {
            val json = request("/auth/oauth/token", method = "POST", auth = false, retried = true,
                body = JSONObject().put("grant_type", "refresh_token").put("client_id", 1).put("refresh_token", r).put("scope", "")) as JSONObject
            val access = json.str("access_token") ?: return false
            Store.setLib(access, json.str("refresh_token") ?: r, Store.libUser, Store.libUserId)
            true
        } catch (e: Exception) { false }
    }

    /** OAuth code from the AnimeLib sign-in page -> tokens (PKCE, the same client the site uses). */
    suspend fun exchangeCode(code: String, verifier: String, redirect: String): Boolean {
        val json = request("/auth/oauth/token", method = "POST", auth = false, retried = true,
            body = JSONObject().put("grant_type", "authorization_code").put("client_id", "1").put("redirect_uri", redirect)
                .put("code_verifier", verifier).put("code", code)) as? JSONObject ?: return false
        val access = json.str("access_token") ?: return false
        Store.setLib(access, json.str("refresh_token"), null, null)
        loadProfile()
        return true
    }

    private fun data(any: Any): Any? = (any as? JSONObject)?.opt("data")

    fun clearCache() = synchronized(cache) { cache.clear() }

    // Catalog

    suspend fun catalog(q: CatalogQuery, page: Int): Pair<List<Title>, Boolean> {
        val params = mutableListOf("site_id[]" to "5", "page" to page.toString())
        if (q.text.isBlank()) { params += "sort_by" to q.sort.key; params += "sort_type" to "desc" } else params += "q" to q.text
        q.status?.let { params += "status[]" to it.toString() }
        q.type?.let { params += "types[]" to it.toString() }
        q.genre?.let { params += "genres[]" to it.toString() }
        val json = request("/anime", params) as JSONObject
        val items = (json.optJSONArray("data") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Title::fromApi) } }
        val more = json.obj("meta")?.optBoolean("has_next_page", items.size >= 20) ?: (items.size >= 20)
        return items to more
    }

    suspend fun search(text: String) = catalog(CatalogQuery(text = text), 1).first

    suspend fun genres(): List<Pair<Int, String>> = try {
        val d = data(request("/constants", listOf("fields[]" to "genres"))) as? JSONObject
        d?.arr("genres")?.mapNotNull { g -> g.int("id")?.let { it to (g.str("name") ?: "") } } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    suspend fun detail(slug: String): Detail {
        val fields = listOf("background", "summary", "releaseDate", "genres").map { "fields[]" to it }
        val d = data(request("/anime/$slug", fields)) as? JSONObject ?: throw ApiException("Не найдено")
        val title = Title.fromApi(d) ?: throw ApiException("Не найдено")
        val bg = d.obj("background")?.str("url")?.takeIf { it.startsWith("http") && !it.contains("/placeholders/") }
        return Detail(title, flatten(d.opt("summary")).trim().ifEmpty { null }, bg, d.arr("genres").mapNotNull { it.str("name") })
    }

    private fun flatten(node: Any?): String = when (node) {
        is String -> node
        is JSONObject -> node.str("text") ?: run {
            val c = node.optJSONArray("content")
            val joined = if (c == null) "" else (0 until c.length()).joinToString("") { flatten(c.opt(it)) }
            if (node.str("type") == "paragraph") joined + "\n\n" else joined
        }
        else -> ""
    }

    /** Other parts of the franchise (sequels, films, OVA) with how they relate. */
    suspend fun related(slug: String): List<Pair<Title, String>> = try {
        val a = data(request("/anime/$slug/relations")) as? JSONArray ?: JSONArray()
        val seen = HashSet<Int>()
        (0 until a.length()).mapNotNull { i ->
            val r = a.optJSONObject(i) ?: return@mapNotNull null
            val m = r.obj("media")?.takeIf { it.str("model") == "anime" } ?: return@mapNotNull null
            val t = Title.fromApi(m)?.takeIf { seen.add(it.id) } ?: return@mapNotNull null
            t to (r.obj("related_type")?.str("label") ?: "")
        }
    } catch (e: Exception) { emptyList() }

    /** AnimeLib comments: [type] "anime" for a title page, "episodes" for one episode. */
    data class Comment(val id: Int, val rootId: Int?, val text: String, val date: Long, val user: String, val avatar: String?,
                       val up: Int, val down: Int, val spoiler: Boolean)
    data class Comments(val root: List<Comment>, val replies: List<Comment>, val hasMore: Boolean)

    suspend fun comments(postId: Int, type: String, page: Int): Comments {
        val json = request("/comments", listOf("post_id" to postId.toString(), "post_type" to type, "page" to page.toString()), auth = false) as? JSONObject
            ?: return Comments(emptyList(), emptyList(), false)
        val d = json.obj("data")
        fun parse(x: JSONObject): Comment? {
            val id = x.int("id") ?: return null
            val html = x.str("comment") ?: ""
            val u = x.obj("user")
            return Comment(id, x.int("root_id"), ShikiExtras.plain(html), (x.dbl("created_at_ts") ?: 0.0).toLong(), u?.str("username") ?: "—",
                u?.obj("avatar")?.str("url"), x.obj("votes")?.int("up") ?: 0, x.obj("votes")?.int("down") ?: 0, html.contains("spoiler"))
        }
        return Comments(d?.arr("root")?.mapNotNull(::parse) ?: emptyList(), d?.arr("replies")?.mapNotNull(::parse) ?: emptyList(),
            json.obj("meta")?.optBoolean("has_next_page") ?: false)
    }

    suspend fun similar(slug: String): List<Title> = try {
        val a = data(request("/anime/$slug/similar")) as? JSONArray ?: JSONArray()
        val seen = HashSet<Int>()
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.obj("media")?.let(Title::fromApi)?.takeIf { seen.add(it.id) } }
    } catch (e: Exception) { emptyList() }

    suspend fun episodes(slug: String): List<Episode> {
        val a = data(request("/episodes", listOf("anime_id" to slug))) as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val x = a.optJSONObject(i) ?: return@mapNotNull null
            Episode(x.int("id") ?: return@mapNotNull null, x.str("number") ?: (i + 1).toString(), x.str("name"), i)
        }
    }

    suspend fun players(episodeId: Int): List<Player> {
        val d = data(request("/episodes/$episodeId")) as? JSONObject ?: return emptyList()
        return d.arr("players").mapNotNull { p ->
            val t = p.obj("team") ?: return@mapNotNull null
            val team = Team(t.int("id") ?: return@mapNotNull null, t.str("name") ?: "Команда", p.obj("translation_type")?.int("id") == 1)
            val hrefs = LinkedHashMap<String, String>()
            p.obj("video")?.arr("quality")?.forEach { q -> q.str("href")?.let { hrefs[q.int("quality")?.let { "${it}p" } ?: "auto"] = it } }
            val tc = p.opt("timecode")
            val subs = p.arr("subtitles").mapNotNull { x ->
                val src = (x.str("src") ?: x.str("url"))?.let { if (it.startsWith("//")) "https:$it" else it } ?: return@mapNotNull null
                Subtitle(src, (x.str("format") ?: src.substringAfterLast('.')).lowercase(), x.str("name") ?: x.str("label") ?: "Субтитры")
            }
            Player(team, p.str("player") ?: "?", p.str("src"), hrefs, parseSegments(tc), p.int("id"),
                (tc as? Number)?.toDouble() ?: (tc as? JSONObject)?.let { it.dbl("timecode") ?: it.dbl("time") }, subs)
        }
    }

    private fun parseSegments(any: Any?): List<Segment> {
        fun sec(v: Any?): Double? = when (v) {
            is Number -> v.toDouble()
            is String -> v.split(":").mapNotNull { it.toDoubleOrNull() }.takeIf { it.isNotEmpty() }?.fold(0.0) { a, b -> a * 60 + b }
            else -> null
        }
        fun seg(kind: String, x: JSONObject): Segment? {
            val from = sec(x.opt("from") ?: x.opt("start")) ?: return null
            val to = sec(x.opt("to") ?: x.opt("end")) ?: return null
            return if (to > from) Segment(kind, from, to) else null
        }
        return when (any) {
            is JSONArray -> (0 until any.length()).mapNotNull { i -> any.optJSONObject(i)?.let { seg(it.str("type") ?: it.str("name") ?: "segment", it) } }
            is JSONObject -> any.keys().asSequence().mapNotNull { k -> any.optJSONObject(k)?.let { seg(k, it) } }.toList()
            else -> emptyList()
        }
    }

    // Video mirrors

    private var servers: List<String>? = null
    private var ranked: Pair<Long, List<String>>? = null

    suspend fun videoServers(): List<String> {
        servers?.let { return it }
        val list = try {
            (data(request("/constants", listOf("fields[]" to "videoServers"))) as? JSONObject)?.arr("videoServers")?.mapNotNull { it.str("url") }
        } catch (e: Exception) { null }
        return (list?.takeIf { it.isNotEmpty() } ?: fallbackServers).also { servers = it }
    }

    fun videoUrl(server: String, href: String): String =
        (if (server.endsWith("/")) server else "$server/") + href.removePrefix("/")

    /** Mirrors with the first one that answered a small range request first. */
    suspend fun rankServers(href: String): List<String> = coroutineScope {
        val all = videoServers()
        ranked?.let { if (System.currentTimeMillis() - it.first < 20 * 60_000) return@coroutineScope it.second }
        val results = all.map { s ->
            async(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                val ok = withTimeoutOrNull(6000) {
                    try {
                        http.newCall(Request.Builder().url(videoUrl(s, href)).header("Range", "bytes=0-65535")
                            .header("Referer", "$SITE/").build()).execute().use { it.code == 200 || it.code == 206 }
                    } catch (e: Exception) { false }
                } ?: false
                Triple(s, ok, System.currentTimeMillis() - t0)
            }
        }.awaitAll()
        val order = results.sortedWith(compareBy({ !it.second }, { it.third })).map { it.first }
        ranked = System.currentTimeMillis() to order
        order
    }

    fun kodikUrl(src: String): String {
        val full = if (src.startsWith("//")) "https:$src" else src
        return full + (if (full.contains("?")) "&" else "?") + "translations=false"
    }

    // Account progress

    suspend fun saveTimecode(episodeId: Int, playerId: Int?, seconds: Double) {
        if (Store.libToken == null) return
        try {
            request("/episodes/timecodes", method = "PUT",
                body = JSONObject().put("episode_id", episodeId).put("timecode", seconds.toInt()).apply { playerId?.let { put("player_id", it) } })
        } catch (e: Exception) { Log.d("AnixQ", "timecode: ${e.message}") }
    }

    suspend fun markViewed(episodeId: Int, playerId: Int?) {
        if (Store.libToken == null) return
        try {
            request("/episodes/views", method = "PUT",
                body = JSONObject().put("episode_id", episodeId).apply { playerId?.let { put("player_id", it) } })
        } catch (e: Exception) { Log.d("AnixQ", "views: ${e.message}") }
    }

    suspend fun loadProfile() {
        try {
            val d = data(request("/auth/me")) as? JSONObject ?: return
            Store.setLib(Store.libToken, Store.libRefresh, d.str("username"), d.int("id"))
        } catch (_: Exception) {}
    }
}
