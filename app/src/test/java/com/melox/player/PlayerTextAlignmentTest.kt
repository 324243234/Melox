package com.melox.player

import com.melox.player.ui.screen.playback.playerTextAlignmentOffset
import com.melox.player.ui.screen.playback.playerMarqueeAlignmentWidth
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTextAlignmentTest {
    @Test
    fun wrappedLinesMoveByTheirOwnWidth() {
        assertEquals(0f, playerTextAlignmentOffset(0f, 80f, 200f, 0f))
        assertEquals(60f, playerTextAlignmentOffset(0f, 80f, 200f, 1f))
        assertEquals(30f, playerTextAlignmentOffset(0f, 80f, 200f, 0.5f))
        assertEquals(10f, playerTextAlignmentOffset(0f, 180f, 200f, 1f))
    }

    @Test
    fun rightToLeftStartPositionMovesTowardCenter() {
        assertEquals(-60f, playerTextAlignmentOffset(120f, 200f, 200f, 1f))
        assertEquals(-24f, playerTextAlignmentOffset(120f, 200f, 200f, 0.4f))
        assertEquals(-12f, playerTextAlignmentOffset(120f, 200f, 200f, 0.2f))
        assertEquals(0f, playerTextAlignmentOffset(120f, 200f, 200f, 0f))
    }

    @Test
    fun marqueeCentersShortTitlesButKeepsOverflowAtItsScrollOrigin() {
        val viewportWidth = 220f
        val shortTitleWidth = 80f
        val longTitleWidth = 300f
        assertEquals(
            70f,
            playerTextAlignmentOffset(
                0f, shortTitleWidth,
                playerMarqueeAlignmentWidth(viewportWidth, shortTitleWidth), 1f,
            ),
        )
        assertEquals(
            0f,
            playerTextAlignmentOffset(
                0f, longTitleWidth,
                playerMarqueeAlignmentWidth(viewportWidth, longTitleWidth), 1f,
            ),
        )
    }
}
