package com.melox.player

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.MotionDurationScale
import com.melox.player.ui.component.playback.TrackSkipIconMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrackSkipIconMotionTest {
    private val clock = BroadcastFrameClock()
    private var time = 0L
    private var onFrame: () -> Unit = {}

    private suspend fun frame() {
        repeat(4) { yield() }
        time += 16_666_667L
        clock.sendFrame(time)
        repeat(4) { yield() }
        onFrame()
    }

    private suspend fun finish(job: Job) {
        repeat(120) {
            if (!job.isActive) return
            frame()
        }
        assertTrue("Skip feedback did not settle", job.isCompleted)
    }

    @Test
    fun pushesBothPartsBeforeReturningToRest() = runBlocking(clock) {
        val motion = TrackSkipIconMotion()
        val samples = mutableListOf("time_ms,triangle,bar,scale,bar_height")
        onFrame = {
            samples += "${time / 1_000_000.0},${motion.triangle.value},${motion.bar.value},${motion.scale.value},${motion.barHeight.value}"
        }
        val job = launch { motion.play() }
        repeat(5) { frame() }
        assertTrue(motion.triangle.value > 0.9f)
        assertTrue(motion.bar.value < motion.triangle.value)
        assertTrue("The bar shortens after the triangle starts moving", motion.barHeight.value > 0.99f)
        repeat(8) { frame() }
        assertTrue(motion.bar.value > motion.triangle.value)
        assertTrue(motion.scale.value < 0.93f)
        assertTrue("Bar height changes independently of whole-icon scale", motion.barHeight.value < 0.87f)
        repeat(10) { frame() }
        assertEquals(0f, motion.triangle.value, 0.01f)
        assertTrue(motion.bar.value > 0f)
        assertTrue("The shorter bar is still recovering after the triangle rests", motion.barHeight.value < 0.99f)
        finish(job)
        assertEquals(0f, motion.triangle.value, 0f)
        assertEquals(0f, motion.bar.value, 0f)
        assertEquals(1f, motion.scale.value, 0f)
        assertEquals(1f, motion.barHeight.value, 0f)
        val minimumHeight = samples.drop(1).minOf { it.substringAfterLast(',').toFloat() }
        assertTrue("Reference cycles shorten the bar to about 84 percent", minimumHeight in 0.84f..0.85f)
        assertTrue("Feedback should fit the reference tail", time in 500_000_000L..900_000_000L)
        File("build/reports/reference-controls/motion.csv").apply {
            parentFile?.mkdirs()
            writeText(samples.joinToString("\n"))
        }
        Unit
    }

    @Test
    fun retriggerDuringReturnKeepsTheCurrentPosition() = runBlocking(clock) {
        val motion = TrackSkipIconMotion()
        val first = launch { motion.play() }
        repeat(25) { frame() }
        val current = motion.bar.value
        val currentScale = motion.scale.value
        val currentHeight = motion.barHeight.value
        assertTrue(current > 0f && current < 1f)
        first.cancelAndJoin()
        assertEquals(current, motion.bar.value, 0f)
        val second = launch { motion.play() }
        yield()
        assertEquals(current, motion.bar.value, 0f)
        assertEquals(currentScale, motion.scale.value, 0f)
        assertEquals(currentHeight, motion.barHeight.value, 0f)
        repeat(5) { frame() }
        assertTrue(motion.bar.value > current)
        assertTrue(motion.barHeight.value < currentHeight)
        finish(second)
        assertEquals(0f, motion.triangle.value, 0f)
        assertEquals(0f, motion.bar.value, 0f)
        assertEquals(1f, motion.scale.value, 0f)
        assertEquals(1f, motion.barHeight.value, 0f)
    }

    @Test
    fun disabledSystemAnimationsLeaveNoOffset() {
        val durationScale = object : MotionDurationScale {
            override val scaleFactor = 0f
        }
        runBlocking(clock + durationScale) {
            val motion = TrackSkipIconMotion()
            val job = launch { motion.play() }
            finish(job)
            assertEquals(0f, motion.triangle.value, 0f)
            assertEquals(0f, motion.bar.value, 0f)
            assertEquals(1f, motion.scale.value, 0f)
            assertEquals(1f, motion.barHeight.value, 0f)
            assertTrue(time <= 66_666_668L)
        }
    }
}
