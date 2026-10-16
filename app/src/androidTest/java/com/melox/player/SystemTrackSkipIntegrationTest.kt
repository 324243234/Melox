package com.melox.player

import android.media.session.MediaController as PlatformMediaController
import android.media.session.PlaybackState
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.melox.player.playback.resumeAfterSystemTrackSkip
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemTrackSkipIntegrationTest {
    @Test
    fun modernPausedSkipsSeekBeforeResumingAndExposeIntentDuringBuffering() {
        for (next in listOf(true, false)) {
            val fixture = Fixture()
            try {
                fixture.onMain {
                    if (next) fixture.controller.seekToNext() else fixture.controller.seekToPrevious()
                }
                fixture.await { fixture.finishedInteractions > 0 }
                val index = if (next) 2 else 0
                assertEquals(listOf("seek:$index", "play:$index"), fixture.player.actions.toList())
                fixture.awaitOnMain { fixture.controller.playWhenReady }
                fixture.onMain { assertFalse(fixture.controller.isPlaying) }
            } finally { fixture.close() }
        }
    }

    @Test
    fun anExplicitPauseInTheSameModernCommandBatchWinsOverSkipAutoplay() {
        val fixture = Fixture()
        try {
            fixture.onMain { fixture.controller.seekToNext(); fixture.controller.pause() }
            fixture.await { fixture.finishedInteractions > 0 }
            assertEquals(listOf("seek:2"), fixture.player.actions.toList())
            fixture.onMain { assertFalse(fixture.player.playWhenReady) }
        } finally { fixture.close() }
    }

    @Test
    fun platformSystemControlsAlsoSeekBeforeResumingAndPublishBufferingState() {
        for (next in listOf(true, false)) {
            val fixture = Fixture()
            try {
                val platform = PlatformMediaController(fixture.context, fixture.session.platformToken)
                fixture.onMain {
                    if (next) platform.transportControls.skipToNext()
                    else platform.transportControls.skipToPrevious()
                }
                fixture.await { fixture.finishedInteractions > 0 }
                val index = if (next) 2 else 0
                assertEquals(listOf("seek:$index", "play:$index"), fixture.player.actions.toList())
                fixture.await { platform.playbackState?.state == PlaybackState.STATE_BUFFERING }
                fixture.onMain {
                    assertTrue(fixture.player.playWhenReady)
                    assertFalse(fixture.player.isPlaying)
                }
            } finally { fixture.close() }
        }
    }

    private class Fixture {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var player: BufferingPlayer
        lateinit var session: MediaSession
        lateinit var controller: MediaController
        @Volatile var finishedInteractions = 0

        init {
            lateinit var future: ListenableFuture<MediaController>
            onMain {
                player = BufferingPlayer()
                session = MediaSession.Builder(context, player)
                    .setId("skip-test-${UUID.randomUUID()}")
                    .setCallback(object : MediaSession.Callback {
                        override fun onPlayerInteractionFinished(session: MediaSession,
                            controller: MediaSession.ControllerInfo, commands: Player.Commands) {
                            session.player.resumeAfterSystemTrackSkip(commands)
                            finishedInteractions++
                        }
                    }).build()
                future = MediaController.Builder(context, session.token).buildAsync()
            }
            controller = future.get(5, TimeUnit.SECONDS)
        }

        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun awaitOnMain(condition: () -> Boolean) = await {
            var satisfied = false
            onMain { satisfied = condition() }
            satisfied
        }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            while (!condition()) {
                check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for system skip" }
                Thread.sleep(10L)
            }
        }
        fun close() = onMain { controller.release(); session.release(); player.release() }
    }

    private class BufferingPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        val actions = CopyOnWriteArrayList<String>()
        private var current = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA,
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            ).build())
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(List(3) { index -> MediaItemData.Builder(index)
                .setMediaItem(MediaItem.Builder().setMediaId("track-$index").build())
                .setIsSeekable(true).setDurationUs(10_000_000L).build() })
            .setCurrentMediaItemIndex(1).setContentPositionMs(0L).build()

        override fun getState(): State = current

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            actions += "seek:$mediaItemIndex"
            current = current.buildUpon().setCurrentMediaItemIndex(mediaItemIndex)
                .setContentPositionMs(0L).setPlaybackState(Player.STATE_BUFFERING).build()
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            actions += if (playWhenReady) "play:${current.currentMediaItemIndex}"
                else "pause:${current.currentMediaItemIndex}"
            current = current.buildUpon()
                .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
            return Futures.immediateVoidFuture()
        }
    }
}
