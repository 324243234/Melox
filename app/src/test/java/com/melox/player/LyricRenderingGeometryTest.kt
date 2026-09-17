package com.melox.player

import androidx.compose.ui.geometry.Offset
import com.melox.player.ui.screen.playback.characterMotion
import com.melox.player.ui.screen.playback.lyricCenterScrollDelta
import com.melox.player.ui.screen.playback.lyricCharacterPivot
import com.melox.player.ui.screen.playback.lyricGlyphOutsetPx
import com.melox.player.ui.screen.playback.lyricRetainedTravelPx
import com.melox.player.ui.screen.playback.lyricActualPlacementTranslation
import com.melox.player.ui.screen.playback.lyricRetentionIsMeasured
import com.melox.player.ui.screen.playback.lyricCharacterFloatOffset
import com.melox.player.ui.screen.playback.LYRIC_FLOAT_MAX_OFFSET_PX
import org.junit.Assert.assertFalse
import com.melox.player.ui.screen.playback.lyricLineMotionStrength
import com.melox.player.ui.screen.playback.withLineStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.sqrt

class LyricRenderingGeometryTest {
    @Test
    fun completedVisualRowsStayRaisedUntilTheLogicalSentenceReleases() {
        val firstRowAtItsEnd = characterMotion(2_000L, 1_000L, 2_000L, 2, 3)
        val firstRowWhileNextRowPlays = characterMotion(4_000L, 1_000L, 2_000L, 2, 3)
        assertEquals(firstRowAtItsEnd, firstRowWhileNextRowPlays)
        assertEquals(-LYRIC_FLOAT_MAX_OFFSET_PX, firstRowWhileNextRowPlays.offsetYPx, 0f)
        val halfReleased = firstRowWhileNextRowPlays.withLineStrength(lyricLineMotionStrength(0.7f))
        assertEquals(-LYRIC_FLOAT_MAX_OFFSET_PX / 2f, halfReleased.offsetYPx, 0.0001f)
        val resting = firstRowWhileNextRowPlays.withLineStrength(lyricLineMotionStrength(0.4f))
        assertEquals(0f, resting.offsetYPx, 0f)
        assertEquals(1f, resting.scale, 0f)
        assertEquals(0f, resting.glowAlpha, 0f)
    }

    @Test
    fun sentenceReleaseContinuouslyReturnsScaleGlowAndFloatToRest() {
        val playing = characterMotion(1_400L, 1_000L, 2_000L, 0, 4)
        var previous = playing
        for (step in 0..60) {
            val alpha = 1f - step * 0.01f
            val current = playing.withLineStrength(lyricLineMotionStrength(alpha))
            assertTrue(current.scale <= previous.scale + 0.0001f)
            assertTrue(current.glowAlpha <= previous.glowAlpha + 0.0001f)
            assertTrue(current.offsetYPx >= previous.offsetYPx - 0.0001f)
            assertTrue(kotlin.math.abs(current.offsetYPx - previous.offsetYPx) < 0.1f)
            previous = current
        }
        assertEquals(0f, previous.offsetYPx, 0.0001f)
        assertEquals(1f, previous.scale, 0.0001f)
        assertEquals(0f, previous.glowAlpha, 0.0001f)
    }

    @Test
    fun unstartedCharacterNeverHasAGlowEvenDuringSentenceFadeIn() {
        for (alpha in listOf(0.4f, 0.7f, 1f)) {
            val motion = characterMotion(900L, 1_000L, 2_000L, 0, 4)
                .withLineStrength(lyricLineMotionStrength(alpha))
            assertEquals(0f, motion.glowAlpha, 0f)
            assertEquals(0f, motion.offsetYPx, 0f)
            assertEquals(1f, motion.scale, 0f)
        }
    }

    @Test
    fun retainedViewportMustBeMeasuredTogetherWithBothPaddings() {
        assertFalse(lyricRetentionIsMeasured(640, 580, 840, 900, 900))
        assertFalse(lyricRetentionIsMeasured(640, 580, 1800, 420, 420))
        assertTrue(lyricRetentionIsMeasured(640, 580, 1800, 900, 900))
        assertTrue(lyricRetentionIsMeasured(641, 580, 1801, 900, 900))
    }

    @Test
    fun tailSeekCompensatesActualPlacementEvenWhenTheListConsumesNothing() {
        assertEquals(0f, lyricActualPlacementTranslation(0f, -20, -20, 300f), 0f)
        assertEquals(60f, lyricActualPlacementTranslation(10f, 30, -20, 300f), 0f)
        assertEquals(-40f, lyricActualPlacementTranslation(10f, -20, 30, -300f), 0f)
        assertEquals(300f, lyricActualPlacementTranslation(10f, null, -20, 300f), 0f)
    }

    @Test
    fun actualPlacementPreservesEveryVisibleTargetsScreenPosition() {
        for (before in listOf(-400, -20, 0, 250, 600)) {
            for (after in listOf(-40, -20, 0, 30)) {
                val translation = lyricActualPlacementTranslation(12f, before, after, 999f)
                assertEquals(before + 12f, after + translation, 0f)
            }
        }
    }

