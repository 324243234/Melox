package com.melox.player

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import com.melox.player.model.BottomBarStyle
import com.melox.player.ui.component.playback.miniPlayerArtworkCornerRadius
import com.melox.player.ui.component.playback.miniPlayerArtworkMetadataSpacing
import com.melox.player.ui.component.playback.miniPlayerArtworkSize
import com.melox.player.ui.component.playback.miniPlayerArtworkStartPadding
import com.melox.player.ui.component.playback.miniPlayerControlSize
import com.melox.player.ui.component.playback.miniPlayerHeight
import com.melox.player.ui.component.playback.miniPlayerQueueOffset
import com.melox.player.ui.component.playback.playbackArtworkBounds
import com.melox.player.ui.component.playback.sharedArtworkRect
import org.junit.Assert.assertEquals
import org.junit.Test
import top.yukonga.miuix.kmp.basic.IconButtonDefaults

class CircularPlaybackArtworkTest {
    @Test
    fun normalMiniPlayerKeepsItsArtworkSlotAndLeadingPosition() {
        for ((small, size) in listOf(false to 48.dp, true to 36.dp)) {
            assertEquals(size, miniPlayerArtworkSize(true, small))
            assertEquals(10.dp, miniPlayerArtworkStartPadding(true, small, true))
            assertEquals(
                miniPlayerArtworkStartPadding(true, small, false),
                miniPlayerArtworkStartPadding(true, small, true),
            )
            assertEquals(size / 2, miniPlayerArtworkCornerRadius(true, small, true))
        }
    }

    @Test
    fun floatingCircleMatchesVerticalMarginsAndKeepsTheSquareArtworkTextGap() {
        for (small in listOf(false, true)) {
            val size = miniPlayerArtworkSize(false, small)
            val clearance = (miniPlayerHeight(BottomBarStyle.FLOATING, small) - size) / 2
            assertEquals(clearance, miniPlayerArtworkStartPadding(false, small, true))
            assertEquals(if (small) 4.dp else 8.dp, miniPlayerArtworkMetadataSpacing(false, small))
            assertEquals(size / 2, miniPlayerArtworkCornerRadius(false, small, true))
        }
    }

    @Test
    fun circleEndpointsRemainSquareForNonSquareSourceImagesAndReversedMotion() {
        val mini = Rect(8f, 708f, 40f, 740f)
        val full = Rect(50f, 150f, 350f, 450f)
        for ((width, height) in listOf(200 to 100, 100 to 200, 100 to 100)) {
            val source = playbackArtworkBounds(mini, width, height, true)
            val target = playbackArtworkBounds(full, width, height, true)
            assertEquals(mini, source)
            assertEquals(full, target)
            for (progress in listOf(0f, 0.2f, 0.8f, 1f, 0.6f, 0.2f, 0f)) {
                val bounds = sharedArtworkRect(source, target, progress)
                assertEquals(bounds.width, bounds.height, 0.001f)
            }
        }
        assertEquals(Rect(8f, 716f, 40f, 732f), playbackArtworkBounds(mini, 200, 100, false))
    }

    @Test
    fun compactTargetsMatchOfficialButtonsAndHaveNoGapBetweenPressAreas() {
        assertEquals(IconButtonDefaults.MinWidth, miniPlayerControlSize())
        assertEquals(IconButtonDefaults.MinHeight, miniPlayerControlSize())
        for (normal in listOf(false, true)) {
            for (scale in listOf(1f, 1.04f)) {
                val playRight = miniPlayerControlSize().value * scale
                val queueLeft = (miniPlayerControlSize().value + miniPlayerQueueOffset(normal, true).value) * scale
                assertEquals(playRight, queueLeft, 0.001f)
            }
        }
        assertEquals(0.dp, miniPlayerQueueOffset(false, true))
        assertEquals((-2).dp, miniPlayerQueueOffset(false, false))
        assertEquals(0.dp, miniPlayerQueueOffset(true, true))
        assertEquals(0.dp, miniPlayerQueueOffset(true, false))
    }
}
