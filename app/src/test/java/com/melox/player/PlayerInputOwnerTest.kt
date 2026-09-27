package com.melox.player

import androidx.compose.ui.geometry.Rect
import com.melox.player.ui.component.playback.PlayerInputOwner
import com.melox.player.ui.component.playback.PlayerSheetTransitionState
import com.melox.player.ui.component.playback.playerSheetInputOwner
import com.melox.player.ui.component.playback.playerSheetInputTransform
import com.melox.player.ui.component.playback.sharedMiniPlayerControlsRenderRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerInputOwnerTest {
    @Test
    fun transitioningInputMapsToTheVisibleContainer() {
        val transform = requireNotNull(
            playerSheetInputTransform(
                source = Rect(20f, 710f, 340f, 774f),
                target = Rect(0f, 0f, 360f, 800f),
                progress = 0.5f,
            ),
        )

        assertEquals(Rect(10f, 355f, 350f, 787f), transform.visibleBounds)
        val fullControl = Rect(100f, 600f, 140f, 640f)
        val renderedControl = Rect(
            10f + fullControl.left * transform.scale,
            355f + fullControl.top * transform.scale,
            10f + fullControl.right * transform.scale,
            355f + fullControl.bottom * transform.scale,
        )
        val restored = transform.localRect(renderedControl)
        assertEquals(fullControl.left, restored.left, 0.001f)
        assertEquals(fullControl.top, restored.top, 0.001f)
        assertEquals(fullControl.right, restored.right, 0.001f)
        assertEquals(fullControl.bottom, restored.bottom, 0.001f)
        assertEquals(0f, transform.localRect(transform.visibleBounds).left, 0.001f)
        assertEquals(360f, transform.localRect(transform.visibleBounds).right, 0.001f)
    }

    @Test
    fun inputMappingRequiresBothEndpointBounds() {
        assertEquals(
            null,
            playerSheetInputTransform(
                source = Rect.Zero,
                target = Rect(0f, 0f, 360f, 800f),
                progress = 0.4f,
            ),
        )
    }

    @Test
    fun inputMappingKeepsContentAlignedWhenTheFullHostIsInset() {
        val transform = requireNotNull(
            playerSheetInputTransform(
                source = Rect(60f, 720f, 360f, 784f),
                target = Rect(20f, 40f, 420f, 840f),
                progress = 0.5f,
            ),
        )
        val fullControl = Rect(120f, 240f, 160f, 280f)
        val renderedControl = Rect(
            transform.visibleBounds.left + (fullControl.left - 20f) * transform.scale,
            transform.visibleBounds.top + (fullControl.top - 40f) * transform.scale,
            transform.visibleBounds.left + (fullControl.right - 20f) * transform.scale,
            transform.visibleBounds.top + (fullControl.bottom - 40f) * transform.scale,
        )

        val localControl = transform.localRect(renderedControl)
        assertEquals(fullControl.left - 20f, localControl.left, 0.001f)
        assertEquals(fullControl.top - 40f, localControl.top, 0.001f)
        assertEquals(fullControl.right - 20f, localControl.right, 0.001f)
        assertEquals(fullControl.bottom - 40f, localControl.bottom, 0.001f)
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
