package com.melox.player

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.melox.player.ui.screen.playback.LyricCenteringEvent
import com.melox.player.ui.screen.playback.LyricRowMotion
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricCascadeCompositionTest {
    private val clock = BroadcastFrameClock()
    private var frameNanos = 0L

    private suspend fun frame() {
        yield()
        frameNanos += 16_666_667L
        clock.sendFrame(frameNanos)
        yield()
    }

    @Test
    fun playbackAcknowledgementDoesNotCancelTheComposeEffectButAnotherTapDoes() = runBlocking(clock) {
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        val recomposeJob = launch { recomposer.runRecomposeAndApplyChanges() }
        val pending = mutableStateOf(true)
        val tapRevision = mutableIntStateOf(1)
        var starts = 0
        var cancellations = 0
        val motion = LyricRowMotion()
        motion.place(emptyMap(), mapOf(0 to 0, 1 to 80, 2 to 160), 180f, 0f, true)
        try {
            composition.setContent {
                val position = if (pending.value) 5_000L else 5_020L
                val event = LyricCenteringEvent(
                    focusIndex = (position / 1_000L).toInt(),
                    requestedPositionMs = 5_000L,
                    tapRevision = tapRevision.intValue,
                    browsing = false,
                    previewing = false,
                    geometry = listOf(600, 800),
                )
                LaunchedEffect(event) {
                    starts++
                    try {
                        motion.settle(80f, listOf(0, 1, 2), cascade = true)
                    } finally {
                        cancellations++
                    }
                }
            }
            repeat(3) { frame() }
            assertEquals(1, starts)
            pending.value = false
            Snapshot.sendApplyNotifications()
            repeat(2) { frame() }
            assertEquals("Acknowledgement restarted the cascade", 1, starts)
            assertEquals(0, cancellations)
            tapRevision.intValue++
            Snapshot.sendApplyNotifications()
            repeat(3) { frame() }
            assertEquals("Repeated tap on the same lyric was ignored", 2, starts)
            assertEquals(1, cancellations)
        } finally {
            composition.dispose()
            recomposer.close()
            recomposeJob.cancelAndJoin()
        }
    }

}
