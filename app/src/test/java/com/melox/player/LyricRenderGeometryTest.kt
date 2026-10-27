package com.melox.player

import com.melox.player.ui.screen.playback.LyricTimingCursor
import com.melox.player.ui.screen.playback.LyricParagraphClock
import com.melox.player.ui.screen.playback.LyricTimedDistance
import com.melox.player.ui.screen.playback.LyricRevealPiece
import com.melox.player.ui.screen.playback.lyricRevealMaskStops
import com.melox.player.ui.screen.playback.lyricRevealClipRects
import com.melox.player.ui.screen.playback.lyricRevealPosition
import com.melox.player.ui.screen.playback.lyricUsesJoinedGlyphs
import com.melox.player.ui.screen.playback.lyricEffectGeometryScale
import com.melox.player.ui.screen.playback.lyricForegroundFadeWidthPx
import com.melox.player.ui.screen.playback.lyricGraphemeRanges
import com.melox.player.ui.screen.playback.lyricTextChunks
import com.melox.player.ui.screen.playback.lyricWordEffects
import com.melox.player.ui.screen.playback.lyricChunkTimeMs
import com.melox.player.ui.screen.playback.lyricFadeBounds
import com.melox.player.ui.screen.playback.lyricGlyphRevealProgress
import com.melox.player.ui.screen.playback.lyricRevealFloatOffset
import com.melox.player.ui.screen.playback.lyricGlyphVisualBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import com.melox.player.ui.screen.playback.lyricLayerOutsets
import com.melox.player.data.lyrics.LyricsParser
import com.melox.player.model.LyricsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricRenderGeometryTest {
    @Test
    fun pronunciationFragmentsShareOneOriginalClockInsteadOfRestartingAtTheSameTime() {
        val clock = LyricParagraphClock(listOf(
            LyricTimedDistance(0, 1_000, 0f, 100f),
            LyricTimedDistance(1_500, 2_500, 100f, 200f),
            LyricTimedDistance(2_500, 3_500, 200f, 300f),
        ))
        for ((time, expected) in listOf(
            500L to listOf(0.5f, 0f, 0f),
            1_250L to listOf(1f, 0f, 0f),
            2_000L to listOf(1f, 0.5f, 0f),
            3_000L to listOf(1f, 1f, 0.5f),
            500L to listOf(0.5f, 0f, 0f),
        )) {
            assertEquals(expected, (0..2).map { clock.progressBetween(time, it * 100f, (it + 1) * 100f) })
        }
    }

    @Test
    fun rtlHighlightAndScrollFollowReadingOrderAcrossRowsAndReverseSeeks() {
        val first = listOf(LyricRevealPiece(120f, 20f, 0f, 100f))
        val wrapped = listOf(LyricRevealPiece(90f, 10f, 100f, 180f))
        val clock = LyricParagraphClock(listOf(LyricTimedDistance(0, 1_800, 0f, 180f)))
        for (time in listOf(0L, 500L, 1_000L, 1_400L, 1_800L, 1_400L, 500L, 0L)) {
            val distance = clock.distanceAt(time)
            assertEquals(120f - distance.coerceIn(0f, 100f),
                lyricRevealPosition(first, distance), 0.0001f)
            assertEquals(90f - (distance - 100f).coerceIn(0f, 80f),
                lyricRevealPosition(wrapped, distance), 0.0001f)
        }
        assertEquals(listOf(Rect(70f, 0f, 120f, 20f)),
            lyricRevealClipRects(first, 0.5f, 0f, 20f))
        val mask = lyricRevealMaskStops(first,
            com.melox.player.ui.screen.playback.LyricFadeBounds(40f, 60f), 1f, 0.4f)
        assertEquals(0.4f, mask.first().second, 0f)
        assertEquals(1f, mask.last().second, 0f)
        assertTrue(mask.zipWithNext().all { (left, right) -> left.second <= right.second })
    }

    @Test
    fun mixedDirectionCursorKeepsLatinAndNumbersInTheirOwnReadingDirection() {
        val pieces = listOf(
            LyricRevealPiece(140f, 100f, 0f, 40f),
            LyricRevealPiece(20f, 80f, 40f, 100f),
            LyricRevealPiece(20f, 0f, 100f, 120f),
        )
        for ((distance, x) in listOf(-1f to 140f, 20f to 120f, 40f to 20f,
            70f to 50f, 100f to 20f, 110f to 10f, 120f to 0f)) {
            assertEquals(x, lyricRevealPosition(pieces, distance), 0.0001f)
        }
    }

    @Test
    fun readingDirectionDoesNotMakeHebrewLettersContextuallyJoined() {
        assertFalse(lyricUsesJoinedGlyphs("שָׁלוֹם"))
        assertFalse(lyricUsesJoinedGlyphs("hello 123"))
        for (text in listOf("مرحبا", "سلام", "ܫܠܡܐ", "नमस्ते")) {
            assertTrue("Joined shaping lost for $text", lyricUsesJoinedGlyphs(text))
        }
    }

    @Test
    fun pronunciationRevealsFollowLogicalDirectionsAndEachStackedTracksOwnRow() {
        val mixed = listOf(
            LyricRevealPiece(0f, 20f, 0f, 20f),
            LyricRevealPiece(80f, 40f, 20f, 60f),
            LyricRevealPiece(100f, 120f, 60f, 80f),
        )
        assertTrue(lyricRevealClipRects(mixed, 0f, 0f, 16f).isEmpty())
        assertEquals(listOf(Rect(0f, 0f, 20f, 16f), Rect(60f, 0f, 80f, 16f)),
            lyricRevealClipRects(mixed, 0.5f, 0f, 16f))
        assertEquals(listOf(Rect(0f, 0f, 20f, 16f), Rect(40f, 0f, 80f, 16f),
            Rect(100f, 0f, 120f, 16f)), lyricRevealClipRects(mixed, 1f, 0f, 16f))
        // A second track has its own width and clock origin, so both reach halfway together.
        val second = listOf(LyricRevealPiece(50f, 0f, 80f, 130f))
        assertEquals(listOf(Rect(25f, 16f, 50f, 32f)),
            lyricRevealClipRects(second, 0.5f, 16f, 32f))
        for (step in 100 downTo 0) {
            val clips = lyricRevealClipRects(mixed, step / 100f, 0f, 16f)
            assertEquals(step * 0.8f, clips.sumOf { it.width.toDouble() }.toFloat(), 0.0001f)
        }
    }

    @Test
    fun zeroAndPositiveInsetBoundsProduceValidLayerOutsetsForDuetRows() {
        for (bounds in listOf(Rect(0f, 0f, 300f, 90f), Rect(60f, 10f, 290f, 80f))) {
            val outsets = lyricLayerOutsets(bounds, 300f, 90f, Density(3f))
            assertEquals(0, outsets.left.value.toRawBits())
            assertEquals(0, outsets.top.value.toRawBits())
            assertEquals(0f, outsets.right.value, 0f)
            assertEquals(0f, outsets.bottom.value, 0f)
        }
        val expanded = lyricLayerOutsets(Rect(-12f, -8f, 320f, 106f), 300f, 90f, Density(2f))
        assertEquals(6f, expanded.left.value, 0f)
        assertEquals(4f, expanded.top.value, 0f)
        assertEquals(10f, expanded.right.value, 0f)
        assertEquals(8f, expanded.bottom.value, 0f)
    }

    @Test
    fun paragraphClockKeepsFutureRowsUnstartedAcrossUnequalWidthsGapsAndReverseSeeks() {
        val clock = LyricParagraphClock(listOf(
            LyricTimedDistance(1_000, 5_000, 0f, 240f),
            LyricTimedDistance(6_000, 7_000, 240f, 300f),
            LyricTimedDistance(7_000, 9_000, 300f, 540f),
        ))
        val expected = listOf(0L to 0f, 1_000L to 0f, 3_000L to 120f,
            5_500L to 240f, 6_500L to 270f, 8_000L to 420f, 9_000L to 540f)
        for ((time, distance) in expected + expected.reversed()) {
            assertEquals("Wrong paragraph advance at $time", distance, clock.distanceAt(time), 0.0001f)
        }
        // A forced sentence reaches row two according to shaped advances, not UTF-16 count.
        val forced = LyricParagraphClock(listOf(LyricTimedDistance(1_000, 9_000, 0f, 540f)))
        assertTrue(forced.distanceAt(3_000) < 240f)
        assertEquals(240f, forced.distanceAt(4_556), 0.05f)
    }

    @Test
    fun maskPreservesLogicalPlaybackAcrossRtlRunsAndInlineCaptionGaps() {
        val pieces = listOf(
            LyricRevealPiece(10f, 70f, 0f, 60f),
            LyricRevealPiece(170f, 110f, 60f, 120f),
            LyricRevealPiece(190f, 250f, 120f, 180f),
        )
        val fade = com.melox.player.ui.screen.playback.LyricFadeBounds(75f, 105f)
        val stops = lyricRevealMaskStops(pieces, fade, 1f, 0.4f)
        assertTrue(stops.zipWithNext().all { (a, b) -> a.first <= b.first })
        assertEquals(1f, stops.first { it.first == 70f }.second, 0f)
        assertEquals(0.4f, stops.first { it.first == 110f }.second, 0f)
        assertEquals(1f, stops.first { it.first == 170f }.second, 0f)
        assertTrue(stops.filter { it.first >= 190f }.all { it.second == 0.4f })
        assertTrue(stops.all { it.second in 0.4f..1f })
    }

    @Test
    fun liftFinishesAtTheOpaqueEdgeForUnequalWidthsAndConnectedUnits() {
        for ((left, right) in listOf(0f to 10f, 10f to 85f, 85f to 100f, 0f to 100f)) {
            val fullRevealPixel = (right + 36f) * 100f / 136f
            val completedFade = lyricFadeBounds(0f, 100f, fullRevealPixel, 36f)
            assertEquals(1f, lyricGlyphRevealProgress(left, right, completedFade), 0.00001f)
            assertEquals(-4f, lyricRevealFloatOffset(
                lyricGlyphRevealProgress(left, right, completedFade)), 0.00001f)
            val earlier = lyricFadeBounds(0f, 100f, fullRevealPixel - 0.1f, 36f)
            assertTrue(lyricGlyphRevealProgress(left, right, earlier) < 1f)
        }
    }

    @Test
    fun liftTracksClippedFadeContinuouslyAtBothRowEdgesAndOnReverseSeek() {
        for (width in listOf(12f, 30f, 100f, 500f)) {
            for ((left, right) in listOf(0f to width / 4f, width / 4f to width, 0f to width)) {
                var previous = 0f
                val offsets = (0..1_000).map { step ->
                    val fade = lyricFadeBounds(0f, width, width * step / 1_000f, 36f)
                    val current = lyricRevealFloatOffset(lyricGlyphRevealProgress(left, right, fade))
                    assertTrue(current <= previous + 0.00001f)
                    assertTrue(previous - current < 0.1f)
                    previous = current
                    current
                }
                assertEquals(0f, offsets.first(), 0f)
                assertEquals(-4f, offsets.last(), 0f)
                for (step in 1_000 downTo 0) {
                    val fade = lyricFadeBounds(0f, width, width * step / 1_000f, 36f)
                    assertEquals(offsets[step], lyricRevealFloatOffset(
                        lyricGlyphRevealProgress(left, right, fade)), 0f)
                }
            }
        }
    }

    @Test
    fun maximumBoundsContainScaledWideUnitsGlowsAndAllLiftPositions() {
        for (width in listOf(40f, 500f, 1_600f)) for (height in listOf(72f, 168f)) {
            val coverage = Rect(-40f, -50f, width + 40f, height + 50f)
            val pivot = Offset(width / 2f, height)
            val outer = lyricGlyphVisualBounds(coverage, pivot, 1.12f, 8f)
            for (scale in listOf(1f, 1.06f, 1.12f)) for (lift in listOf(0f, 4f, 8f)) {
                for (x in listOf(coverage.left, coverage.right)) for (y in listOf(coverage.top, coverage.bottom)) {
                    val transformedX = pivot.x + (x - pivot.x) * scale
                    val transformedY = pivot.y + (y - pivot.y) * scale - lift
                    assertTrue(transformedX >= outer.left && transformedX <= outer.right)
                    assertTrue(transformedY >= outer.top && transformedY <= outer.bottom)
                }
            }
            assertTrue(outer.left < -40f)
            assertTrue(outer.top < -50f)
        }
    }

    @Test
    fun wrappedSpansRetainOriginalTimesAcrossCombiningCharactersAndEmoji() {
        val accented = "e\u0301abcdef"
        assertEquals(3_500L, lyricChunkTimeMs(1_000, 6_000, accented.length, 4))
        val emoji = "a👍🏽b"
        assertEquals(5_000L, lyricChunkTimeMs(0, 6_000, emoji.length, 5))
        assertEquals(6_000L, lyricChunkTimeMs(0, 6_000, emoji.length, emoji.length))
        val spaced = "word!  "
        assertEquals(5_000L, lyricChunkTimeMs(1_000, 8_000, spaced.length, 4))
    }

    @Test
    fun narrowMaskMatchesPreviousStopClippingThroughoutTheLine() {
        for (rowWidth in listOf(30f, 60f, 500f)) for (progress in listOf(0f, 0.01f, 0.1f, 0.5f, 0.9f, 0.99f, 1f)) {
            val fadeWidth = minOf(60f, rowWidth)
            val previousRange = fadeWidth / rowWidth
            val previousCenter = -previousRange / 2f + (1f + previousRange) * progress
            val expectedStart = (previousCenter - previousRange / 2f).coerceIn(0f, 1f) * rowWidth
            val expectedEnd = (previousCenter + previousRange / 2f).coerceIn(0f, 1f) * rowWidth
            val actual = lyricFadeBounds(10f, 10f + rowWidth, 10f + rowWidth * progress, 60f)
            assertEquals(expectedStart + 10f, actual.startX, 0.0001f)
            assertEquals(expectedEnd + 10f, actual.endX, 0.0001f)
        }
    }

    @Test
    fun shortLineTailDoesNotBecomeNearlyWhiteBeforeItsTimestampEnds() {
        val fade = lyricFadeBounds(0f, 60f, 54f, 60f)
        assertEquals(48f, fade.startX, 0f)
        assertEquals(60f, fade.endX, 0f)
        val alphaAtTail = 1f - 0.6f * (54f - fade.startX) / (fade.endX - fade.startX)
        assertEquals(0.7f, alphaAtTail, 0.0001f)
    }

    @Test
    fun suppliedNativeLyricsKeepTheirPerCharacterClockAfterWrapping() {
        val line = LyricsParser.parse(
            "[00:37.731]停[00:38.059]在[00:38.740]这[00:39.419]个[00:40.267]路[00:40.508]口[00:40.891]",
            LyricsSource.EMBEDDED,
        )!!.lines.single()
        val starts = longArrayOf(37_731, 38_059, 38_740, 39_419, 40_267, 40_508)
        val ends = longArrayOf(38_059, 38_740, 39_419, 40_267, 40_508, 40_891)
        val cursor = LyricTimingCursor(starts, ends)
        line.words.forEachIndexed { index, word ->
            assertEquals(starts[index], word.startTimeMs)
            assertEquals(ends[index], word.endTimeMs)
            assertEquals(word.startTimeMs, lyricChunkTimeMs(word.startTimeMs, word.endTimeMs, word.text.length, 0))
            assertEquals(word.endTimeMs, lyricChunkTimeMs(word.startTimeMs, word.endTimeMs, word.text.length, word.text.length))
        }
        for (position in listOf(40_700L, 38_400L, 40_850L, 37_800L)) {
            val index = starts.indices.first { position in starts[it] until ends[it] }
            assertEquals(index, cursor.indexAt(position))
        }
    }

    @Test
    fun whiteBoundaryStaysNarrowerAndTracksFontSize() {
        assertEquals(24f, lyricForegroundFadeWidthPx(24f), 0f)
        assertEquals(36f, lyricForegroundFadeWidthPx(48f), 0f)
        assertEquals(36f, lyricForegroundFadeWidthPx(72f), 0f)
        assertEquals(36f, lyricForegroundFadeWidthPx(144f), 0f)
        assertEquals(1f, lyricEffectGeometryScale(72f), 0f)
        assertEquals(2f, lyricEffectGeometryScale(144f), 0f)
    }

    @Test
    fun nativeLongNotesScaleAndGlowAcrossScripts() {
        for (content in listOf("啊", "あ", "ア", "한", "e\u0301", "مرحبا", "שלום")) {
            val longNote = lyricWordEffects(content, 2_000)
            assertTrue(content, longNote.glow)
            assertTrue(content, longNote.scale)
            assertFalse(content, lyricWordEffects(content, 999).glow)
            assertFalse(content, lyricWordEffects(content, 999).scale)
        }
        assertTrue(lyricWordEffects("啊", 1_000).scale)
        assertFalse(lyricWordEffects("……  ", 5_000).glow)
        assertFalse(lyricWordEffects("……  ", 5_000).scale)
    }

    @Test
    fun spacesAndPunctuationDoNotDisqualifyALongWord() {
        assertTrue(lyricWordEffects("word!  ", 1_001).glow)
        assertTrue(lyricWordEffects("word!  ", 1_001).scale)
        assertFalse(lyricWordEffects("hello", 1_000).glow)
        assertTrue(lyricWordEffects("hello", 1_001).glow)
        assertTrue(lyricWordEffects("مرحبا", 2_000).scale)
        assertTrue(lyricWordEffects("مرحبا", 2_000).glow)
        assertTrue(lyricWordEffects("שלום", 2_000).scale)
        assertTrue(lyricWordEffects("שלום", 2_000).glow)
    }

    @Test
    fun timingAndWrappingKeepExtendedGraphemesTogether() {
        val text = "e\u0301👨‍👩‍👧‍👦👍🏽🇨🇳🇯🇵中"
        val expected = listOf("e\u0301", "👨‍👩‍👧‍👦", "👍🏽", "🇨🇳", "🇯🇵", "中")
        assertEquals(expected, lyricGraphemeRanges(text).map { text.substring(it) })
        assertEquals(expected, lyricTextChunks(text, 1f) { lyricGraphemeRanges(it).size.toFloat() }
            .map { text.substring(it) })
        assertTrue(lyricWordEffects("e\u0301", 1_000).glow)
    }

    @Test
    fun cursorMatchesIntervalsAcrossGapsReverseSeeksAndOverlaps() {
        val starts = longArrayOf(100, 250, 800, 1_000)
        val ends = longArrayOf(500, 650, 900, 1_300)
        val cursor = LyricTimingCursor(starts, ends)
        for (position in listOf(0L, 100, 300, 500, 650, 700, 850, 1_300, 1_400, 275, 125, 1_200, 0)) {
            val active = starts.indices.firstOrNull { position >= starts[it] && position < ends[it] }
            val next = ends.indices.firstOrNull { ends[it] > position } ?: ends.size
            assertEquals("Incorrect cursor at $position", active ?: next, cursor.indexAt(position))
        }
    }
}
