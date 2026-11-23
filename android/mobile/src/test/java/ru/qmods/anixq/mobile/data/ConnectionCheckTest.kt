package ru.qmods.anixq.mobile.data

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ConnectionCheckTest {
    @Test fun forbiddenIsDifferentFromAConnectionTimeout() = runBlocking {
        MockWebServer().use { server ->
            val client = OkHttpClient.Builder().callTimeout(800, TimeUnit.MILLISECONDS).build()
            val probe = ConnectionProbe("AnimeLib", "API", server.url("/").toString(), ProbeContent.JSON, animeLib = true)
            server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
            val denied = ConnectionCheck.probe(client, probe)
            assertFalse(denied.success)
            assertEquals("HTTP 403 · доступ запрещён", denied.message)
            val request = server.takeRequest()
            assertEquals("https://anilib.me/", request.getHeader("Referer"))
            assertEquals("5", request.getHeader("Site-Id"))
            assertNull(request.getHeader("Authorization"))

            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            assertEquals("Таймаут", ConnectionCheck.probe(client, probe).message)
        }
    }

    @Test fun catalogShowsCacheResultAndRejectsUnexpectedResponses() = runBlocking {
        MockWebServer().use { server ->
            val probe = ConnectionProbe("AnixQ", "catalog", server.url("/").toString(), ProbeContent.JSON, animeLib = true)
            val client = OkHttpClient()
            server.enqueue(MockResponse().setHeader("x-anixq-cache", "fallback").setBody("""{"data":[]}"""))
            val catalog = ConnectionCheck.probe(client, probe)
            assertTrue(catalog.success)
            assertEquals("HTTP 200 · сохранённый ответ", catalog.message)

            for (body in listOf("<html>Denied</html>", """{"error":"blocked"}""")) {
                server.enqueue(MockResponse().setBody(body))
                assertFalse(ConnectionCheck.probe(client, probe).success)
            }
        }
    }

    @Test fun dnsFailureIsLabeledWithoutLeakingTheException() = runBlocking {
        val client = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> = throw UnknownHostException("private diagnostic")
        }).build()
        val probe = ConnectionProbe("Kodik", "host", "https://kodik.example/")
        assertEquals("DNS · адрес не найден", ConnectionCheck.probe(client, probe).message)
    }

    @Test fun coverChecksJpegBytes() = runBlocking {
        MockWebServer().use { server ->
            val probe = ConnectionProbe("AnimeLib", "cover", server.url("/cover.jpg").toString(), ProbeContent.JPEG, animeLib = true)
            val client = OkHttpClient()
            server.enqueue(MockResponse().setBody("not an image"))
            assertFalse(ConnectionCheck.probe(client, probe).success)
            server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(-1, -40, -1))))
            assertEquals("HTTP 200 · JPEG", ConnectionCheck.probe(client, probe).message)
        }
    }
}
