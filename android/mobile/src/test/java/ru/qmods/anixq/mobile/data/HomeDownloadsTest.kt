package ru.qmods.anixq.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.qmods.anixq.tv.data.Episode
import ru.qmods.anixq.tv.data.Title
import ru.qmods.anixq.tv.data.WatchEntry

class HomeDownloadsTest {
    private val title = Title(1, "title")
    private val entry = WatchEntry(title, 2, "2", "", 0, "", 40.0, 100.0, 1.0)
    private val episodes = (1..8).map { Episode(it, it.toString(), null, it - 1) }

    @Test fun suggestionsSkipCurrentAndUnavailableEpisodes() {
        val result = HomeDownloads.nextEpisodes(entry, episodes) { it.id in setOf(3, 5) }
        assertEquals(listOf(4, 6, 7), result.map { it.id })
    }

    @Test fun episodeNumberCanMatchAfterIdChanges() {
        val result = HomeDownloads.nextEpisodes(entry.copy(episodeId = 1234), episodes) { false }
        assertEquals(listOf(3, 4, 5), result.map { it.id })
    }

    @Test fun noSuggestionsForUnknownOrFinalEpisode() {
        assertTrue(HomeDownloads.nextEpisodes(entry.copy(episodeId = 1234, ep = "20"), episodes) { false }.isEmpty())
        assertTrue(HomeDownloads.nextEpisodes(entry.copy(episodeId = 8, ep = "8"), episodes) { false }.isEmpty())
    }
}
