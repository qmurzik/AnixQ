package ru.qmods.anixq.mobile.ui

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.qmods.anixq.tv.data.*

/** Verifies player state/Media3's requested seek only; this does not test video decoding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerSourceSwitchTest {
    @Test fun selectingYummyPreservesTheBaseEpisodeAndPosition() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Store.init(context, "source-switch-test")
        val title = Title(100, "100--test")
        val ep = Episode(200, "3", "Episode", 0)
        val lib = Player(Team(1, "Original", false), "animelib", null,
            mapOf("720p" to "file:///does-not-exist-lib.mp4"), emptyList(), 10, null)
        val yummy = Player(Team(-300, "Dub", false), "animelib", null,
            mapOf("720p" to "file:///does-not-exist-yummy.mp4"), emptyList(), null, null,
            source = "yummy", headers = emptyMap(), balancer = "Aksor")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = PlayerModel(context, scope, title, PlayerRoute(title))
        try {
            model.episodes = listOf(ep)
            model.players = listOf(lib, yummy)
            model.player = lib
            Store.updateProgress(title, ep, lib.team, 127.5, 1400.0, lib)
            model.pos = 127.5; model.dur = 1400.0
            model.load(0, teamId = yummy.team.id, keepPos = model.pos)
            assertFalse(model.loading)
            assertFalse(model.failed)
            assertEquals("yummy", model.player?.source)
            assertEquals(200, model.episode?.id) // canonical AnimeLib episode is preserved
            assertEquals("3", model.episode?.number)
            assertEquals(127.5, model.pos, 0.01)
            assertEquals(127.5, model.exo!!.position, 0.01)
            model.saveProgress(force = true)
            assertEquals(127.5, Store.resume(200)!!, 0.01)
            assertEquals("yummy", Store.lastEntry(100)?.playbackSource)
            assertEquals(100, Store.lastEntry(100)?.title?.id)
        } finally { model.release(); scope.cancel() }
    }
}
