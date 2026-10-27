package com.melox.player

import com.melox.player.playback.acceptTrackSkip
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSkipDebounceTest {
    @Test
    fun firstTapIsImmediateEvenJustAfterBoot() {
        val lastTap = AtomicLong(Long.MIN_VALUE)
        assertTrue(acceptTrackSkip(lastTap, 0L))
        assertEquals(0L, lastTap.get())
    }

    @Test
    fun anIgnoredTapRestartsTheSharedWindow() {
        val lastTap = AtomicLong(Long.MIN_VALUE)
        assertTrue(acceptTrackSkip(lastTap, 1_000L))
        assertFalse(acceptTrackSkip(lastTap, 1_100L))
        assertFalse(acceptTrackSkip(lastTap, 1_301L))
        assertTrue(acceptTrackSkip(lastTap, 1_602L))
    }

    @Test
    fun exactlyThreeHundredMillisecondsIsIgnoredAndAlsoRestartsTheWindow() {
        val lastTap = AtomicLong(Long.MIN_VALUE)
        assertTrue(acceptTrackSkip(lastTap, 1_000L))
        assertFalse(acceptTrackSkip(lastTap, 1_300L))
        assertFalse(acceptTrackSkip(lastTap, 1_600L))
        assertTrue(acceptTrackSkip(lastTap, 1_901L))
    }

    @Test
    fun continuousHundredMillisecondTapsOnlyAcceptTheFirstUntilTheUserPauses() {
        val lastTap = AtomicLong(Long.MIN_VALUE)
        val accepted = (0L..10L).filter { tap -> acceptTrackSkip(lastTap, tap * 100L) }
        assertEquals(listOf(0L), accepted)
        assertFalse(acceptTrackSkip(lastTap, 1_300L))
        assertTrue(acceptTrackSkip(lastTap, 1_601L))
    }

    @Test
    fun tapsSpacedMoreThanThreeHundredMillisecondsContinueToSkipImmediately() {
        val lastTap = AtomicLong(Long.MIN_VALUE)
        for (tap in listOf(1_000L, 1_301L, 1_602L, 1_903L)) {
            assertTrue(acceptTrackSkip(lastTap, tap))
        }
    }
}
