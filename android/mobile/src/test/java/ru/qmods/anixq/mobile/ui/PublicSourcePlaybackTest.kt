package ru.qmods.anixq.mobile.ui

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.qmods.anixq.tv.data.*
import ru.qmods.anixq.tv.data.sources.AnimeSources


/** Checks source/seek state; no claim of video decoding under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PublicSourcePlaybackTest {
    @Test fun publiclyListedSourceDoesNotForceLoginOrResetCanonicalProgress() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Store.init(context, "public-source-test")
        AnimeSources.yummy.userToken = null
        AnimeSources.yummy.applicationToken = null
        val title = Title(110, "110--public-source")
        ru.qmods.anixq.mobile.data.PlaybackHistory.init(context)
        ru.qmods.anixq.mobile.data.PlaybackHistory.remember(title, listOf("Драма"))
        val ep = Episode(210, "3", null, 0)
        val lib = Player(Team(1, "Dub", false), "animelib", null,
            mapOf("720p" to "file:///lib.mp4"), emptyList(), 10, null)
        val public = Player(Team(-310, "Dub", false), "yummy", null, emptyMap(), emptyList(), null, null,
            source = "yummy", headers = emptyMap(), resolverUrl = "https://player.aksor.tv/video/public-hash", balancer = "Aksor")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = PlayerModel(context, scope, title, PlayerRoute(title))
        try {
            val resolved = public.copy(player = "animelib", hrefs = mapOf("720p" to "file:///public.mp4"))
            model.episodes = listOf(ep); model.players = listOf(lib, public); model.player = resolved
            Store.updateProgress(title, ep, lib.team, 127.5, 1400.0, lib)
            model.pos = 127.5; model.dur = 1400.0
            model.load(0, teamId = public.team.id, keepPos = 127.5, qualityKey = "720p")
            assertFalse(model.failed)
            assertEquals("yummy", model.player?.source)
            assertTrue(model.player!!.isNative)
            assertTrue(model.player!!.headers.isEmpty())
            assertEquals(210, model.episode?.id)
            assertEquals("3", model.episode?.number)
            assertEquals(127.5, model.exo!!.position, 0.01)
            assertEquals(127.5, Store.resume(210)!!, 0.01)
        } finally { model.release(); scope.cancel() }
    }
}
