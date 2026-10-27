package com.melox.player

import android.net.Uri
import android.content.ComponentName
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.melox.player.model.MusicTrack
import com.melox.player.playback.PlaybackController
import com.melox.player.playback.PlaybackService
import com.melox.player.ui.screen.playback.LyricPlaybackClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricTrackRestartTest {
    @Test
    fun rapidSongListRoundTripResetsTheRetainedLyricClock() = runPlaybackChecks(checkSeeks = false)

    @Test
    fun pausedSystemSessionSeeksResetTheRetainedLyricClock() = runPlaybackChecks(checkSeeks = true)

    private fun runPlaybackChecks(checkSeeks: Boolean) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val files = listOf("lyric-restart-a.wav", "lyric-restart-b.wav").map { name ->
            File(context.cacheDir, name).apply {
                val pcmSize = 8_000 * 2 * 60
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                    .put("RIFF".toByteArray()).putInt(pcmSize + 36).put("WAVEfmt ".toByteArray())
                    .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
                    .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcmSize).array()
                outputStream().use { it.write(header); it.write(ByteArray(pcmSize)) }
            }
        }
        val tracks = files.mapIndexed { index, file ->
            MusicTrack(id = -100L - index, title = file.name, artist = null, album = null,
                durationMs = 60_000, dateAddedEpochSeconds = 0, dateModifiedEpochSeconds = 0,
                fileName = file.name, fileSizeBytes = file.length(), contentUri = Uri.fromFile(file).toString(),
                titleSectionKey = "L", titleSortKey = file.name)
        }
        lateinit var controller: PlaybackController
        ActivityScenario.launch(MainActivity::class.java).use {
            instrumentation.runOnMainSync { controller = PlaybackController(context) }
            try {
                instrumentation.runOnMainSync { controller.playQueue(tracks, 0) }
                withTimeout(15_000) { controller.state.first { it.isPlaying && it.currentItem?.trackId == tracks[0].id } }
                if (!checkSeeks) repeat(3) {
                    // Wait for a fresh service sample after the preceding queue replacement.
                    val previousUpdate = controller.state.value.positionUpdateElapsedRealtimeMs
                    withTimeout(10_000) {
                        controller.state.first {
                            it.isPlaying && it.currentItem?.trackId == tracks[0].id &&
                                it.positionUpdateElapsedRealtimeMs > previousUpdate + 500L
                        }
                    }
                    instrumentation.runOnMainSync { controller.seekTo(30_000) }
                    val before = withTimeoutOrNull(10_000) {
                        controller.state.first { it.positionMs >= 30_000 }
                    } ?: error("Seek before round $it was not applied: ${controller.state.value}")
                    val clock = LyricPlaybackClock(before.positionMs, before.playbackIteration)
                    // Both selections happen before a UI collector can render the intervening song.
                    instrumentation.runOnMainSync {
                        controller.playQueue(tracks, 1)
                        controller.playQueue(tracks, 0)
                    }
                    val after = withTimeoutOrNull(10_000) {
                        controller.state.first {
                            it.currentItem?.trackId == tracks[0].id && it.positionMs < 2_000 &&
                                it.playbackIteration >= before.playbackIteration + 2
                        }
                    }
                        ?: error("Round trip did not reset: before=$before, after=${controller.state.value}")
                    assertTrue(clock.resetForIteration(after.playbackIteration, after.positionMs))
                    assertTrue(clock.positionMs.doubleValue < 2_000)
                }
                if (checkSeeks) {
                    // A second session client represents system media controls, without app seek requests.
                    val future = MediaController.Builder(
                        context, SessionToken(context, ComponentName(context, PlaybackService::class.java)),
                    ).buildAsync()
                    val mediaControls = future.get(10, TimeUnit.SECONDS)
                    try {
                        instrumentation.runOnMainSync { mediaControls.pause() }
                        withTimeout(10_000) { controller.state.first { !it.isPlaying } }
                        for (target in listOf(45_000L, 5_000L)) {
                            val before = controller.state.value
                            val clock = LyricPlaybackClock(
                                before.positionMs, before.playbackIteration, before.positionDiscontinuityRevision,
                            )
                            clock.updateFromPlayback(
                                before.playbackIteration, before.positionMs, active = true,
                                positionDiscontinuityRevision = before.positionDiscontinuityRevision,
                            )
                            instrumentation.runOnMainSync { mediaControls.seekTo(target) }
                            val after = withTimeout(10_000) {
                                controller.state.first {
                                    !it.isPlaying && kotlin.math.abs(it.positionMs - target) < 100 &&
                                        it.positionDiscontinuityRevision > before.positionDiscontinuityRevision
                                }
                            }
                            assertTrue(clock.updateFromPlayback(
                                after.playbackIteration, after.positionMs, active = true,
                                positionDiscontinuityRevision = after.positionDiscontinuityRevision,
                            ))
                            assertTrue(kotlin.math.abs(clock.positionMs.doubleValue - target) < 100)
                        }
                    } finally {
                        instrumentation.runOnMainSync { mediaControls.release() }
                    }
                }
            } finally {
                instrumentation.runOnMainSync { controller.clear(); controller.release() }
                files.forEach(File::delete)
            }
        }
    }
}
