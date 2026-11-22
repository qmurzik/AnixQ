package ru.qmods.anixq.tv.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverRequestsTest {
    private val coverUrl = "https://cover.mangalib.me/uploads/anime/11/cover/poster.jpg"

    @Test fun sendsAnimeLibRefererToCurrentCoverHost() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody("jpeg"))
        client(server).newCall(Request.Builder().url(coverUrl).build()).execute().use {
            assertEquals(200, it.code)
        }
        val request = server.takeRequest()
        assertEquals("https://anilib.me/", request.getHeader("Referer"))
        assertEquals("cover.mangalib.me", request.getHeader("X-Test-Host"))
    }

    @Test fun triesAnOfficialMirrorWhenCoverHostReturns403() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody("jpeg"))
        client(server).newCall(Request.Builder().url(coverUrl + "?size=small").build()).execute().use {
            assertEquals("jpeg", it.body?.string())
        }
        assertEquals("cover.mangalib.me", server.takeRequest().getHeader("X-Test-Host"))
        val mirror = server.takeRequest()
        assertEquals("cover.cdnlibs.org", mirror.getHeader("X-Test-Host"))
        assertEquals("/uploads/anime/11/cover/poster.jpg?size=small", mirror.path)
        assertEquals("https://anilib.me/", mirror.getHeader("Referer"))
    }

    @Test fun doesNotSendAnimeLibRefererToOtherSources() = MockWebServer().use { server ->
        server.enqueue(MockResponse())
        val url = "https://cover.mangalib.me.example.org/uploads/anime/11/cover/poster.jpg"
        client(server).newCall(Request.Builder().url(url).build()).execute().close()
        assertNull(server.takeRequest().getHeader("Referer"))
    }

    private fun client(server: MockWebServer): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(CoverRequests)
        .addInterceptor { chain ->
            val request = chain.request()
            val localUrl = server.url(request.url.encodedPath + request.url.encodedQuery?.let { "?$it" }.orEmpty())
            chain.proceed(request.newBuilder().url(localUrl).header("X-Test-Host", request.url.host).build())
        }.build()
}