    @Test
    fun longSpanEmphasisIsRestrictedToALocalCharacterWindow() {
        val count = 40
        for (time in 0L..12_000L step 100) {
            val glowing = (0 until count).count {
                characterMotion(time, 0L, 12_000L, it, count).glowAlpha > 0.001f
            }
            assertTrue("Too many characters emphasized at $time: $glowing", glowing <= 5)
        }
    }

    @Test
    fun riseUsesTheWholeTimestampIntervalAndHasNoFixedSevenHundredMsCutoff() {
        assertEquals(0f, lyricCharacterFloatOffset(1_000L, 1_000L, 5_000L, 0, 1), 0f)
        val early = lyricCharacterFloatOffset(1_700L, 1_000L, 5_000L, 0, 1)
        val middle = lyricCharacterFloatOffset(3_000L, 1_000L, 5_000L, 0, 1)
        assertTrue(early < 0f && early > middle)
        assertEquals(-2f, middle, 0.0001f)
        assertEquals(-LYRIC_FLOAT_MAX_OFFSET_PX, lyricCharacterFloatOffset(5_000L, 1_000L, 5_000L, 0, 1), 0f)
    }

    @Test
    fun riseIsContinuousAndMonotonicAcrossCharacterAndRowBoundaries() {
        for (count in listOf(1, 4, 40)) {
            for (index in 0 until count) {
                var previous = 0f
                for (time in 0L..8_100L step 1) {
                    val current = lyricCharacterFloatOffset(time, 1_000L, 8_000L, index, count)
                    assertTrue(current <= previous + 0.0001f)
                    assertTrue(previous - current < 0.03f)
                    previous = current
                }
                assertEquals(-LYRIC_FLOAT_MAX_OFFSET_PX, previous, 0f)
            }
        }
    }

    @Test
    fun retainedViewportCoversBothSeekDirectionsAndSpringRetargetVelocity() {
        val viewportHeight = 640f
        for (start in listOf(-480f, -72f, 0f, 72f, 480f)) {
            for (velocity in listOf(-3_000f, 0f, 3_000f)) {
                val stiffness = 120f
                val reserve = 100f + lyricRetainedTravelPx(start, velocity, stiffness)
                val frequency = sqrt(stiffness)
                for (frame in 0..300) {
                    val time = frame / 60f
                    val translation = (start + (velocity + frequency * start) * time) *
                        exp(-frequency * time)
                    assertTrue(-reserve + translation <= 0f)
                    assertTrue(viewportHeight + reserve + translation >= viewportHeight)
                }
            }
        }
    }

    @Test
    fun symmetricRetentionLeavesTheFocusCenterUnchanged() {
        val height = 640
        for (reserve in listOf(100, 580, 1_200)) {
            val beforePadding = height / 2 + reserve
            val measuredHeight = height + reserve * 2
            assertEquals(
                240f,
                lyricCenterScrollDelta(
                    itemOffset = 220,
                    itemSize = 40,
                    viewportStartOffset = -beforePadding,
                    viewportEndOffset = measuredHeight - beforePadding,
                ),
                0f,
            )
        }
    }

    @Test
    fun glyphScaleKeepsItsOwnHorizontalCenterRegardlessOfLinePosition() {
        for (x in listOf(0f, 100f, 400f)) {
            val position = Offset(x, 20f)
            val pivot = lyricCharacterPivot(position, width = 24f, height = 32f)
            val scaledLeft = pivot.x + (position.x - pivot.x) * 1.12f
            val scaledRight = pivot.x + (position.x + 24f - pivot.x) * 1.12f
            assertEquals(x + 12f, (scaledLeft + scaledRight) / 2f, 0.0001f)
            assertEquals(52f, pivot.y, 0f)
            assertTrue(position.x - scaledLeft < 2f)
        }
    }

    @Test
    fun glyphRasterOutsetContainsScaleGlowAndFloatAtAllFontSizes() {
        for (height in listOf(16f, 28f, 112f, 224f)) {
            val outset = lyricGlyphOutsetPx(height)
            for (time in 0L..3_000L step 30) {
                val motion = characterMotion(time, 0L, 3_000L, 1, 4)
                val scaleOverflow = (motion.scale - 1f) * height
                val glowOverflow = 3f * motion.glowRadius * motion.scale
                assertTrue(scaleOverflow + glowOverflow + kotlin.math.abs(motion.offsetYPx) <= outset + 0.001f)
            }
        }
    }

    @Test
    fun glyphPulseStartsAndEndsLocallyWithinItsOwnSyllableTime() {
        val first = characterMotion(1_100L, 1_000L, 2_000L, 0, 4)
        val last = characterMotion(1_100L, 1_000L, 2_000L, 3, 4)
        assertTrue(first.scale > 1f)
        assertEquals(1f, last.scale, 0f)
        assertEquals(0f, last.glowAlpha, 0f)
        val completed = characterMotion(2_000L, 1_000L, 2_000L, 3, 4)
        assertEquals(1f, completed.scale, 0.0001f)
        assertEquals(0f, completed.glowAlpha, 0.0001f)
    }
}
