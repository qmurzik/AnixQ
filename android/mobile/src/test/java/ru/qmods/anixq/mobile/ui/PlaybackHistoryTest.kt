package ru.qmods.anixq.mobile.ui

import android.content.Context
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
import ru.qmods.anixq.mobile.data.PlaybackHistory
import ru.qmods.anixq.tv.data.Episode
import ru.qmods.anixq.tv.data.Player
import ru.qmods.anixq.tv.data.Store
import ru.qmods.anixq.tv.data.Team
import ru.qmods.anixq.tv.data.Title

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackHistoryTest {
    @Test fun filteredPlaybackDoesNotWriteProgressAndExistingEntriesStayHiddenOnPhone() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Store.init(context, "playback-history-test")
        PlaybackHistory.init(context)
        val filtered = Title(7351, "7351--sample")
        val ordinary = Title(7352, "7352--sample")
        val unknown = Title(7353, "7353--sample")
        val team = Team(10, "Dub", false)
        val episode = Episode(5650, "1", null, 0)
        val player = Player(team, "animelib", null, mapOf("720p" to "file:///sample.mp4"),
            emptyList(), null, null, source = "animego")
        PlaybackHistory.remember(filtered, listOf("Драма", " ХЕНТАЙ "))
        PlaybackHistory.remember(ordinary, listOf("Драма", "Романтика"))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            for (title in listOf(filtered, ordinary, unknown)) {
                val model = PlayerModel(context, scope, title, PlayerRoute(title))
                model.episodes = listOf(episode.copy(id = title.id + 10))
                model.players = listOf(player)
                model.load(0, teamId = team.id, keepPos = 90.0)
                assertFalse(model.failed)
                model.pos = 90.0
                model.dur = 1000.0
                model.saveProgress(force = true)
                model.release()
            }
            assertNull(Store.lastEntry(filtered.id))
            assertNull(Store.lastEntry(unknown.id))
            assertEquals(90.0, Store.lastEntry(ordinary.id)!!.pos, 0.01)

            Store.updateProgress(filtered, episode, team, 30.0, 1000.0)
            assertNotNull(Store.lastEntry(filtered.id))
            assertNull(PlaybackHistory.lastEntry(filtered))
            assertEquals(listOf(ordinary.id), PlaybackHistory.continueEntries().map { it.title.id })
        } finally {
            scope.cancel()
        }
    }
}
