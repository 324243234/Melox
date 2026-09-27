package com.melox.player

import com.melox.player.ui.screen.playback.playerHeaderSwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerHeaderGestureTest {
    @Test
    fun shortDragsAndReversalsBackToCenterDoNotSkip() {
        for (distance in listOf(0f, 47.9f, -47.9f)) {
            assertEquals(0, playerHeaderSwipeDirection(distance, 48f))
        }
    }

    @Test
    fun leftSkipsNextAndRightSkipsPreviousAtThreshold() {
        for (density in listOf(1f, 2f, 3.5f)) {
            assertEquals(1, playerHeaderSwipeDirection(-48f * density, 48f * density))
            assertEquals(-1, playerHeaderSwipeDirection(48f * density, 48f * density))
            assertEquals(1, playerHeaderSwipeDirection(-160f * density, 48f * density))
            assertEquals(-1, playerHeaderSwipeDirection(160f * density, 48f * density))
        }
    }
}
