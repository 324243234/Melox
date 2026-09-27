package com.melox.player

import com.melox.player.playback.resolveTrackChangeDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTitleDirectionTest {
    @Test
    fun explicitSkipsPreserveDirectionAcrossWrapAndTwoItemQueues() {
        for (size in listOf(2, 5)) {
            assertEquals(-1, resolveTrackChangeDirection(-1, false, 0, size - 1, size))
            assertEquals(1, resolveTrackChangeDirection(1, false, size - 1, 0, size))
        }
        assertEquals(-1, resolveTrackChangeDirection(-1, false, 1, 0, 2))
        assertEquals(1, resolveTrackChangeDirection(1, false, 0, 1, 2))
        assertEquals(-1, resolveTrackChangeDirection(-1, false, 0, 2, 5))
    }

    @Test
    fun automaticPlaybackAlwaysEntersFromRight() {
        assertEquals(1, resolveTrackChangeDirection(null, true, 4, 0, 5))
        assertEquals(1, resolveTrackChangeDirection(null, true, 3, 1, 5))
    }

    @Test
    fun externalChangesUseQueueOrderAndWraparound() {
        assertEquals(-1, resolveTrackChangeDirection(null, false, 3, 2, 5))
        assertEquals(1, resolveTrackChangeDirection(null, false, 2, 3, 5))
        assertEquals(-1, resolveTrackChangeDirection(null, false, 0, 4, 5))
        assertEquals(1, resolveTrackChangeDirection(null, false, 4, 0, 5))
        assertEquals(1, resolveTrackChangeDirection(null, false, -1, 0, 5))
    }
}
