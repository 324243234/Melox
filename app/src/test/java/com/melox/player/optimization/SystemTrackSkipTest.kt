package com.melox.player.optimization

import androidx.media3.common.Player
import com.melox.player.playback.resumeAfterSystemTrackSkip
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SystemTrackSkipTest {
    @Test
    fun pausedSkipResumesTheSelectedTrackAndPublishesIntentBeforeAudiblePlayback() {
        val source = PlayerSource()
        source.player.seekToDefaultPosition(1)
        source.player.resumeAfterSystemTrackSkip(skipped = true, playPauseRequested = false)
        assertEquals(listOf("seek:1", "play:1"), source.actions)
        assertTrue(source.playWhenReady)
        assertFalse(source.isPlaying)
    }

    @Test
    fun anExplicitPauseInTheSameInteractionIsNotOverridden() {
        val source = PlayerSource()
        source.player.seekToDefaultPosition(1)
        source.player.resumeAfterSystemTrackSkip(skipped = true, playPauseRequested = true)
        assertEquals(listOf("seek:1"), source.actions)
        assertFalse(source.playWhenReady)
    }

    @Test
    fun playingSkipsAndOrdinaryPositionSeeksDoNotIssueAnotherPlayCommand() {
        val source = PlayerSource()
        source.playWhenReady = true
        source.player.resumeAfterSystemTrackSkip(skipped = true, playPauseRequested = false)
        source.playWhenReady = false
        source.player.resumeAfterSystemTrackSkip(skipped = false, playPauseRequested = false)
        assertTrue(source.actions.isEmpty())
    }

    @Test
    fun anIdleTrackIsPreparedButAnEmptyQueueCannotAutoplay() {
        val source = PlayerSource()
        source.state = Player.STATE_IDLE
        source.player.resumeAfterSystemTrackSkip(skipped = true, playPauseRequested = false)
        assertEquals(listOf("prepare:0", "play:0"), source.actions)
        source.actions.clear()
        source.playWhenReady = false
        source.count = 0
        source.player.resumeAfterSystemTrackSkip(skipped = true, playPauseRequested = false)
        assertTrue(source.actions.isEmpty())
    }

    private class PlayerSource {
        var index = 0
        var count = 2
        var state = Player.STATE_BUFFERING
        var playWhenReady = false
        val isPlaying = false
        val actions = mutableListOf<String>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader,
            arrayOf(Player::class.java)) { proxy, method, args ->
            when (method.name) {
                "getMediaItemCount" -> count
                "getPlaybackState" -> state
                "getPlayWhenReady" -> playWhenReady
                "seekToDefaultPosition" -> { index = args!![0] as Int; actions += "seek:$index"; null }
                "prepare" -> { actions += "prepare:$index"; state = Player.STATE_BUFFERING; null }
                "play" -> { actions += "play:$index"; playWhenReady = true; null }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SystemTrackSkipPlayer"
                else -> error("Unexpected player call: ${method.name}")
            }
        } as Player
    }
}
