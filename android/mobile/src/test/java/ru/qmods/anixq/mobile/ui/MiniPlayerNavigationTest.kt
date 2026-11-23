package ru.qmods.anixq.mobile.ui

import org.junit.Assert.*
import org.junit.Test
import ru.qmods.anixq.tv.data.Title

class MiniPlayerNavigationTest {
    @Test fun browsingAndExpandingKeepTheSamePlaybackRoute() {
        val nav = Nav()
        val title = Title(1, "title")
        val playback = PlayerRoute(title, episodeId = 12, startAt = 128.0)
        nav.open(title)
        nav.play(playback)
        nav.minimizePlayer()
        assertEquals(TitleRoute(title), nav.top)
        assertSame(playback, nav.playerRoute)

        nav.push(SettingsRoute)
        nav.expandPlayer()
        assertSame(playback, nav.top)
        nav.minimizePlayer()
        assertSame(SettingsRoute, nav.top)
        nav.closePlayer()
        assertNull(nav.playerRoute)
        assertSame(SettingsRoute, nav.top)
    }

    @Test fun startingAnotherEpisodeReplacesTheMinimizedSession() {
        val nav = Nav()
        val first = PlayerRoute(Title(1, "first"), episodeId = 1)
        val second = PlayerRoute(Title(2, "second"), episodeId = 2)
        nav.play(first)
        nav.minimizePlayer()
        nav.play(second)
        assertSame(second, nav.playerRoute)
        assertEquals(listOf(second), nav.stack.toList())
        nav.pop()
        assertNull(nav.playerRoute)
        assertNull(nav.top)
    }
}
