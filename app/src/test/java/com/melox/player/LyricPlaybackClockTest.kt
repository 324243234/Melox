package com.melox.player

import androidx.compose.ui.unit.dp
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.melox.player.model.LyricLine
import com.melox.player.model.LyricAuxiliaryText
import com.melox.player.model.LyricsDocument
import com.melox.player.model.LyricsFormat
import com.melox.player.model.LyricsSource
import com.melox.player.model.LyricsUiState
import com.melox.player.ui.screen.playback.LyricPlaybackClock
import com.melox.player.ui.screen.playback.compactLyricsDocument
import com.melox.player.ui.screen.playback.compactPrimaryLines
import com.melox.player.ui.screen.playback.compactLyricAuxiliaryText
import com.melox.player.ui.screen.playback.compactLyricRowCenters
import com.melox.player.ui.screen.playback.compactLyricIndexAtPosition
import com.melox.player.ui.screen.playback.compactLyricScrollTarget
import com.melox.player.ui.screen.playback.CompactLyricScroll
import com.melox.player.ui.screen.playback.compactArtworkLayout
import com.melox.player.ui.screen.playback.PLAYER_ARTWORK_CONTAINER_EXPANSION
import com.melox.player.ui.screen.playback.PLAYER_ARTWORK_PLAYING_PADDING
import com.melox.player.ui.screen.playback.PLAYER_ARTWORK_VERTICAL_FOOTPRINT_EXPANSION
import com.melox.player.ui.screen.playback.lyricPlaybackPositionMs
import com.melox.player.ui.screen.playback.stabilizedLyricPlaybackPositionMs
import com.melox.player.ui.screen.playback.rememberLyricDocumentTransition
import com.melox.player.ui.screen.playback.rememberCompactArtworkLyricsPresence
import com.melox.player.ui.screen.playback.fitPlayerArtworkSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LyricPlaybackClockTest {
    @Test
    fun artworkLyricsKeepTheResolvedLayoutDuringLoadingAndReactToConfirmedResults() = runBlocking {
        val line = LyricLine("main", 0, 1_000, "Lyrics", emptyList(), null)
        val available = LyricsUiState.Available(
            LyricsDocument(listOf(line), LyricsFormat.LRC, LyricsSource.SIDECAR), "song")
        val input = mutableStateOf<LyricsUiState>(LyricsUiState.Loading)
        var present = false
        withArtworkComposition(content = {
            val current = rememberCompactArtworkLyricsPresence(input.value)
            SideEffect { present = current }
        }) {
            frame(0)
            assertTrue(present)
            input.value = available
            frame(16)
            assertTrue(present)
            input.value = LyricsUiState.Loading
            frame(32)
            assertTrue(present)
            input.value = available.copy(mediaId = "next")
            frame(48)
            assertTrue(present)

            input.value = LyricsUiState.Unavailable
            frame(64)
            assertFalse(present)
            input.value = LyricsUiState.Loading
            frame(80)
            assertFalse(present)
            input.value = available.copy(document = available.document.copy(lines = emptyList()))
            frame(96)
            assertFalse(present)
            input.value = available.copy(document = available.document.copy(lines = listOf(
                line.copy(isBackground = true), line.copy(text = " "))))
            frame(112)
            assertFalse(present)
            input.value = LyricsUiState.Loading
            frame(128)
            assertFalse(present)
            input.value = available
            frame(144)
            assertTrue(present)
        }
    }

    @Test
    fun lyricDocumentTransitionsRetainContentForFadeOutAndFadeInAfterLoading() = runBlocking {
        val first = LyricsUiState.Available(
            LyricsDocument(listOf(LyricLine("main", 0, 1_000, "First", emptyList(), null)),
                LyricsFormat.LRC, LyricsSource.SIDECAR), "first")
        val second = first.copy(document = first.document.copy(
            lines = first.document.lines.map { it.copy(text = "Second") }), mediaId = "second")
        val input = mutableStateOf<LyricsUiState>(first)
        var displayed: LyricsUiState.Available? = null
        lateinit var alpha: State<Float>
        withArtworkComposition(content = {
            val transition = rememberLyricDocumentTransition(input.value)
            SideEffect { displayed = transition.first; alpha = transition.second }
        }) {
            frame(0)
            assertEquals(first, displayed)
            assertEquals(0f, alpha.value, 0f)
            frame(120)
            assertTrue(alpha.value > 0f && alpha.value < 1f)
            frame(240)
            assertEquals(1f, alpha.value, 0f)

            input.value = LyricsUiState.Loading
            frame(256)
            frame(346)
            assertEquals(first, displayed)
            assertTrue(alpha.value > 0f && alpha.value < 1f)
            frame(436)
            assertEquals(0f, alpha.value, 0f)

            input.value = second
            frame(452)
            assertEquals(second, displayed)
            assertEquals(0f, alpha.value, 0f)
            frame(572)
            assertTrue(alpha.value > 0f && alpha.value < 1f)
            frame(692)
            assertEquals(1f, alpha.value, 0f)

            input.value = LyricsUiState.Unavailable
            frame(708)
            frame(798)
            assertEquals(second, displayed)
            assertTrue(alpha.value > 0f && alpha.value < 1f)
            frame(888)
            assertNull(displayed)
            assertEquals(0f, alpha.value, 0f)
        }
    }

    @Test
    fun interruptedLyricReplacementFadesOnlyIntoTheLatestSong() = runBlocking {
        val document = LyricsDocument(emptyList(), LyricsFormat.LRC, LyricsSource.SIDECAR)
        val first = LyricsUiState.Available(document, "first")
        val input = mutableStateOf<LyricsUiState>(first)
        var displayed: LyricsUiState.Available? = null
        lateinit var alpha: State<Float>
        val visibleSongs = mutableListOf<String?>()
        withArtworkComposition(content = {
            val transition = rememberLyricDocumentTransition(input.value)
            SideEffect {
                displayed = transition.first
                alpha = transition.second
                visibleSongs += displayed?.mediaId
            }
        }) {
            frame(0)
            frame(240)
            input.value = first.copy(mediaId = "skipped")
            frame(256)
            frame(346)
            assertEquals(first, displayed)
            assertTrue(alpha.value > 0f && alpha.value < 1f)
            input.value = first.copy(mediaId = "latest")
            frame(362)
            frame(542)
            assertEquals("latest", displayed?.mediaId)
            assertEquals(0f, alpha.value, 0f)
            frame(782)
            assertEquals(1f, alpha.value, 0f)
            assertFalse("skipped" in visibleSongs)
        }
    }

    @Test
    fun compactLyricsFollowPrimaryVocalsAndKeepCompletedLinesThroughGaps() {
        val first = LyricLine("lead", 1_000, 2_000, "First", emptyList(), "Translation")
        val second = first.copy(startTimeMs = 5_000, endTimeMs = 6_000, text = "Second")
        val document = LyricsDocument(
            listOf(
                first,
                first.copy(startTimeMs = 1_500, text = "Backing", isBackground = true),
                first.copy(startTimeMs = 3_000, text = " "),
                second,
            ), LyricsFormat.TTML, LyricsSource.EMBEDDED,
        )
        val lines = compactPrimaryLines(document)
        assertEquals(listOf(first, second), lines)
        assertEquals(-1, compactLyricIndexAtPosition(lines, 999))
        assertEquals(0, compactLyricIndexAtPosition(lines, 1_000))
        assertEquals(0, compactLyricIndexAtPosition(lines, 4_999))
        assertEquals(1, compactLyricIndexAtPosition(lines, 5_000))
        assertEquals(1, compactLyricIndexAtPosition(lines, 9_000))
        // Backward seeks and repeat restarts select directly, without replaying intermediate lines.
        assertEquals(0, compactLyricIndexAtPosition(lines, 1_500))
        assertEquals(-1, compactLyricIndexAtPosition(lines, 0))
        assertEquals(-1, compactLyricIndexAtPosition(emptyList(), 10_000))
    }

    @Test
    fun compactLyricsRejectDocumentsFromThePreviousTrack() {
        val document = LyricsDocument(emptyList(), LyricsFormat.LRC, LyricsSource.SIDECAR)
        val state = LyricsUiState.Available(document, "first")
        assertSame(document, compactLyricsDocument(state, "first"))
        assertNull(compactLyricsDocument(state, "second"))
        assertNull(compactLyricsDocument(state, null))
        assertNull(compactLyricsDocument(LyricsUiState.Loading, "first"))
        assertNull(compactLyricsDocument(LyricsUiState.Unavailable, "first"))
    }

    @Test
    fun compactLyricsPackOnlyPresentAuxiliaryTextWithConsistentEntryGaps() {
        val primary = LyricLine("main", 0, 1_000, "Original", emptyList(), null)
        val translated = primary.copy(translation = "Translation")
        val romanized = primary.copy(romanizations = listOf(LyricAuxiliaryText("Romanization")))
        val both = romanized.copy(translation = "Translation")
        val blank = primary.copy(translation = " \n ", romanizations = listOf(LyricAuxiliaryText(" ")))
        val auxiliary = listOf(primary, translated, romanized, both, blank).map {
            compactLyricAuxiliaryText(it, showTranslation = true, showRomanization = true)
        }
        assertEquals(listOf(emptyList<String>(), listOf("Translation"), listOf("Romanization"),
            listOf("Romanization", "Translation"), emptyList<String>()), auxiliary)
        val multilingual = romanized.copy(translations = listOf(
            LyricAuxiliaryText("English", "en"), LyricAuxiliaryText("Chinese", "zh-Hans")))
        assertEquals(listOf("Romanization", "Chinese"), compactLyricAuxiliaryText(multilingual,
            true, true, com.melox.player.model.LyricsTranslationLanguage.AUTO, "zh-CN"))
        assertEquals(listOf("English"), compactLyricAuxiliaryText(multilingual,
            true, false, com.melox.player.model.LyricsTranslationLanguage.ENGLISH, "zh-CN"))
        assertEquals(listOf("Translation"), compactLyricAuxiliaryText(both, true, false))
        assertEquals(listOf("Romanization"), compactLyricAuxiliaryText(both, false, true))
        assertEquals(emptyList<String>(), compactLyricAuxiliaryText(both, false, false))

        val heights = auxiliary.map { 20f + 16f * it.size }
        val centers = compactLyricRowCenters(heights, 6f)
        assertArrayEquals(floatArrayOf(10f, 44f, 86f, 136f, 178f), centers, 0f)
        for (index in 1..heights.lastIndex) {
            val previousBottom = centers[index - 1] + heights[index - 1] / 2f
            val nextTop = centers[index] - heights[index] / 2f
            assertEquals(6f, nextTop - previousBottom, 0f)
        }
    }

    @Test
    fun compactLongLinesFollowTheCursorAndStopAtTextEdges() {
        assertEquals(0f, compactLyricScrollTarget(100f, 400f, 0f, false), 0f)
        assertEquals(-150f, compactLyricScrollTarget(100f, 400f, 200f, false), 0f)
        assertEquals(-300f, compactLyricScrollTarget(100f, 400f, 400f, false), 0f)
        assertEquals(20f, compactLyricScrollTarget(100f, 60f, 60f, true), 0f)
        assertEquals(0f, compactLyricScrollTarget(100f, 60f, 60f, false), 0f)
        val scroll = CompactLyricScroll()
        assertEquals(0f, scroll.update(0f, 1_000, 100f, 400f), 0f)
        val next = scroll.update(-100f, 1_250, 100f, 400f)
        assertEquals(-63.212f, next, 0.001f)
        // A backward seek and a resize reanchor without scrolling across obsolete text.
        assertEquals(-200f, scroll.update(-200f, 500, 100f, 400f), 0f)
        assertEquals(-20f, scroll.update(-20f, 516, 200f, 400f), 0f)
        assertEquals(-40f, scroll.update(-40f, 532, 200f, 400f, smooth = false), 0f)
    }

    @Test
    fun portraitLyricsUseSaltSpaceBudgetAndPlayingArtworkBounds() {
        val geometry = compactArtworkLayout(600.dp, 330.dp, 28.dp)
        val playingInset = (PLAYER_ARTWORK_VERTICAL_FOOTPRINT_EXPANSION -
            PLAYER_ARTWORK_CONTAINER_EXPANSION) / 2f + PLAYER_ARTWORK_PLAYING_PADDING
        val artworkBottom = geometry.artworkTop + geometry.artworkHeight - playingInset
        val lyricCenter = geometry.lyricsTop + geometry.lyricsHeight / 2f
        assertEquals((artworkBottom.value + 628f) / 2f, lyricCenter.value, 0.001f)
        assertEquals(68.dp, geometry.lyricsHeight)
        assertEquals(330.dp + PLAYER_ARTWORK_VERTICAL_FOOTPRINT_EXPANSION, geometry.artworkHeight)
        assertTrue(geometry.artworkTop < (600.dp - geometry.artworkHeight) / 2f)
        assertTrue(geometry.lyricsTop >= artworkBottom)
        assertTrue(geometry.lyricsTop + geometry.lyricsHeight <= 600.dp)

        val withoutLyrics = compactArtworkLayout(600.dp, 330.dp, 28.dp, reserveLyrics = false)
        assertEquals(300.dp, withoutLyrics.artworkTop + withoutLyrics.artworkHeight / 2f)
        assertTrue(withoutLyrics.artworkTop > geometry.artworkTop)
        assertEquals(0.dp, withoutLyrics.lyricsHeight)
        assertEquals(fitPlayerArtworkSize(330.dp, 600.dp),
            withoutLyrics.artworkHeight - PLAYER_ARTWORK_VERTICAL_FOOTPRINT_EXPANSION)

        assertEquals(24.dp, compactArtworkLayout(416.dp, 330.dp, 28.dp).lyricsHeight)
        assertEquals(68.dp, compactArtworkLayout(417.dp, 330.dp, 28.dp).lyricsHeight)

        val small = compactArtworkLayout(300.dp, 330.dp, 28.dp)
        assertEquals(24.dp, small.lyricsHeight)
        assertTrue(small.artworkHeight < geometry.artworkHeight)
        assertTrue(small.lyricsTop + small.lyricsHeight <= 300.dp)
        val smallWithoutLyrics = compactArtworkLayout(300.dp, 330.dp, 28.dp, reserveLyrics = false)
        assertEquals(150.dp, smallWithoutLyrics.artworkTop + smallWithoutLyrics.artworkHeight / 2f)
        assertEquals(fitPlayerArtworkSize(330.dp, 300.dp),
            smallWithoutLyrics.artworkHeight - PLAYER_ARTWORK_VERTICAL_FOOTPRINT_EXPANSION)
        val empty = compactArtworkLayout(0.dp, 330.dp, 28.dp)
        assertEquals(0.dp, empty.artworkHeight)
        assertEquals(0.dp, empty.lyricsHeight)
        assertEquals(0.dp, empty.lyricsTop)
    }

    @Test
    fun hiddenPlaybackSamplesKeepTheClockReadyWithoutFrames() {
        val clock = LyricPlaybackClock(1_000, 0)
        val sample = lyricPlaybackPositionMs(60_000, 10_000, 10_500, true, 2f)
        clock.updateFromPlayback(0, sample, active = false)
        assertEquals(61_000.0, clock.positionMs.doubleValue, 0.0)

        clock.updateFromPlayback(0, sample, active = true)
        // A slightly older active sample must not replace the smooth clock after activation.
        clock.updateFromPlayback(0, 60_900, active = true)
        assertEquals(61_000.0, clock.positionMs.doubleValue, 0.0)
    }

    @Test
    fun activeSystemSeekResetsOnceAndOrdinaryBackwardSamplesStayFiltered() {
        val clock = LyricPlaybackClock(180_000, 0)
        clock.updateFromPlayback(0, 180_000, active = true)
        assertTrue(clock.updateFromPlayback(0, 30_000, active = true, positionDiscontinuityRevision = 1))
        assertEquals(30_000.0, clock.positionMs.doubleValue, 0.0)
        assertFalse(clock.updateFromPlayback(0, 29_990, active = true, positionDiscontinuityRevision = 1))
        assertEquals(30_000.0, clock.positionMs.doubleValue, 0.0)
    }

    @Test
    fun reopeningReanchorsEvenWithoutASeekEventOrRunningFrames() {
        val clock = LyricPlaybackClock(180_000, 0)
        clock.updateFromPlayback(0, 180_000, active = true)
        clock.updateFromPlayback(0, 180_000, active = false)
        assertTrue(clock.updateFromPlayback(0, 30_000, active = true))
        assertEquals(30_000.0, clock.positionMs.doubleValue, 0.0)
    }

    @Test
    fun hiddenSystemSeeksInBothDirectionsReplaceTheOldPosition() {
        val clock = LyricPlaybackClock(1_000, 0)
        for (target in listOf(180_000L, 30_000L)) {
            clock.updateFromPlayback(0, target, active = false)
            assertEquals(target.toDouble(), clock.positionMs.doubleValue, 0.0)
            clock.updateFromPlayback(0, target, active = true)
            val firstFrame = stabilizedLyricPlaybackPositionMs(
                clock.positionMs.doubleValue, target, 0L, 1f,
            )
            assertEquals(target.toDouble(), firstFrame, 0.0)
        }
    }

    @Test
    fun pausedHiddenSeekUsesTheControllerPositionWithoutExtrapolation() {
        val clock = LyricPlaybackClock(180_000, 0)
        val sample = lyricPlaybackPositionMs(30_000, 10_000, 30_000, false, 1f)
        clock.updateFromPlayback(0, sample, active = false)
        clock.updateFromPlayback(0, sample, active = true)
        assertEquals(30_000.0, clock.positionMs.doubleValue, 0.0)
    }

    @Test
    fun consecutiveRepeatsResetTheSameDocumentAndItsProgress() {
        val lyrics = LyricsDocument(
            listOf(
                LyricLine("", 0, 5_000, "First", emptyList(), null),
                LyricLine("", 5_000, 10_000, "Last", emptyList(), null),
            ), LyricsFormat.LRC, LyricsSource.EMBEDDED,
        )
        val clock = LyricPlaybackClock(9_900, 0)
        for (iteration in 1L..3L) {
            clock.positionMs.doubleValue = 9_900.0
            assertEquals(1, lyrics.focusLineIndex(clock.positionMs.doubleValue.toLong()))
            assertTrue(clock.resetForIteration(iteration, 20))
            assertEquals(0, lyrics.currentLineIndex(clock.positionMs.doubleValue.toLong()))
            assertTrue(lyrics.lines.first().revealProgress(20, true) < 0.01f)
            assertEquals(20.0, clock.positionMs.doubleValue, 0.0)
        }
    }

    @Test
    fun conflatedRoundTripToTheSameTrackStartsANewClockInterval() {
        val clock = LyricPlaybackClock(90_000, 10)
        // Two media transitions occurred before the UI collected the latest snapshot.
        assertTrue(clock.resetForIteration(12, 0))
        assertEquals(0.0, clock.positionMs.doubleValue, 0.0)
        assertFalse(clock.resetForIteration(12, 0))
        clock.positionMs.doubleValue = stabilizedLyricPlaybackPositionMs(
            clock.positionMs.doubleValue, 17, 16_666_667, 1f,
        )
        assertEquals(17.0, clock.positionMs.doubleValue, 0.0)
    }

    @Test
    fun ordinaryBackwardSamplesNeverResetTheClock() {
        val clock = LyricPlaybackClock(9_900, 2)
        assertFalse(clock.resetForIteration(2, 20))
        clock.positionMs.doubleValue = stabilizedLyricPlaybackPositionMs(
            clock.positionMs.doubleValue, 20, 16_666_667, 1f,
        )
        assertTrue(clock.positionMs.doubleValue > 9_900)
    }

    @Test
    fun suspendedFramesReanchorToPlaybackInsteadOfAddingBackgroundTime() {
        assertEquals(12_000.0, stabilizedLyricPlaybackPositionMs(
            10_000.0, 12_000, 30_000_000_000L, 1f,
        ), 0.0)
        assertEquals(2_000.0, stabilizedLyricPlaybackPositionMs(
            10_000.0, 2_000, 30_000_000_000L, 1f,
        ), 0.0)
        assertEquals(32_000.0, stabilizedLyricPlaybackPositionMs(
            10_000.0, 32_000, 30_000_000_000L, 1f,
        ), 0.0)
    }

    @Test
    fun lateRepeatSampleResetsToItsRealPositionAndIsConsumedOnlyOnce() {
        val clock = LyricPlaybackClock(240_000, 0)
        assertTrue(clock.resetForIteration(1, 650))
        clock.positionMs.doubleValue = stabilizedLyricPlaybackPositionMs(
            clock.positionMs.doubleValue, 650, 16_666_667, 1f,
        )
        val advanced = clock.positionMs.doubleValue
        assertFalse(clock.resetForIteration(1, 650))
        assertEquals(advanced, clock.positionMs.doubleValue, 0.0)
        assertTrue(advanced > 650 && advanced < 700)
    }
}
