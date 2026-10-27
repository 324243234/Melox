package com.melox.player

import com.melox.player.playback.resolveTrackChangeDirection
import com.melox.player.playback.TrackChangeDirectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTitleDirectionTest {
    @Test
    fun previousIntentPrecedesTheTargetAndSurvivesSameTrackCallbacks() {
        val state = TrackChangeDirectionState()
        state.observeCurrentItem("first", 0)
        state.request("last", -1)
        assertEquals(-1, state.direction)
        state.observeCurrentItem("first", 0)
        state.onTransition("last", seek = true, automatic = false, nextIndex = 1, queueSize = 2)
        assertEquals(-1, state.direction)
        repeat(3) {
            state.onTransition("last", seek = false, automatic = false, nextIndex = 1, queueSize = 2)
            state.observeCurrentItem("last", 1)
            assertEquals(-1, state.direction)
        }
    }

    @Test
    fun duplicateCallbacksDoNotConsumeThePendingPreviousCommand() {
        val state = TrackChangeDirectionState()
        state.observeCurrentItem("first", 0)
        state.request("last", -1)
        state.onTransition("first", seek = false, automatic = false, nextIndex = 0, queueSize = 2)
        state.onTransition("last", seek = true, automatic = false, nextIndex = 1, queueSize = 2)
        assertEquals(-1, state.direction)
    }

    @Test
    fun rapidTransitionsUseTheLastEventEvenWhenNoUiSnapshotWasPublished() {
        val state = TrackChangeDirectionState()
        state.observeCurrentItem("first", 0)
        state.request("last", -1)
        state.onTransition("last", seek = true, automatic = false, nextIndex = 4, queueSize = 5)
        state.onTransition("fourth", seek = true, automatic = false, nextIndex = 3, queueSize = 5)
        assertEquals(-1, state.direction)
        state.request("last", 1)
        state.onTransition("last", seek = true, automatic = false, nextIndex = 4, queueSize = 5)
        assertEquals(1, state.direction)
        state.request("fourth", -1)
        state.onTransition("fourth", seek = true, automatic = false, nextIndex = 3, queueSize = 5)
        assertEquals(-1, state.direction)
    }

    @Test
    fun automaticPlaybackAfterPreviousUsesTheNextDirection() {
        val state = TrackChangeDirectionState()
        state.observeCurrentItem("first", 0)
        state.request("last", -1)
        state.onTransition("last", seek = true, automatic = false, nextIndex = 4, queueSize = 5)
        state.onTransition("first", seek = false, automatic = true, nextIndex = 0, queueSize = 5)
        assertEquals(1, state.direction)
    }

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
