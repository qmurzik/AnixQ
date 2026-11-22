package ru.qmods.anixq.tv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicEdgeRoutingTest {
    @Test fun publicReadsCanUseTheCache() {
        assertTrue(canUsePublicEdge("/anime", "GET", authenticated = false))
    }

    @Test fun credentialsAndWritesGoStraightToAnimeLib() {
        assertFalse(canUsePublicEdge("/anime", "GET", authenticated = true))
        assertFalse(canUsePublicEdge("/auth/oauth/token", "POST", authenticated = false))
        assertFalse(canUsePublicEdge("/auth/me", "GET", authenticated = false))
        assertFalse(canUsePublicEdge("/bookmarks", "GET", authenticated = false))
        assertFalse(canUsePublicEdge("/episodes/timecodes", "PUT", authenticated = true))
    }
}
