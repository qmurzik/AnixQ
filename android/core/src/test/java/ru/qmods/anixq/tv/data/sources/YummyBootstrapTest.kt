package ru.qmods.anixq.tv.data.sources

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YummyBootstrapTest {
    private fun api(server: MockWebServer) = YummyAnime(SourceHttp(), server.url("/").toString().trimEnd('/')).apply { applicationToken = null }
    private fun MockWebServer.json(body: String) = enqueue(MockResponse().setBody(body))
    private val profile = """{"response":{"id":7,"nickname":"owner"}}"""
    private val detail = """{"response":{"app_id":12,"name":"AnixQ","owner":{"id":7},"public_token":"owned-public-key","private_access_token":"never-use-this-private-key"}}"""

    @Test fun firstLoginBootstrapsWithoutBorrowingAnApplicationKey() = runBlocking {
        MockWebServer().use { server ->
            server.json("""{"response":{"success":true,"token":"own-session"}}""")
            server.json(profile); server.json("""{"response":[]}""")
            server.json("""{"response":{"app_id":12}}"""); server.json(detail)
            val api = api(server)
            val token = api.login("owner@example.test", "password")
            val connection = api.connectApplication(token)
            assertEquals("owned-public-key", connection.publicToken)
            assertEquals(7, connection.managedOwnerId)
            val requests = List(5) { server.takeRequest() }
            assertEquals(listOf("/profile/login","/profile","/apps?limit=100&offset=0","/apps","/apps/12"), requests.map { it.path })
            assertTrue(requests.all { it.getHeader("X-Application") == null })
            assertNull(requests.first().getHeader("Authorization"))
            assertTrue(requests.drop(1).all { it.getHeader("Authorization") == "Bearer own-session" })
            assertEquals("AnixQ", JSONObject(requests[3].body.readUtf8()).getString("name"))
        }
    }
    @Test fun reusesOnlyTheSignedInOwnersExistingApp() = runBlocking {
        MockWebServer().use { server ->
            server.json(profile)
            server.json("""{"response":[{"app_id":5,"name":"AnixQ","owner":{"id":99}},{"app_id":12,"name":"AnixQ","owner":{"id":7}}]}""")
            server.json(detail)
            assertEquals("owned-public-key", api(server).connectApplication("own-session").publicToken)
            val requests = List(3) { server.takeRequest() }
            assertTrue(requests.all { it.method == "GET" })
            assertEquals("/apps/12", requests.last().path)
        }
    }
    @Test fun interruptedRegistrationDoesNotCreateAnotherApplication() = runBlocking {
        MockWebServer().use { server ->
            server.json(profile); server.json("""{"response":[]}"""); server.json("""{"response":{"app_id":12}}""")
            server.enqueue(MockResponse().setResponseCode(503))
            val receipts = hashMapOf<Int, Int>()
            val receiptStore = object : YummyApplicationReceipts {
                override fun get(ownerId: Int) = receipts[ownerId]
                override fun save(ownerId: Int, applicationId: Int) { receipts[ownerId] = applicationId }
            }
            val first = api(server).apply { applicationReceipts = receiptStore }
            assertNotNull(runCatching { first.connectApplication("own-session") }.exceptionOrNull())
            assertEquals(12, receipts[7])
            server.json(profile); server.json(detail)
            val restarted = api(server).apply { applicationReceipts = receiptStore }
            assertEquals("owned-public-key", restarted.connectApplication("own-session").publicToken)
            val requests = List(6) { server.takeRequest() }
            assertEquals(1, requests.count { it.method == "POST" })
        }
    }
    @Test fun rejectsAppDetailsBelongingToAnotherOwner() = runBlocking {
        MockWebServer().use { server ->
            server.json(profile); server.json("""{"response":[{"app_id":12,"name":"AnixQ","owner":{"id":7}}]}""")
            server.json(detail.replace("\"id\":7", "\"id\":99"))
            assertTrue(runCatching { api(server).connectApplication("own-session") }.exceptionOrNull()?.message.orEmpty().contains("другому аккаунту"))
        }
    }
    @Test fun invalidListNeverTriggersApplicationCreation() = runBlocking {
        MockWebServer().use { server ->
            server.json(profile); server.json("""{"response":false}""")
            assertNotNull(runCatching { api(server).connectApplication("own-session") }.exceptionOrNull())
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun explicitlyConfiguredPublicKeyNeedsNoRegistration() = runBlocking {
        MockWebServer().use { server ->
            server.json(profile)
            val api = api(server).apply { applicationToken = "explicit-owned-key" }
            assertEquals("explicit-owned-key", api.connectApplication("own-session").publicToken)
            assertEquals(1, server.requestCount)
        }
    }
}
