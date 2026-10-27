package com.melox.player

import androidx.media3.common.Player
import com.melox.player.model.PlaybackMode
import com.melox.player.model.PlaybackQueueItem
import com.melox.player.playback.PlaybackModeMemory
import com.melox.player.playback.applyPlaybackMode
import com.melox.player.playback.currentPlaybackMode
import com.melox.player.playback.nextPlaybackMode
import com.melox.player.playback.reorderQueueForPlaybackMode
import java.lang.reflect.Proxy
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EmptyPlaybackModeTest {
    @Test
    fun emptyQueueCyclesAllModesWithoutPreparingOrChangingMediaItems() = withEmptyPlayer { player ->
        repeat(2) {
            for (expected in listOf(PlaybackMode.REPEAT_ONE, PlaybackMode.RANDOM, PlaybackMode.ORDER)) {
                val target = nextPlaybackMode(player.currentPlaybackMode())
                val reordered = reorderQueueForPlaybackMode(emptyList(), -1, target)
                player.applyPlaybackMode(target, reordered)

                assertEquals(expected, player.currentPlaybackMode())
                assertEquals(expected, PlaybackModeMemory.get())
                assertEquals(emptyList<PlaybackQueueItem>(), reordered.queue)
                assertEquals(-1, reordered.currentIndex)
                assertEquals(0, player.mediaItemCount)
                assertEquals(-1, player.currentMediaItemIndex)
                assertFalse(player.shuffleModeEnabled)
            }
        }
    }

    @Test
    fun nextQueueUsesTheModeSelectedWhileEmpty() = withEmptyPlayer { player ->
        val queue = listOf("a", "b", "c").mapIndexed { index, id ->
            PlaybackQueueItem(
                mediaId = id,
                trackId = null,
                contentUri = "content://test/$id",
                title = id,
                artist = null,
                album = null,
                durationMs = 1_000,
                dateModifiedEpochSeconds = 0,
                fileSizeBytes = 0,
                sourceOrder = index.toDouble(),
            )
        }
        for (mode in PlaybackMode.entries) {
            player.applyPlaybackMode(mode, reorderQueueForPlaybackMode(emptyList(), -1, mode))
            val inherited = player.currentPlaybackMode()
            val next = reorderQueueForPlaybackMode(queue, 1, inherited, Random(7))

            assertEquals(mode, inherited)
            assertEquals(setOf(mode), next.queue.map { it.playbackMode }.toSet())
            assertEquals("b", next.queue[next.currentIndex].mediaId)
            assertEquals(
                if (mode == PlaybackMode.RANDOM) 0 else 1,
                next.currentIndex,
            )
            if (mode != PlaybackMode.RANDOM) {
                assertEquals(listOf("a", "b", "c"), next.queue.map { it.mediaId })
            }
        }
    }

    private fun withEmptyPlayer(check: (Player) -> Unit) {
        val previousMode = PlaybackModeMemory.get()
        var repeatMode = Player.REPEAT_MODE_ALL
        var shuffleEnabled = false
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) {
            _, method, arguments ->
            when (method.name) {
                "getMediaItemCount" -> 0
                "getCurrentMediaItemIndex" -> -1
                "getRepeatMode" -> repeatMode
                "setRepeatMode" -> { repeatMode = arguments!![0] as Int; null }
                "getShuffleModeEnabled" -> shuffleEnabled
                "setShuffleModeEnabled" -> { shuffleEnabled = arguments!![0] as Boolean; null }
                else -> error("Empty mode selection must not call ${method.name}")
            }
        } as Player
        try {
            PlaybackModeMemory.set(PlaybackMode.ORDER)
            check(player)
        } finally {
            PlaybackModeMemory.set(previousMode)
        }
    }
}
