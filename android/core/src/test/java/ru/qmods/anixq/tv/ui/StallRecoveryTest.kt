package ru.qmods.anixq.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.qmods.anixq.tv.ui.StallRecovery.Action.*

class StallRecoveryTest {
    @Test fun shortBuffersNeverReduceQuality() {
        val policy = StallRecovery()
        repeat(12) { n ->
            val t = n * 10_000L
            assertEquals(NONE, policy.update(t, true, true))
            assertEquals(NONE, policy.update(t + 5_000, true, true))
            assertEquals(NONE, policy.update(t + 5_500, false, true))
        }
    }
    @Test fun disabledAutomaticQualityStillRecoversMirrors() {
        val policy = StallRecovery()
        repeat(6) { n ->
            val t = n * 30_000L
            assertEquals(NONE, policy.update(t, true, false))
            assertEquals(NONE, policy.update(t + 6_000, true, false))
            assertEquals(MIRROR, policy.update(t + 18_000, true, false))
        }
    }
    @Test fun threeSustainedStallsRequiredAndDropsHaveCooldown() {
        val policy = StallRecovery()
        for (t in listOf(0L, 30_000L)) {
            assertEquals(NONE, policy.update(t, true, true))
            assertEquals(NONE, policy.update(t + 6_000, true, true))
            assertEquals(NONE, policy.update(t + 7_000, false, true))
        }
        assertEquals(NONE, policy.update(60_000, true, true))
        assertEquals(LOWER_QUALITY, policy.update(66_000, true, true))
        for (t in listOf(90_000L, 120_000L, 150_000L)) {
            assertEquals(NONE, policy.update(t, true, true))
            assertEquals(NONE, policy.update(t + 6_000, true, true))
            assertEquals(NONE, policy.update(t + 7_000, false, true))
        }
    }
    @Test fun newEpisodeClearsPreviousStallHistoryAndStartupDoesNotCount() {
        val policy = StallRecovery()
        policy.update(0, true, true); policy.update(6_000, true, true); policy.update(7_000, false, true)
        policy.update(20_000, true, true); policy.update(26_000, true, true)
        policy.reset(30_000, clearHistory = true)
        assertEquals(NONE, policy.update(31_000, true, true))
        assertEquals(NONE, policy.update(39_000, true, true))
        assertEquals(NONE, policy.update(40_000, true, true))
        assertEquals(NONE, policy.update(46_000, true, true))
    }
}
