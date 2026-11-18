package ru.qmods.anixq.tv.data.sources

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.qmods.anixq.tv.data.sources.extractors.AksorExtractor

class AksorExtractorTest {
    private fun extractor(server: MockWebServer) = AksorExtractor(SourceHttp(OkHttpClient.Builder()
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
            .url(server.url(chain.request().url.encodedPath)).build()) }.build()))
    private val url = "https://player.aksor.tv/video/hash"

    @Test fun supportsOnlyRealAksorHostsAndNormalizesSchemeRelativeLinks() {
        assertTrue(AksorExtractor.supportsUrl("//player.aksor.tv/video/hash"))
        assertFalse(AksorExtractor.supportsUrl("https://aksor.tv.example.test/video/hash"))
        assertFalse(AksorExtractor.supportsUrl("https://example.test/aksor.tv/video/hash"))
    }
    @Test fun metadataCannotSubstituteAnotherEpisodeOrTitle() = runBlocking {
        for (body in listOf(
            """{"id":"another","episode":"1","anime_id":45}""",
            """{"id":"hash","episode":"2","anime_id":45}""",
            """{"id":"hash","episode":"1","anime_id":46}""")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(JSONObject(body).put("qualities", JSONObject("""{"q720":"https://cdn.example.test/720.mp4"}""")).toString()))
                val error = runCatching { extractor(server).resolveEpisode(url, "1", 45) }.exceptionOrNull()
                assertTrue(error?.message.orEmpty().contains("другую серию"))
                assertEquals(1, server.requestCount) // no HTML recovery that could hide identity mismatch
            }
        }
    }
    @Test fun publicDashFixtureChecksEpisodeAndKeepsSourceSpecificHeaders() = runBlocking {
        MockWebServer().use { server ->
            val fixture = javaClass.getResource("/yummy/aksor-public-dash.json")!!.readText()
            server.enqueue(MockResponse().setBody(fixture))
            val page = "https://player.aksor.tv/video/" + JSONObject(fixture).getString("id")
            val stream = extractor(server).resolveEpisode(page, "01.0", 45)
            assertEquals(setOf("1080p"), stream.qualities.keys)
            assertTrue(stream.qualities.values.single().endsWith("/Studio%20Band/01/1080.mpd"))
            assertEquals(mapOf("Referer" to page, "User-Agent" to SourceHttp.UA), stream.headers)
            val call = server.takeRequest()
            assertNull(call.getHeader("Authorization"))
            assertNull(call.getHeader("X-Application"))
            assertNull(call.getHeader("Cookie"))
        }
    }
    @Test fun fallbackSkipsBrokenScriptAndPreservesEveryQuality() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""<meta content='{{ video_url }}' name='video_url'><script src='/ads.js'></script><script src='/app.js'></script>"""))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("""const api = 'https://api.aksor.tv/api';"""))
            server.enqueue(MockResponse().setBody("""{"qualities":{"q360":"https://cdn.example.test/360.mp4","q1080":"https://cdn.example.test/Studio Band/1080.mpd","q4k":"https://cdn.example.test/4k.m3u8"}}"""))
            val stream = extractor(server).resolve(url)
            assertEquals(listOf("360p", "1080p", "4K"), stream.qualities.keys.toList())
            assertTrue(stream.qualities["1080p"]!!.contains("Studio%20Band"))
            assertEquals(url, stream.headers["Referer"])
            assertNull(stream.headers["Authorization"])
            assertNull(stream.headers["X-Application"])
            assertNull(stream.headers["Cookie"])
            assertEquals(5, server.requestCount)
        }
    }
    @Test fun metaAttributesMayBeReversedAndSignedQueryIsUnescaped() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""<meta content='https://cdn.example.test/master.m3u8?a=1&amp;b=2' name='video_url'>"""))
            assertEquals("https://cdn.example.test/master.m3u8?a=1&b=2", extractor(server).resolve(url).qualities["auto"])
        }
    }
    @Test fun iframeAndTemplateAreNeverVideoQualities() {
        assertTrue(AksorExtractor.parseQualities(JSONObject("""{"qualities":{"q720":"https://player.aksor.tv/video/hash","q1080":"{{ video_url }}","q4k":"javascript:alert(1)"}}""")).isEmpty())
    }
    @Test fun signingOutKeepsNonAksorYummyBalancersProtectedEvenWithAStalePlayerList() {
        val api = AnimeSources.yummy
        val oldUser = api.userToken; val oldApp = api.applicationToken
        try {
            api.userToken = null; api.applicationToken = null
            val public = ru.qmods.anixq.tv.data.Player(ru.qmods.anixq.tv.data.Team(-1, "Dub", false), "yummy", null,
                emptyMap(), emptyList(), null, null, source = "yummy", resolverUrl = url)
            assertFalse(AnimeSources.requiresYummyLogin(public))
            val kodik = public.copy(resolverUrl = "https://kodikplayer.com/seria/test")
            assertTrue(AnimeSources.requiresYummyLogin(kodik))
            assertFalse(AnimeSources.requiresYummyLogin(kodik.copy(source = "animelib")))
            api.userToken = "my-account"; api.applicationToken = "my-app"
            assertFalse(AnimeSources.requiresYummyLogin(kodik))
        } finally { api.userToken = oldUser; api.applicationToken = oldApp }
    }
    @Test fun cancellationDoesNotTriggerFallbackRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeadersDelay(1, java.util.concurrent.TimeUnit.SECONDS).setBody("{}"))
            val request = async(Dispatchers.Default) { extractor(server).resolve(url) }
            assertNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS))
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }
}
