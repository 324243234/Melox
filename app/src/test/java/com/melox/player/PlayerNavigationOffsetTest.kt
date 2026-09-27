package com.melox.player

import androidx.compose.ui.geometry.Rect
import com.melox.player.ui.component.playback.playerNavigationOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerNavigationOffsetTest {
    @Test
    fun preservesGapDuringExpansionCollapseAndReversal() {
        val source = Rect(24f, 700f, 376f, 764f)
        val target = Rect(0f, 0f, 400f, 900f)
        val navigationTop = 780f
        for (progress in listOf(0f, 0.2f, 0.8f, 0.4f, 1f, 0.6f, 0f)) {
            val playerBottom = 764f + 136f * progress
            val translatedTop = navigationTop + playerNavigationOffset(source, target, progress)
            assertEquals(16f, translatedTop - playerBottom, 0.001f)
        }
    }

    @Test
    fun missingBoundsKeepNavigationInPlace() {
        val bounds = Rect(0f, 0f, 400f, 900f)
        assertEquals(0f, playerNavigationOffset(Rect.Zero, bounds, 0.5f), 0f)
        assertEquals(0f, playerNavigationOffset(bounds, Rect.Zero, 0.5f), 0f)
    }
}
