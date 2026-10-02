package com.melox.player

import androidx.compose.ui.geometry.Rect
import com.melox.player.ui.component.playback.PlayerInputOwner
import com.melox.player.ui.component.playback.PlayerSheetTransitionState
import com.melox.player.ui.component.playback.playerSheetInputOwner
import com.melox.player.ui.component.playback.sharedContainerRect
import com.melox.player.ui.component.playback.sharedMiniPlayerControlsRenderRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerInputOwnerTest {
    @Test
    fun normalBarTransitionBoundsGrowVerticallyWithoutScalingTheHost() {
        val source = Rect(6f, 710f, 354f, 774f)
        val host = Rect(0f, 0f, 360f, 800f)
        assertEquals(
            Rect(3f, 355f, 357f, 787f),
            sharedContainerRect(source, host, 0.5f),
        )
        assertEquals(host, sharedContainerRect(source, host, 1f))
    }

    @Test
    fun settledEndpointReturnsToLiveDrawingAfterTransition() {
        val state = PlayerSheetTransitionState(initialProgress = 1f)
        state.updateMiniPlayerBounds(Rect(6f, 710f, 354f, 774f))
        state.updateFullPlayerBounds(Rect(0f, 0f, 360f, 800f))

        assertTrue(state.fullPlayerDrawsInPlace)
        state.close()
        assertTrue(state.isTransitionActive)
        assertFalse(state.fullPlayerDrawsInPlace)
        state.open()
        assertFalse(state.isTransitionActive)
        assertTrue(state.fullPlayerDrawsInPlace)
        assertTrue(state.isFullyExpanded)
    }

    @Test
    fun miniControlsInputFollowsTheRecordedBarPlacement() {
        val source = Rect(20f, 710f, 340f, 774f)
        val content = Rect(20f, 718f, 340f, 766f)
        val controls = Rect(250f, 722f, 330f, 762f)
        val animated = Rect(18f, 639f, 342f, 777f)

        assertEquals(
            Rect(252f, 651f, 332f, 691f),
            sharedMiniPlayerControlsRenderRect(source, animated, content, controls),
        )
    }

    @Test
    fun collapsedEndpointBelongsToMiniPlayer() {
        assertEquals(
            PlayerInputOwner.MINI,
            playerSheetInputOwner(
                targetOpen = false,
                isDragging = false,
                dragStartedFromMiniPlayer = false,
                progress = 0f,
                sharedLayersReady = false,
            ),
        )
    }

    @Test
    fun unreadyIntermediateFrameBlocksBothEndpoints() {
        assertEquals(
            PlayerInputOwner.NONE,
            playerSheetInputOwner(
                targetOpen = true,
                isDragging = false,
                dragStartedFromMiniPlayer = false,
                progress = 0.5f,
                sharedLayersReady = false,
            ),
        )
    }

    @Test
    fun expandedEndpointBelongsToFullPlayerWithoutRecordedLayers() {
        assertEquals(
            PlayerInputOwner.FULL,
            playerSheetInputOwner(
                targetOpen = true,
                isDragging = false,
                dragStartedFromMiniPlayer = false,
                progress = 1f,
                sharedLayersReady = false,
            ),
        )
    }

    @Test
    fun dragKeepsTheOriginatingSurfaceAsInputOwner() {
        assertEquals(
            PlayerInputOwner.MINI,
            playerSheetInputOwner(
                targetOpen = true,
                isDragging = true,
                dragStartedFromMiniPlayer = true,
                progress = 0.8f,
                sharedLayersReady = true,
            ),
        )
        assertEquals(
            PlayerInputOwner.FULL,
            playerSheetInputOwner(
                targetOpen = false,
                isDragging = true,
                dragStartedFromMiniPlayer = false,
                progress = 0.2f,
                sharedLayersReady = true,
            ),
        )
    }
}
