package com.melox.player

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import com.melox.player.ui.screen.playback.lyricContentEnterSpec
import com.melox.player.ui.screen.playback.lyricContentExitSpec
import com.melox.player.ui.screen.playback.LYRIC_CONTENT_MIN_SCALE
import com.melox.player.ui.component.playback.PLAYBACK_BACKGROUND_TRANSITION_DURATION_MILLIS
import androidx.compose.ui.MotionDurationScale
import com.melox.player.ui.screen.playback.LyricTranslationMotion
import com.melox.player.ui.screen.playback.lyricTranslationPaddingDp
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class TranslationTestDurationScale(override val scaleFactor: Float) : MotionDurationScale

class LyricTranslationMotionTest {
    @Test
    fun firstLyricsEnterImmediatelyWithoutWaitingForAnOutgoingDocument() {
        val enter = TargetBasedAnimation(lyricContentEnterSpec(fromEmpty = true), Float.VectorConverter, 0f, 1f)
        assertEquals(PLAYBACK_BACKGROUND_TRANSITION_DURATION_MILLIS * 1_000_000L, enter.durationNanos)
        assertTrue(enter.getValueFromNanos(20_000_000) > 0f)
        assertEquals(1f, enter.getValueFromNanos(400_000_000), 0f)
    }

    @Test
    fun lyricFadesMatchTheBackgroundDurationAndOverlapWithoutAnEmptyFrame() {
        val enter = TargetBasedAnimation(lyricContentEnterSpec(), Float.VectorConverter, 0f, 1f)
        val exit = TargetBasedAnimation(lyricContentExitSpec(), Float.VectorConverter, 1f, 0f)
        assertEquals(PLAYBACK_BACKGROUND_TRANSITION_DURATION_MILLIS * 1_000_000L, enter.durationNanos)
        assertEquals(1f, exit.getValueFromNanos(0), 0f)
        assertEquals(0f, enter.getValueFromNanos(0), 0f)
        assertEquals(0f, enter.getValueFromNanos(150_000_000), 0f)
        assertTrue(enter.getValueFromNanos(151_000_000) > 0f)
        assertTrue(exit.getValueFromNanos(249_000_000) > 0f)
        assertEquals(0f, exit.getValueFromNanos(250_000_000), 0f)
        assertEquals(1f, enter.getValueFromNanos(400_000_000), 0f)
        assertTrue(exit.getValueFromNanos(200_000_000) > 0f)
        assertTrue(enter.getValueFromNanos(200_000_000) > 0f)
        for (ms in 0..400) {
            assertTrue("Empty lyric frame at $ms ms",
                exit.getValueFromNanos(ms * 1_000_000L) + enter.getValueFromNanos(ms * 1_000_000L) > 0f)
        }
    }

    @Test
    fun lyricScaleMovesBetweenNinetyTwoPercentAndFullSizeOnTheSameFadeTimeline() {
        val enter = TargetBasedAnimation(lyricContentEnterSpec(), Float.VectorConverter, LYRIC_CONTENT_MIN_SCALE, 1f)
        val exit = TargetBasedAnimation(lyricContentExitSpec(), Float.VectorConverter, 1f, LYRIC_CONTENT_MIN_SCALE)
        assertEquals(0.92f, enter.getValueFromNanos(0), 0f)
        assertEquals(0.92f, exit.getValueFromNanos(250_000_000), 0f)
        assertEquals(1f, enter.getValueFromNanos(400_000_000), 0f)
        assertTrue(enter.getValueFromNanos(200_000_000) in 0.92f..1f)
        assertTrue(exit.getValueFromNanos(200_000_000) in 0.92f..1f)
    }

    private val clock = BroadcastFrameClock()
    private var time = 0L

    private suspend fun frame() {
        yield()
        time += 16_666_667L
        clock.sendFrame(time)
        yield()
    }

    private suspend fun finish(job: Job, check: () -> Unit = {}) {
        repeat(300) {
            if (!job.isActive) return
            frame()
            check()
        }
        assertTrue("Translation transition did not finish", job.isCompleted)
    }

    @Test
    fun closingFadesWhileTheSpaceCollapses() = runBlocking(clock) {
        val motion = LyricTranslationMotion(true)
        val job = launch { motion.transitionTo(false) }
        repeat(6) { frame() }
        assertTrue(motion.opacity.value in 0f..1f && motion.opacity.value < 1f)
        assertTrue(motion.expansion.value in 0f..1f && motion.expansion.value < 1f)
        assertTrue(motion.opacity.value > 0f)
        assertTrue(motion.expansion.value > 0f)
        finish(job)
        assertEquals(0f, motion.expansion.value, 0f)
        assertEquals(0f, motion.opacity.value, 0f)
    }

    @Test
    fun openingFadesInWhileTheSpaceExpands() = runBlocking(clock) {
        val motion = LyricTranslationMotion(false)
        val job = launch { motion.transitionTo(true) }
        repeat(6) { frame() }
        assertTrue(motion.opacity.value > 0f && motion.opacity.value < 1f)
        assertTrue(motion.expansion.value > 0f && motion.expansion.value < 1f)
        finish(job)
        assertEquals(1f, motion.expansion.value, 0f)
        assertEquals(1f, motion.opacity.value, 0f)
    }

