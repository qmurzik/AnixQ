package ru.qmods.anixq.tv.data.sources

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.qmods.anixq.tv.data.CatalogQuery
import ru.qmods.anixq.tv.data.CatalogSort
import ru.qmods.anixq.tv.data.PlaybackChoices
import ru.qmods.anixq.tv.data.Title
import ru.qmods.anixq.tv.data.sources.extractors.CvhExtractor

class ShikimoriCatalogTest {
    private val title = """{
        "id":64534,"name":"Koori no Jouheki 2nd Season","russian":"Ледяная стена 2",
        "kind":"tv","score":"7.81","status":"ongoing","episodes":13,"episodes_aired":8,
        "aired_on":"2026-10-02","image":{"original":"/system/animes/original/64534.jpg"},
        "description":"[b]Описание[/b]","genres":[{"id":22,"russian":"Романтика"}]
    }"""
    private val playlist = """{
        "isSerial":true,"items":[
          {"episode":1,"voiceStudio":"ТО Дубляжная","voiceType":"Дубляж","vkId":"first"},
          {"episode":1,"voiceStudio":"SubVost","voiceType":"Субтитры","vkId":"subbed"},
          {"episode":2,"voiceStudio":"ТО Дубляжная","voiceType":"Дубляж","vkId":"second"},
          {"episode":3,"voiceStudio":"ТО Дубляжная","voiceType":"Дубляж"}
        ]
    }"""

    @Test fun parsesCatalogMetadataAndNamespacesIds() {
        val parsed = checkNotNull(ShikimoriCatalog.parseTitle(JSONObject(title)))
        assertEquals("Ледяная стена 2", parsed.title)
        assertEquals(64534, parsed.malId)
        assertEquals(8, parsed.episodesAired)
        assertEquals("https://shikimori.io/system/animes/original/64534.jpg", parsed.cover)
        assertEquals(SourceIds.shikiTitle(64534), parsed.id)
        assertNotEquals(SourceIds.yummy(64534), parsed.id)
        assertEquals(64534, SourceIds.shikiId(Title(parsed.id, parsed.slug)))
        assertFalse(AnimeSources.safelyMatches(parsed, parsed.copy(malId = 64533)))
    }

    @Test fun mapsSearchAndCatalogFilters() {
        val query = CatalogQuery(sort = CatalogSort.RATING, status = 1, type = 16, genre = 22, yearFrom = 2022, yearTo = 2026)
        val parameters = ShikimoriCatalog.parameters(query, 2)
        assertEquals("ranked", parameters["order"])
        assertEquals("ongoing", parameters["status"])
        assertEquals("tv", parameters["kind"])
        assertEquals("22", parameters["genre"])
        assertEquals("2022_2026", parameters["season"])
        assertEquals("2", parameters["page"])
        val search = ShikimoriCatalog.parameters(query.copy(text = " ice "), 1)
        assertEquals("ice", search["search"])
        assertNull(search["order"])
    }

    @Test fun separatesVoicesAndKeepsStableEpisodeAndTeamIds() {
        val data = JSONObject(playlist)
        val episodes = ShikimoriCatalog.parseEpisodes(64534, data)
        assertEquals(listOf("1", "2"), episodes.map { it.number })
        assertNotEquals(SourceIds.shikiEpisode(64533, 1), episodes.first().id)
        val first = ShikimoriCatalog.parsePlayers(data, "1.0")
        val second = ShikimoriCatalog.parsePlayers(data, "2")
        assertEquals(2, first.size)
        assertEquals(first.first().team.id, second.single().team.id)
        assertTrue(first.last().team.isSubtitles)
        assertTrue(first.first().isExternalEmbed)
        assertTrue(first.first().resolverUrl!!.endsWith("/video/first"))
        assertTrue(second.single().resolverUrl!!.endsWith("/video/second"))
        assertEquals("Шикимори", PlaybackChoices.sources(PlaybackChoices.options(first)).single().label)
    }

    @Test fun cvhRequestsTheShikimoriPublisherAndReturnsNativeStreams() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[$title]"))
            server.enqueue(MockResponse().setBody(title))
            server.enqueue(MockResponse().setBody(playlist))
            server.enqueue(MockResponse().setBody("""{"sources":{
                "hlsUrl":"https://media.example/index.m3u8","dashUrl":"https://media.example/index.mpd",
                "mpegHighUrl":"https://media.example/720.mp4"}}"""))
            val http = SourceHttp(OkHttpClient.Builder().addInterceptor { chain ->
                val original = chain.request()
                val local = server.url(original.url.encodedPath + original.url.encodedQuery?.let { "?$it" }.orEmpty())
                chain.proceed(original.newBuilder().url(local).build())
            }.build())
            val cvh = CvhExtractor(http)
            val source = ShikimoriCatalog(http, cvh)
            assertEquals(1, source.catalog(CatalogQuery(text = "Ледяная стена"), 1).first.size)
            val detail = source.detail(64534)
            assertEquals(listOf("Романтика"), detail.genres)
            assertEquals("Описание", detail.summary)
            assertEquals(2, source.episodes(64534).size)
            val selected = source.players(64534, "1").first()
            val resolved = cvh.resolve(checkNotNull(selected.resolverUrl))
            assertEquals(mapOf("auto" to "https://media.example/index.m3u8", "720p" to "https://media.example/720.mp4"), resolved.qualities)
            assertNull(resolved.headers["Referer"])
            assertEquals("application/x-mpegURL", resolved.mimeTypes["auto"])
            assertEquals("video/mp4", resolved.mimeTypes["720p"])

            val catalogRequest = server.takeRequest()
            assertEquals("Ледяная стена", catalogRequest.requestUrl?.queryParameter("search"))
            assertNull(catalogRequest.getHeader("Authorization"))
            assertEquals("/api/animes/64534", server.takeRequest().path)
            val playlistRequest = server.takeRequest()
            assertEquals("3058", playlistRequest.requestUrl?.queryParameter("pub"))
            assertEquals("64534", playlistRequest.requestUrl?.queryParameter("id"))
            assertEquals("https://shikimori.io/", playlistRequest.getHeader("Referer"))
            assertEquals("/api/v1/player/sv/video/first", server.takeRequest().path)
        }
    }
}
