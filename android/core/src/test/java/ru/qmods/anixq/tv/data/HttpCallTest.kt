package ru.qmods.anixq.tv.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpCallTest {
    @Test fun cancellingTheCallerStopsTheNetworkCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/slow")).build())
            val pending = launch(start = CoroutineStart.UNDISPATCHED) {
                call.awaitResult { it.body?.string() }
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            pending.cancelAndJoin()
            assertTrue(call.isCanceled())
        }
    }
}