    @Test
    fun reversingDuringOpeningKeepsBothCurrentValues() = runBlocking(clock) {
        val motion = LyricTranslationMotion(false)
        val opening = launch { motion.transitionTo(true) }
        repeat(6) { frame() }
        val previousExpansion = motion.expansion.value
        val previousOpacity = motion.opacity.value
        assertTrue(previousExpansion > 0f && previousExpansion < 1f)
        assertTrue(previousOpacity > 0f && previousOpacity < 1f)
        opening.cancelAndJoin()
        assertEquals(previousExpansion, motion.expansion.value, 0f)
        assertEquals(previousOpacity, motion.opacity.value, 0f)
        val closing = launch { motion.transitionTo(false) }
        finish(closing)
        assertEquals(0f, motion.expansion.value, 0f)
        assertEquals(0f, motion.opacity.value, 0f)
    }

    @Test
    fun reversingDuringClosingKeepsBothCurrentValues() = runBlocking(clock) {
        val motion = LyricTranslationMotion(true)
        val closing = launch { motion.transitionTo(false) }
        repeat(6) { frame() }
        val previousExpansion = motion.expansion.value
        val previousOpacity = motion.opacity.value
        assertTrue(previousExpansion > 0f && previousExpansion < 1f)
        assertTrue(previousOpacity > 0f && previousOpacity < 1f)
        closing.cancelAndJoin()
        val opening = launch { motion.transitionTo(true) }
        assertEquals(previousExpansion, motion.expansion.value, 0f)
        assertEquals(previousOpacity, motion.opacity.value, 0f)
        finish(opening)
        assertEquals(1f, motion.expansion.value, 0f)
        assertEquals(1f, motion.opacity.value, 0f)
    }

    @Test
    fun reopeningDuringCollapseResumesBothAnimationsTogether() = runBlocking(clock) {
        val motion = LyricTranslationMotion(true)
        val closing = launch { motion.transitionTo(false) }
        repeat(6) { frame() }
        assertTrue(motion.expansion.value > 0f && motion.expansion.value < 1f)
        assertTrue(motion.opacity.value > 0f && motion.opacity.value < 1f)
        closing.cancelAndJoin()
        val previousExpansion = motion.expansion.value
        val previousOpacity = motion.opacity.value
        val opening = launch { motion.transitionTo(true) }
        repeat(4) { frame() }
        assertTrue(motion.expansion.value != previousExpansion)
        assertTrue(motion.opacity.value > previousOpacity)
        finish(opening)
        assertEquals(1f, motion.expansion.value, 0f)
        assertEquals(1f, motion.opacity.value, 0f)
    }

    @Test
    fun cancelledOpeningCannotRevealAnObsoleteTranslation() = runBlocking(clock) {
        val motion = LyricTranslationMotion(false)
        val opening = launch { motion.transitionTo(true) }
        repeat(6) { frame() }
        opening.cancelAndJoin()
        val closing = launch { motion.transitionTo(false) }
        finish(closing)
        assertEquals(0f, motion.expansion.value, 0f)
        assertEquals(0f, motion.opacity.value, 0f)
    }

    @Test
    fun disabledSystemAnimationsReachBothEndpoints() = runBlocking(clock) {
        val scale = TranslationTestDurationScale(0f)
        val motion = LyricTranslationMotion(false)
        for (visible in listOf(true, false)) {
            val job = launch(scale) { motion.transitionTo(visible) }
            repeat(5) { frame() }
            assertTrue(job.isCompleted)
            val target = if (visible) 1f else 0f
            assertEquals(target, motion.opacity.value, 0f)
            assertEquals(target, motion.expansion.value, 0f)
        }
    }

    @Test
    fun rowsWithoutTranslationKeepTheirSpacingAndTranslatedRowsInterpolate() {
        for (expansion in listOf(0f, 0.5f, 1f)) {
            assertEquals(12f, lyricTranslationPaddingDp(false, expansion), 0f)
        }
        assertEquals(12f, lyricTranslationPaddingDp(true, 0f), 0f)
        assertEquals(11f, lyricTranslationPaddingDp(true, 0.5f), 0f)
        assertEquals(10f, lyricTranslationPaddingDp(true, 1f), 0f)
    }

    @Test
    fun fontSizeChangesSpacingGentlyAndKeepsTranslationInterpolationContinuous() {
        for ((fontScale, hidden, shown) in listOf(
            Triple(2f / 3f, 11f, 55f / 6f),
            Triple(1f, 12f, 10f),
            Triple(2f, 15f, 12.5f),
        )) {
            for (expansion in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                assertEquals(hidden, lyricTranslationPaddingDp(false, expansion, fontScale), 0.0001f)
                assertEquals(hidden + (shown - hidden) * expansion,
                    lyricTranslationPaddingDp(true, expansion, fontScale), 0.0001f)
            }
        }
    }
}
