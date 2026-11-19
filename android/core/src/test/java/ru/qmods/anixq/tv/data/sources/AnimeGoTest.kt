package ru.qmods.anixq.tv.data.sources

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.qmods.anixq.tv.data.Episode
import ru.qmods.anixq.tv.data.Title
import ru.qmods.anixq.tv.data.PlaybackChoices
import okhttp3.HttpUrl.Companion.toHttpUrl

class AnimeGoTest {
    private val title = Title(123, "123--attack", rusName = "Атака титанов", shikiId = 16498)
    private val ep = Episode(456, "1", null, 0)
    private fun fixture() = javaClass.getResource("/animego/players.json")!!.readText()

    @Test fun verifiesTheTitleIdAndReusesTheListingAcrossEpisodes() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/search" -> MockResponse().setBody("""<a href="/anime/season-two-9">Two</a><a href="/anime/season-one-8">One</a>""")
                    "/go/shikimori/9" -> MockResponse().setResponseCode(302).setHeader("Location", "https://shikimori.io/animes/25777")
                    "/go/shikimori/8" -> MockResponse().setResponseCode(302).setHeader("Location", "https://shikimori.io/animes/16498")
                    "/api/player" -> {
                        check(request.body.readUtf8() == "id=8")
                        check(request.getHeader("Authorization") == null && request.getHeader("X-Application") == null)
                        MockResponse().setBody(fixture())
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val go = AnimeGo(server.url("/").toString().trimEnd('/'))
            val first = go.players(title, ep)
            val second = go.players(title, ep.copy(id = 457, number = "2"))
            val dub = first.first { !it.team.isSubtitles }
            assertEquals(dub.team.id, second.single().team.id)
            assertEquals("AnimeGO", PlaybackChoices.options(first).first().sourceLabel)
            assertTrue(dub.isExternalEmbed && dub.canResolve)
            assertFalse(AnimeSources.requiresYummyLogin(dub))
            assertNull(dub.playerId)
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun specialAndOtherSeasonsCannotSubstituteForARegularEpisode() {
        val players = AnimeGo.parsePlayers(JSONObject(fixture()), "01.0", "8")
        assertEquals(listOf("StudioBand", "Субтитры"), players.map { it.team.name })
        assertTrue(players.last().team.isSubtitles)
        val multiSeason = fixture().replace("\"season\":1,\"num\":2", "\"season\":2,\"num\":2")
        assertTrue(AnimeGo.parsePlayers(JSONObject(multiSeason), "1", "8").isEmpty())
        assertTrue(AnimeGo.parsePlayers(JSONObject(fixture()), "1.5", "8").isEmpty())
        assertFalse(AnimeGo.matchesRedirect("https://shikimori.io.example.test/animes/16498", "shikimori", 16498))
        assertFalse(AnimeGo.matchesRedirect("https://shikimori.io/characters/16498", "shikimori", 16498))
    }

    @Test fun liveAnimeGoKodik() = runBlocking {
        assumeTrue(System.getenv("ANIXQ_LIVE_ANIMEGO") == "1")
        val players = AnimeGo().players(title, ep)
        val chosen = players.first { it.team.name == "StudioBand" }
        val stream = AnimeSources.resolve(chosen, title, ep)
        assertTrue(stream.isNative)
        val url = stream.hrefs["720p"] ?: stream.hrefs.values.first()
        val manifest = SourceHttp().request(url, stream.headers).text
        assertTrue(manifest.startsWith("#EXTM3U"))
        val segment = manifest.lineSequence().first { it.isNotBlank() && !it.startsWith("#") }.trim()
        val segmentUrl = url.toHttpUrl().resolve(segment)!!
        SourceHttp().client.newCall(okhttp3.Request.Builder().url(segmentUrl)
            .header("Range", "bytes=0-1023").build()).execute().use { response ->
            assertTrue("Kodik segment HTTP ${response.code}", response.isSuccessful)
            assertEquals(0x47, response.body!!.byteStream().read()) // MPEG-TS sync byte
        }
        println("AnimeGO: ${players.size} variants, Kodik qualities=${stream.hrefs.keys}")
    }
}
