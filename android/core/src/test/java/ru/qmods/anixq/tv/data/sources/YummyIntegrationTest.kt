package ru.qmods.anixq.tv.data.sources

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.data.sources.extractors.*

class YummyIntegrationTest {
    private fun fixture(name: String) = javaClass.getResource("/yummy/$name.json")!!.readText()
    @Test fun realCatalogFixtureHasSeparateIdentityAndRoundTripsSync() {
        val title = YummyAnime.parseTitle(JSONObject(fixture("catalog")).arr("response").single())!!
        assertEquals(-45, title.id)
        assertEquals("yummy:45", title.slug)
        assertEquals(16498, title.malId)
        assertEquals(16498, title.shikiId)
        assertEquals(title, Title.fromSync(title.toSync()))
        assertEquals(45, SourceIds.yummyRemote(title.id))
        assertNotEquals(Title(45, "45--test").id, title.id)
    }
    @Test fun shikimoriUrlDecoratorDoesNotBecomePartOfTheExternalId() {
        val title = Title.fromApi(JSONObject("""{"id":1,"slug_url":"1--test","shikimori_href":"https://shikimori.one/animes/z16498-shingeki-no-kyojin"}"""))
        assertEquals(16498, title?.shikiId)
    }
    @Test fun sameNameIsNeverSufficientForMatching() {
        val lib = Title(1, "1--test", name = "Season", shikiId = 100, year = 2020, typeLabel = "TV")
        val candidate = Title(-5, "yummy:5", name = "Season", shikiId = 100, malId = 100, year = 2020, typeLabel = "TV")
        assertTrue(AnimeSources.safelyMatches(lib, candidate))
        assertFalse(AnimeSources.safelyMatches(lib, candidate.copy(malId = 101)))
        assertFalse(AnimeSources.safelyMatches(lib, candidate.copy(shikiId = null)))
        assertFalse(AnimeSources.safelyMatches(lib, candidate.copy(year = 2021)))
        assertFalse(AnimeSources.safelyMatches(lib, candidate.copy(typeLabel = "OVA")))
        assertFalse(AnimeSources.safelyMatches(lib.copy(shikiId = null), candidate))
        assertTrue(AnimeSources.safelyMatches(lib.copy(malId = 100, shikiId = 99), candidate))
    }
    @Test fun subtitlesRetainTheirTypeWhileLabelsStayShort() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"response":[{"video_id":1,"number":"1","iframe_url":"https://player.aksor.tv/video/sub","data":{"player":"Плеер Aksor","dubbing":"Субтитры FSG YakuSub Studio"}}]}"""))
            val api = YummyAnime(SourceHttp(),server.url("/").toString().trimEnd('/')).apply { applicationToken="my-app"; userToken="my-session" }
            val player = api.players(45,"1").single()
            assertTrue(player.team.isSubtitles)
            assertEquals("FSG YakuSub Studio", PlaybackLabels.dub(player.team.name))
        }
    }
    @Test fun aQualityChangeCanReuseTheCurrentResolvedStreamWithoutNetwork() = runBlocking {
        val unresolved = Player(Team(-1,"Dub",false),"yummy",null,emptyMap(),emptyList(),null,null,source="yummy",resolverUrl="https://unsupported.example.test/frame")
        val resolved = unresolved.copy(player="animelib",hrefs=mapOf("720p" to "https://cdn.example.test/video.mp4"))
        val title = Title(1,"1--test"); val episode = Episode(2,"1",null,0)
        assertSame(resolved,AnimeSources.resolve(unresolved,title,episode,cached=resolved))
        assertNotNull(runCatching { AnimeSources.resolve(unresolved.copy(team=unresolved.team.copy(id=-2)),title,episode,cached=resolved) }.exceptionOrNull())
    }
    @Test fun videoVariantsRetainTheirOwnEpisodeDubAndBalancer() {
        val videos = YummyAnime.parseVideos(JSONObject(fixture("videos")))
        assertEquals(listOf("1", "2"), videos.map { it.number })
        assertTrue(videos.all { it.balancer.contains("Aksor") && it.dubbing == "Арт-Дубляж" })
        assertEquals(2, videos.map { it.id }.distinct().size)
        assertTrue(YummyAnime.sameNumber("01.0", "1"))
        assertFalse(YummyAnime.sameNumber("1.5", "1"))
        assertFalse(YummyAnime.sameNumber("OVA", "1"))
    }
    @Test fun filtersUseYummyNamespacesAndOffsetRatherThanAnimeLibParameters() {
        val params = YummyAnime.parameters(CatalogQuery(source = "yummy", text = "test", genre = 8, status = 2,
            type = 20, yearFrom = 2020, episodesMax = 13, sort = CatalogSort.RATING), 2).toMap()
        assertEquals("30", params["offset"])
        assertEquals("ova", params["types"])
        assertEquals("0", params["status"])
        assertEquals("rating", params["sort"])
        assertEquals("13", params["ep_to"])
        assertFalse(params.containsKey("genres[]"))
    }
    @Test fun concurrentCatalogueReadsCoalesceAndNeverSendAnimeLibCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture("catalog")))
            val api = YummyAnime(SourceHttp(), server.url("/").toString().trimEnd('/'))
            api.applicationToken = "my-own-test-application"
            val results = coroutineScope { List(8) { async { api.catalog(CatalogQuery(), 1).first } }.awaitAll() }
            assertTrue(results.all { it.single().id == -45 })
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("my-own-test-application", request.getHeader("X-Application"))
            assertEquals("ru", request.getHeader("Lang"))
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Referer"))
            assertNull(request.getHeader("Cookie"))
        }
    }
    @Test fun httpErrorsDoNotBecomeEmptySuccessfulCatalogues() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("maintenance"))
            val api = YummyAnime(SourceHttp(), server.url("/").toString().trimEnd('/'))
            val failure = runCatching { api.catalog(CatalogQuery(), 1) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("503"))
            server.enqueue(MockResponse().setBody(fixture("catalog")))
            assertEquals(-45, api.catalog(CatalogQuery(), 1).first.single().id)
        }
    }
    @Test fun videosRequireBothApplicationAndPersonalAuthorization() = runBlocking {
        MockWebServer().use { server ->
            val api = YummyAnime(SourceHttp(), server.url("/").toString().trimEnd('/'))
            api.applicationToken = "my-app"
            assertTrue(runCatching { api.videos(45) }.exceptionOrNull()?.message.orEmpty().contains("войдите"))
            assertEquals(0, server.requestCount)
            api.userToken = "my-account"
            server.enqueue(MockResponse().setBody(fixture("videos")))
            assertEquals(2, api.videos(45).size)
            val request = server.takeRequest()
            assertEquals("Bearer my-account", request.getHeader("Authorization"))
            assertEquals("my-app", request.getHeader("X-Application"))
        }
    }
    @Test fun restoredOldSessionRefreshesBeforeItsFirstCatalogRead() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"response":{"token":"fresh-personal-token"}}"""))
            server.enqueue(MockResponse().setBody(fixture("catalog")))
            val api = YummyAnime(SourceHttp(), server.url("/").toString().trimEnd('/'))
            api.applicationToken = "my-app"
            api.restoreUserToken("old-personal-token", System.currentTimeMillis() - 3 * 86_400_000L)
            api.catalog(CatalogQuery(), 1)
            assertEquals("/profile/token", server.takeRequest().path)
            assertEquals("Bearer fresh-personal-token", server.takeRequest().getHeader("Authorization"))
        }
    }
    @Test fun officialLoginUsesJsonAndReturnsOnlyTheUsersToken() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"response":{"success":true,"token":"my-personal-token"}}"""))
            val api = YummyAnime(SourceHttp(), server.url("/").toString().trimEnd('/'))
            api.applicationToken = "my-own-app"
            assertEquals("my-personal-token", api.login("test@example.test", "test-password"))
            val request = server.takeRequest()
            assertEquals("/profile/login", request.path)
            assertEquals("POST", request.method)
            assertNull(request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertTrue(body.getBoolean("need_json"))
            assertEquals("test@example.test", body.getString("login"))
        }
    }
    @Test fun expiredUserTokenIsRefreshedOnceAndCatalogRequestRetried() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"response":{"token":"refreshed-personal-token"}}"""))
            server.enqueue(MockResponse().setBody(fixture("catalog")))
            val api = YummyAnime(SourceHttp(), server.url("/").toString().trimEnd('/'))
            api.applicationToken = "my-app"; api.userToken = "expired-personal-token"
            var saved: String? = null; api.onTokenChanged = { saved = it }
            assertEquals(-45, api.catalog(CatalogQuery(), 1).first.single().id)
            assertEquals("Bearer expired-personal-token", server.takeRequest().getHeader("Authorization"))
            assertEquals("/profile/token", server.takeRequest().path)
            assertEquals("Bearer refreshed-personal-token", server.takeRequest().getHeader("Authorization"))
            assertEquals("refreshed-personal-token", saved)
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun aksorResolvesAllObservedQualitiesWithHostSpecificHeaders() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture("aksor")))
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val url = "https://player.aksor.tv/video/test-hash"
            val stream = AksorExtractor(SourceHttp(client)).resolve(url)
            assertEquals(setOf("1080p"), stream.qualities.keys) // live fixture: all other fields are null
            assertEquals(url, stream.headers["Referer"])
            assertNull(stream.headers["Origin"])
            assertTrue(stream.qualities.values.all { !it.contains(" ") && it.contains("%20") })
            assertEquals("/api/video/test-hash", server.takeRequest().path)
            assertEquals(1440, qualityHeight("2K")); assertEquals(2160, qualityHeight("4K"))
            assertEquals(Int.MAX_VALUE, qualityHeight("auto"))
        }
    }
    @Test fun aksorFailureCanResolveFromMetaWithoutCallingOtherExtractors() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("<meta name='video_url' content='https://cdn.example.test/master.m3u8'>"))
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val stream = AksorExtractor(SourceHttp(client)).resolve("https://player.aksor.tv/video/hash")
            assertEquals("https://cdn.example.test/master.m3u8", stream.qualities["auto"])
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun kodikUsesItsPageCookieOnlyForItsOwnEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().addHeader("Set-Cookie", "session=kodik-test; Path=/; Secure").setBody("""
                <script src="/assets/js/app.player_single.js"></script>
                <script>var urlParams = '{"d":"x","d_sign":"s","pd":"p","pd_sign":"s","ref":"https%3A%2F%2Fyani.tv","ref_sign":"s"}';
                videoInfo.type = 'seria'; videoInfo.hash = 'hash'; videoInfo.id = '1';</script>
            """.trimIndent()))
            server.enqueue(MockResponse().setBody("// player script; default endpoint /ftor"))
            server.enqueue(MockResponse().setBody("""{"links":{"720":[{"src":"//cdn.example.test/720.mp4:hls:manifest.m3u8"}]}}"""))
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val stream = KodikExtractor(SourceHttp(client)).resolve("https://kodikplayer.com/video/test")
            assertEquals("https://cdn.example.test/720.mp4:hls:manifest.m3u8", stream.qualities["720p"])
            assertNull(server.takeRequest().getHeader("Cookie"))
            assertNull(server.takeRequest().getHeader("Cookie"))
            val post = server.takeRequest()
            assertEquals("/ftor", post.path)
            assertEquals("session=kodik-test", post.getHeader("Cookie"))
            assertFalse(stream.headers.containsKey("Cookie"))
            assertFalse(stream.headers.containsKey("Authorization"))
        }
    }
    @Test fun cvhApiFlowIsIndependentAndDoesNotReuseKodikCookies() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"isSerial":true,"items":[{"vkId":"vk2","episode":2,"voiceStudio":"Dub"}]}"""))
            server.enqueue(MockResponse().setBody("""{"sources":{"mpegHighUrl":"https://cdn.example.test/720.mp4"}}"""))
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath + "?" + chain.request().url.encodedQuery.orEmpty())).build())
            }.build()
            val stream = CvhExtractor(SourceHttp(client)).resolve("https://ru.yummyani.me/iframeCVH.html?anime_id=31240&episode=2&dubbing_code=Dub")
            assertEquals("https://cdn.example.test/720.mp4", stream.qualities["720p"])
            assertNull(stream.headers["Referer"])
            assertNull(stream.headers["Cookie"])
            assertTrue(server.takeRequest().path!!.startsWith("/api/v1/player/sv/playlist?pub=745"))
            assertTrue(server.takeRequest().path!!.startsWith("/api/v1/player/sv/video/vk2"))
        }
    }
    @Test fun cancellingARequestCancelsTheUnderlyingOkHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeadersDelay(2, java.util.concurrent.TimeUnit.SECONDS).setBody("late"))
            val client = OkHttpClient()
            val job = async(Dispatchers.Default) { SourceHttp(client).request(server.url("/slow").toString()) }
            assertNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            server.enqueue(MockResponse().setBody("fresh"))
            assertEquals("fresh", SourceHttp(client).request(server.url("/new").toString()).text)
        }
    }
    @Test fun cvhDoesNotChooseDifferentEpisodeOrDub() {
        val playlist = JSONObject("""{"isSerial":true,"items":[
            {"episode":1,"voiceStudio":"A","vkId":"first"},
            {"episode":2,"voiceStudio":"A","vkId":"wanted"},
            {"episode":2,"voiceStudio":"B","vkId":"other"}]}""")
        assertEquals("wanted", CvhExtractor.select(playlist, "2", "A", "")?.str("vkId"))
        assertNull(CvhExtractor.select(playlist, "3", "A", ""))
        assertNull(CvhExtractor.select(playlist, "2", "C", ""))
        assertNull(CvhExtractor.select(playlist, "2", "", ""))
        val seasons = JSONObject("""{"isSerial":true,"items":[{"season":1,"episode":1,"voiceStudio":"A","vkId":"s1"},{"season":2,"episode":1,"voiceStudio":"A","vkId":"s2"}]}""")
        assertNull(CvhExtractor.select(seasons, "1", "A", ""))
        assertEquals("s2", CvhExtractor.select(seasons, "1", "A", "", "2")?.str("vkId"))
        val movie = JSONObject("""{"isSerial":false,"items":[{"voiceStudio":"A","vkId":"movie"}]}""")
        assertEquals("movie", CvhExtractor.select(movie, "1", "A", "")?.str("vkId"))
        assertNull(CvhExtractor.select(movie, "2", "A", ""))
    }
    @Test fun highResolutionLabelsArePreservedWithoutInventingMissingQualities() {
        val q = AksorExtractor.parseQualities(JSONObject("""{"qualities":{"q360":"https://cdn.example.test/360.mp4","q720":"https://cdn.example.test/720.mp4","q1080":"https://cdn.example.test/1080.mp4","q2k":"https://cdn.example.test/1440.mp4","q4k":"https://cdn.example.test/2160.mp4","q480":null}}"""))
        assertEquals(setOf("360p", "720p", "1080p", "2K", "4K"), q.keys)
    }
    @Test fun cvhSourceFieldsAndHeadersDoNotInheritAnimeLib() {
        val qualities = CvhExtractor.parseQualities(JSONObject("""{"sources":{"mpegHighUrl":"https://cdn.example.test/video.mp4","mpegFullHdUrl":null,"mpeg4kUrl":""}}"""))
        assertEquals(setOf("720p"), qualities.keys)
        val p = Player(Team(-1,"Dub",false), "animelib", null, qualities, emptyList(), null, null, source="yummy", headers=mapOf("User-Agent" to "test"))
        assertTrue(p.isNative)
        assertFalse(p.headers.containsKey("Referer"))
        assertTrue(Player(Team(1,"Lib",false),"animelib",null,qualities,emptyList(),null,null).headers["Referer"]!!.contains("anilib"))
    }
    @Test fun hlsRelativeVariantsResolveAgainstTheManifestHost() {
        val variants = RutubeExtractor.parseHls("#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1280x720\n720/index.m3u8\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080\n/full.m3u8", "https://cdn.example.test/path/master.m3u8")
        assertEquals("https://cdn.example.test/path/720/index.m3u8", variants["720p"])
        assertEquals("https://cdn.example.test/full.m3u8", variants["1080p"])
    }
    @Test fun fullMediaUrlsNeverBecomeAnimeLibMirrorPaths() = runBlocking {
        val url = "https://cdn.example.test/master.m3u8?expires=1"
        assertEquals(url, Api.videoUrl("https://mirror.example.test/", url))
        assertEquals(listOf(""), Api.rankServers(url))
        assertEquals("file:///episode.mp4", Api.videoUrl("https://mirror.example.test/", "file:///episode.mp4"))
    }
    @Test fun zedfilmUsesOnlyTheDeclaredPrimaryStream() {
        val payload = """{"url":"https://cdn.example.test/master.mpd","url2":"https://cdn.example.test/fallback.mp4"}""".encodeUtf8().base64()
        val stream = ZedfilmExtractor.parse("video_Init('$payload')", "https://zedfilm.ru/player")
        assertEquals("https://cdn.example.test/master.mpd", stream)
        assertNull(ZedfilmExtractor.parse("<iframe src='https://site.example.test/player'>", "https://zedfilm.ru/player"))
    }
    @Test fun iframeIsNeverAcceptedAsADirectMediaItem() {
        assertFalse(DirectExtractor().supports("https://alloha.yani.tv/?token_movie=example"))
        assertFalse(DirectExtractor().supports("https://site.example.test/iframe.html"))
        assertTrue(DirectExtractor().supports("https://cdn.example.test/video.mp4?expires=1"))
        assertFalse(AksorExtractor(SourceHttp()).supports("https://aksor.tv.attacker.test/video/hash"))
    }
}
