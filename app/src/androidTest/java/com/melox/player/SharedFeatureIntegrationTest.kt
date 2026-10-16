package com.melox.player

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.melox.player.data.playback.PlaybackSnapshotStore
import com.melox.player.model.PlaybackMode
import com.melox.player.model.PlaybackQueueItem
import com.melox.player.model.PlaybackSnapshot
import com.melox.player.playback.PlaybackController
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedFeatureIntegrationTest {
    @Test
    fun positionOnlyCheckpointLeavesTheFullQueueFileUnchangedAndRestoresProgress() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "checkpoint-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        try {
            val store = PlaybackSnapshotStore(isolated)
            val queue = listOf("one", "two").map {
                PlaybackQueueItem(it, null, "content://$it", it, null, null, 10_000L, 1L, 1L)
            }
            val initial = PlaybackSnapshot(queue, 0, 123L, PlaybackMode.ORDER)
            store.save(initial)
            val fullFile = File(directory, "playback_snapshot.bin")
            val fullBytes = fullFile.readBytes()
            val progress = initial.copy(currentIndex = 1, positionMs = 9_000L)
            store.save(progress)
            assertArrayEquals(fullBytes, fullFile.readBytes())
            assertEquals(36L, File(directory, "playback_position_checkpoint.bin").length())
            assertEquals(progress, PlaybackSnapshotStore(isolated).loadUnvalidated())
            val reordered = progress.copy(queue = queue.reversed())
            store.save(reordered)
            assertFalse(fullBytes.contentEquals(fullFile.readBytes()))
            assertEquals(reordered, PlaybackSnapshotStore(isolated).loadUnvalidated())
            store.save(initial.copy(queue = emptyList(), currentIndex = -1))
            assertNull(PlaybackSnapshotStore(isolated).loadUnvalidated())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun sleepTimerSurvivesAUiControllerReleaseAndReconnect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var first: PlaybackController? = null
        var second: PlaybackController? = null
        try {
            instrumentation.runOnMainSync { first = PlaybackController(instrumentation.targetContext) }
            await { first!!.state.value.positionUpdateElapsedRealtimeMs > 0L }
            instrumentation.runOnMainSync { first!!.startSleepTimer(120) }
            // Wait for the service acknowledgement before releasing the UI owner.
            await { first!!.sleepTimerState.value.remainingSeconds in 1..119 }
            instrumentation.runOnMainSync {
                first!!.release()
                first = null
                second = PlaybackController(instrumentation.targetContext)
            }
            val reconnected = checkNotNull(second)
            await { reconnected.sleepTimerState.value.active }
            assertTrue(reconnected.sleepTimerState.value.remainingSeconds in 1..119)
            instrumentation.runOnMainSync { reconnected.cancelSleepTimer() }
            await { !reconnected.sleepTimerState.value.active }
        } finally {
            instrumentation.runOnMainSync {
                second?.cancelSleepTimer()
                first?.cancelSleepTimer()
                first?.release()
                second?.release()
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for playback state" }
            Thread.sleep(10L)
        }
    }
}
