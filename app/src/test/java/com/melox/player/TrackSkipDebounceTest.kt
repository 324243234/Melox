package com.melox.player

import com.melox.player.playback.shouldAcceptTrackSkip
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSkipDebounceTest {
    @Test
    fun repeatedSkipCommandsWithinTheWindowAreCollapsed() {
        var previous = Long.MIN_VALUE
        assertTrue(shouldAcceptTrackSkip(previous, 1_000L))
        previous = 1_000L
        assertFalse(shouldAcceptTrackSkip(previous, 1_100L))
        assertFalse(shouldAcceptTrackSkip(previous, 1_299L))
        assertTrue(shouldAcceptTrackSkip(previous, 1_300L))
        previous = 1_300L
        assertFalse(shouldAcceptTrackSkip(previous, 1_500L))
        assertTrue(shouldAcceptTrackSkip(previous, 1_600L))
    }
}
