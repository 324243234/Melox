package com.melox.player

import com.melox.player.model.LyricLine
import com.melox.player.model.LyricsDocument
import com.melox.player.model.LyricsFormat
import com.melox.player.model.LyricsSource
import com.melox.player.ui.screen.playback.LyricPlaybackClock
import com.melox.player.ui.screen.playback.lyricPlaybackPositionMs
import com.melox.player.ui.screen.playback.stabilizedLyricPlaybackPositionMs
import org.junit.Assert.*
import org.junit.Test

class LyricPlaybackClockTest {
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
