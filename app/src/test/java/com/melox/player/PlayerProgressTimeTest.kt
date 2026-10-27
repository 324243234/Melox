package com.melox.player

import com.melox.player.ui.screen.playback.formatPlayerProgressTime
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerProgressTimeTest {
    @Test
    fun shortTimesKeepTwoMinuteDigits() {
        assertEquals("00:00", formatPlayerProgressTime(0L))
        assertEquals("00:07", formatPlayerProgressTime(7_000L))
        assertEquals("03:25", formatPlayerProgressTime(205_000L))
        assertEquals("09:59", formatPlayerProgressTime(599_999L))
    }

    @Test
    fun tenMinuteAndHourBoundariesKeepTheirNormalFormat() {
        assertEquals("10:00", formatPlayerProgressTime(600_000L))
        assertEquals("59:59", formatPlayerProgressTime(3_599_000L))
        assertEquals("1:00:00", formatPlayerProgressTime(3_600_000L))
        assertEquals("1:02:03", formatPlayerProgressTime(3_723_000L))
    }

    @Test
    fun negativeAndSubsecondPositionsRemainZero() {
        assertEquals("00:00", formatPlayerProgressTime(-1L))
        assertEquals("00:00", formatPlayerProgressTime(999L))
    }
}
