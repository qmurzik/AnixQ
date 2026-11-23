// Adapted for AnixQ from YummyTV API models (CC BY-NC 4.0; assets/licenses/YummyTV-NOTICE).
package ru.qmods.anixq.tv.data.sources

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import ru.qmods.anixq.tv.data.*

class YummyAnime(private val http: SourceHttp = SourceHttp(), private val base: String = "https://api.yani.tv") : AnimeCatalog, EpisodeSource {
    private val mutex = Mutex()
    private val registrationMutex = Mutex()
    var managedApplicationOwner: Int? = null
    var applicationReceipts: YummyApplicationReceipts? = null
    private val pendingApps = HashMap<Int, Int>()
    private val cache = LinkedHashMap<String, Triple<Long, JSONObject, Int>>()
    /** Only caller-owned application credentials; some providers require a user session. */
    var applicationToken: String? = ru.qmods.anixq.core.BuildConfig.YANI_APPLICATION_TOKEN.takeIf { it.isNotBlank() }
        set(value) { field = value; synchronized(cache) { cache.clear() } }
    @Volatile var userToken: String? = null
        set(value) { field = value; synchronized(cache) { cache.clear() }; refreshedAt = System.currentTimeMillis() }
    fun restoreUserToken(token: String?, savedAt: Long) { userToken = token; refreshedAt = savedAt }
    var onTokenChanged: ((String?) -> Unit)? = null
    private var refreshedAt = 0L
    private fun headers(token: String? = userToken): Map<String, String> = buildMap {
        put("Accept", "application/json"); put("Lang", "ru")
        applicationToken?.takeIf { it.isNotBlank() }?.let { put("X-Application", it) }
        token?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
    }
    suspend fun login(email: String, password: String): String {
        val body = JSONObject().put("login", email).put("password", password).put("need_json", true)
        val response = JSONObject(http.request("$base/profile/login", headers(null), body.toString().toRequestBody("application/json".toMediaType())).text).obj("response")
        return response?.str("token")?.takeIf { response.optBoolean("success") } ?: error("YummyAnime: не удалось войти")
    }
    suspend fun verifyToken(token: String) { profileId(token) }
    private suspend fun profileId(token: String): Int {
        val profile = JSONObject(http.request("$base/profile", headers(token)).text).obj("response")
        val id = profile?.int("id") ?: 0
        check(id > 0) { "YummyAnime: токен не подтверждён" }
        return id
    }
    /** Sets up only the signed-in user's own AnixQ app; never borrows another owner's key. */
    suspend fun connectApplication(token: String): YummyConnection = registrationMutex.withLock {
        val owner = profileId(token)
        applicationToken?.takeIf { it.isNotBlank() && (managedApplicationOwner == null || managedApplicationOwner == owner) }?.let {
            return@withLock YummyConnection(it, managedApplicationOwner)
        }
        val auth = headers(token).filterKeys { !it.equals("X-Application", true) }
        suspend fun appRequest(path: String, body: JSONObject? = null): JSONObject = try {
            JSONObject(http.request("$base$path", auth, body?.toString()?.toRequestBody("application/json".toMediaType())).text)
        } catch (e: SourceHttp.HttpError) {
            if (e.status == 401 || e.status == 403) error("YummyAnime не разрешил автоматическую настройку. Откройте Дополнительно → Ключ приложения")
            throw e
        }
        suspend fun detail(id: Int): String {
            val app = appRequest("/apps/$id").obj("response") ?: error("YummyAnime: нет данных приложения")
            check(app.obj("owner")?.int("id") == owner) { "YummyAnime: приложение принадлежит другому аккаунту" }
            return app.str("public_token") ?: error("YummyAnime: публичный ключ не получен")
        }
        var id = applicationReceipts?.get(owner) ?: pendingApps[owner]
        if (id != null) {
            try { return@withLock YummyConnection(detail(id), owner) }
            catch (e: SourceHttp.HttpError) { if (e.status != 404) throw e; id = null }
        }
        // Finish pagination before creating; a truncated list must not cause duplicate apps.
        var offset = 0
        while (id == null) {
            val page = appRequest("/apps?limit=100&offset=$offset")
            check(page.optJSONArray("response") != null) { "YummyAnime: неверный список приложений" }
            val list = page.arr("response")
            id = list.filter { it.obj("owner")?.int("id") == owner && it.str("name") == "AnixQ" }.mapNotNull { it.int("app_id") }.minOrNull()
            if (id != null || list.size < 100) break
            offset += 100
            check(offset < 1000) { "YummyAnime: слишком много приложений. Настройте ключ в дополнительных настройках" }
        }
        if (id == null) {
            val body = JSONObject().put("name", "AnixQ").put("description", "AnixQ for Android and Android TV. Personal catalogue and playback access.")
            id = appRequest("/apps", body).obj("response")?.int("app_id")?.takeIf { it > 0 } ?: error("YummyAnime: не удалось зарегистрировать приложение")
        }
        pendingApps[owner] = id
        applicationReceipts?.save(owner, id)
        YummyConnection(detail(id), owner)
    }
    suspend fun logout() {
        try { http.request("$base/profile/logout", headers(), "{}".toRequestBody("application/json".toMediaType())) }
        finally { userToken = null; onTokenChanged?.invoke(null) }
    }
    private suspend fun refreshToken() {
        check(!userToken.isNullOrBlank()) { "Войдите в YummyAnime" }
        val token = JSONObject(http.request("$base/profile/token", headers()).text).obj("response")?.str("token")
            ?: error("YummyAnime: войдите заново")
        onTokenChanged?.invoke(token); userToken = token
    }
    private suspend fun get(path: String, params: List<Pair<String, String>> = emptyList()): JSONObject {
        val url = (base + path).toHttpUrl().newBuilder().apply { params.forEach { addQueryParameter(it.first, it.second) } }.build().toString()
        return mutex.withLock {
            synchronized(cache) { cache[url]?.takeIf { System.currentTimeMillis() - it.first < 120_000 }?.second }?.let { return@withLock it }
            if (userToken != null && System.currentTimeMillis() - refreshedAt > 2 * 86_400_000L) refreshToken()
            val reply = try { http.request(url, headers()) } catch (e: SourceHttp.HttpError) {
                if (e.status != 401 || userToken == null) throw e
                try { refreshToken() } catch (failure: SourceHttp.HttpError) {
                    if (failure.status == 401) { userToken = null; onTokenChanged?.invoke(null) }
                    throw failure
                }
                http.request(url, headers())
            }
            val json = JSONObject(reply.text)
            check(json.has("response")) { "YummyAnime: неверный формат ответа" }
            synchronized(cache) { cache[url] = Triple(System.currentTimeMillis(), json, reply.text.length * 2); while (cache.size > 32 || (cache.size > 1 && cache.values.sumOf { it.third } > 8 * 1024 * 1024)) cache.remove(cache.keys.first()) }
            json
        }
    }
    override suspend fun catalog(query: CatalogQuery, page: Int): Pair<List<Title>, Boolean> {
        val items = get("/anime", parameters(query, page)).arr("response").mapNotNull(::parseTitle)
        return items to (items.size == PAGE_SIZE)
    }
    override suspend fun detail(id: Int): Detail {
        val x = get("/anime/$id").obj("response") ?: error("YummyAnime: тайтл не найден")
        return Detail(parseTitle(x) ?: error("YummyAnime: тайтл без ID"), x.str("description"), null, x.arr("genres").mapNotNull { it.str("title") })
    }
    suspend fun byRemoteId(shikiId: Int?, malId: Int?): List<Title> {
        val query = if (malId != null) "mal_ids" to malId.toString() else "shikimori_ids" to (shikiId ?: return emptyList()).toString()
        return get("/anime", listOf(query, "limit" to "100")).arr("response").mapNotNull(::parseTitle)
    }
    suspend fun schedule(): List<Title> = get("/anime/schedule").arr("response").mapNotNull(::parseTitle)
    override suspend fun genres(): List<Pair<Int, String>> = get("/anime/genres").obj("response")?.arr("genres")?.mapNotNull {
        val id = it.int("value") ?: return@mapNotNull null
        id to (it.str("title") ?: return@mapNotNull null)
    }.orEmpty()
    suspend fun videos(id: Int): List<YummyVideo> {
        val videos = parseVideos(get("/anime/$id/videos"))
        if (!userToken.isNullOrBlank() && !applicationToken.isNullOrBlank()) return videos
        // Public Aksor endpoints currently expose real episodes. Other balancers may serve
        // anonymous placeholders: keep their existing account requirement, never borrow a key.
        val public = videos.filter { ru.qmods.anixq.tv.data.sources.extractors.AksorExtractor.supportsUrl(it.url) }
        check(public.isNotEmpty() || videos.isEmpty()) {
            "Для этих озвучек YummyAnime войдите в аккаунт в настройках. Без входа балансеры могут возвращать заглушки."
        }
        return public
    }
    override suspend fun episodes(id: Int): List<Episode> = videos(id).groupBy { it.number.toBigDecimal().stripTrailingZeros().toPlainString() }.entries
        .sortedBy { it.key.toDoubleOrNull() ?: Double.MAX_VALUE }.mapIndexed { index, (number, videos) ->
            Episode(SourceIds.yummy(videos.minOf { it.id }), number, null, index)
        }
    override suspend fun players(id: Int, number: String): List<Player> = videos(id).filter { sameNumber(it.number, number) }.map { v ->
        // video_id is unique per episode/voice/balancer, unlike dubbing labels and player_id.
        Player(Team(SourceIds.yummy(v.id), v.dubbing, Regex("(?iu)^(субтитры|subtitles)\\s").containsMatchIn(v.dubbing)), "yummy", null, emptyMap(), v.segments, null, null,
            source = "yummy", headers = emptyMap(), resolverUrl = v.url, balancer = v.balancer)
    }
    companion object {
        const val PAGE_SIZE = 30
        fun sameNumber(a: String, b: String): Boolean {
            if (a == b) return true
            val x = a.toBigDecimalOrNull() ?: return false
            val y = b.toBigDecimalOrNull() ?: return false
            return x.compareTo(y) == 0
        }
        fun parameters(q: CatalogQuery, page: Int): List<Pair<String, String>> = buildList {
            add("limit" to PAGE_SIZE.toString()); add("offset" to ((page.coerceAtLeast(1) - 1) * PAGE_SIZE).toString())
            if (q.text.isNotBlank()) add("q" to q.text)
            add("sort" to when(q.sort) { CatalogSort.POPULAR -> "views"; CatalogSort.RATING -> "rating"; CatalogSort.UPDATED -> "year"; CatalogSort.NEWEST -> "id" })
            add("sort_forward" to "false")
            q.status?.let { add("status" to when(it) { 1 -> "1"; 2 -> "0"; else -> "2" }) }
            q.type?.let { type -> mapOf(16 to "tv", 17 to "movie", 20 to "ova", 21 to "ona", 19 to "special", 18 to "shorttv")[type]?.let { add("types" to it.toString()) } }
            q.genre?.let { add("genres" to it.toString()) }
            q.episodesMin?.let { add("ep_from" to it.toString()) }; q.episodesMax?.let { add("ep_to" to it.toString()) }
            q.yearFrom?.let { add("from_year" to it.toString()) }; q.yearTo?.let { add("to_year" to it.toString()) }
        }
        fun parseTitle(x: JSONObject): Title? {
            val id = x.int("anime_id")?.takeIf(SourceIds::validYummyId) ?: return null
            val eps = x.obj("episodes")
            return Title(SourceIds.yummy(id), "yummy:$id", rusName = x.str("title"),
                cover = x.obj("poster")?.let { it.str("big") ?: it.str("fullsize") }?.let(::fullUrl),
                coverThumb = x.obj("poster")?.str("small")?.let(::fullUrl), rating = x.obj("rating")?.dbl("average"),
                shikiId = x.obj("remote_ids")?.int("shikimori_id")?.takeIf { it > 0 },
                typeLabel = x.obj("type")?.str("shortname"), statusId = when(x.obj("anime_status")?.str("alias")) { "ongoing" -> 1; "released" -> 2; "anons", "announce", "announcement" -> 3; else -> null },
                year = x.int("year"), age = x.obj("min_age")?.str("title"),
                malId = x.obj("remote_ids")?.int("myanimelist_id")?.takeIf { it > 0 },
                episodeCount = eps?.int("count"), episodesAired = eps?.int("aired"), nextEpisodeAt = eps?.dbl("next_date"))
        }
        fun parseVideos(json: JSONObject): List<YummyVideo> = json.arr("response").mapNotNull { x ->
            val id = x.int("video_id")?.takeIf(SourceIds::validYummyId) ?: return@mapNotNull null
            val number = x.str("number")?.takeIf { it.toBigDecimalOrNull() != null } ?: return@mapNotNull null
            val url = x.str("iframe_url")?.let(::fullUrl)?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return@mapNotNull null
            val data = x.obj("data") ?: return@mapNotNull null
            // Marks come as {"time": start, "length": seconds} (older answers: [start, end]), for this very file.
            val length = x.optDouble("duration", 0.0).takeIf { it > 0 } ?: 0.0
            val segments = listOf("opening", "ending").mapNotNull { kind ->
                val skips = x.obj("skips") ?: return@mapNotNull null
                val (start, end) = skips.optJSONObject(kind)?.let { o ->
                    val t = o.optDouble("time", -1.0); t to t + o.optDouble("length", 0.0)
                } ?: skips.optJSONArray(kind)?.let { a -> a.optDouble(0, -1.0) to a.optDouble(1, -1.0) } ?: return@mapNotNull null
                if (start >= 0 && end > start) Segment(kind, start, end, length) else null
            }
            YummyVideo(id, number, data.str("dubbing") ?: "Оригинал", data.str("player") ?: "Балансер", url, segments)
        }.distinctBy { it.id }
    }
}
data class YummyVideo(val id: Int, val number: String, val dubbing: String, val balancer: String, val url: String, val segments: List<Segment>)
