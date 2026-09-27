package com.melox.player

import com.melox.player.ui.component.library.artworkExportName
import com.melox.player.ui.component.library.artworkPreviewBounds
import com.melox.player.ui.component.library.artworkZoomOffset
import com.melox.player.ui.component.library.clampArtworkPan
import com.melox.player.ui.component.library.artworkPreviewTransitionBounds
import com.melox.player.ui.component.library.artworkDoubleTapZoom
import com.melox.player.ui.component.library.artworkPreviewImagePoint
import com.melox.player.ui.component.library.artworkPreviewBackgroundColor
import com.melox.player.ui.component.library.artworkPreviewUsesLightChrome
import com.melox.player.ui.component.library.artworkPreviewChromeAlpha
import com.melox.player.ui.component.library.artworkPreviewShouldDismiss
import com.melox.player.ui.component.playback.sourceOverAlphas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPreviewTest {
    @Test
    fun previewChromeUsesThemeBrightnessAndDarkensWhenHidden() {
        assertTrue(artworkPreviewUsesLightChrome(Color.White))
        assertEquals(Color.White, artworkPreviewBackgroundColor(true, toolbarVisible = true))
        assertEquals(Color.Black, artworkPreviewBackgroundColor(true, toolbarVisible = false))
        assertEquals(Color.Black, artworkPreviewBackgroundColor(false, toolbarVisible = true))
    }

    @Test
    fun pinchChromeRecoversOnlyAboveDismissThreshold() {
        assertEquals(1f, artworkPreviewChromeAlpha(1f), 0f)
        assertEquals(0f, artworkPreviewChromeAlpha(0.55f), 0f)
        assertEquals(0f, artworkPreviewChromeAlpha(0.72f), 0f)
        assertEquals(0f, artworkPreviewChromeAlpha(0.6f), 0f)
        assertTrue(artworkPreviewChromeAlpha(0.8f) > 0f)
        assertTrue(artworkPreviewChromeAlpha(0.8f) < 1f)
        assertTrue(artworkPreviewShouldDismiss(0.72f))
        assertTrue(!artworkPreviewShouldDismiss(0.8f))
        assertTrue(!artworkPreviewShouldDismiss(1f))
    }

    @Test
    fun previewMovesBothAxesDirectlyAndReachesRectangularEndpoints() {
        val source = Rect(24f, 1700f, 80f, 1756f)
        val target = Rect(0f, 900f, 1080f, 1500f)
        assertEquals(source, artworkPreviewTransitionBounds(source, target, 0f))
        assertEquals(target, artworkPreviewTransitionBounds(source, target, 1f))
        val middle = artworkPreviewTransitionBounds(source, target, 0.5f)
        assertEquals(Offset(296f, 1464f), middle.center)
        assertEquals(328f, middle.height, 0.01f)
        for (progress in listOf(0.1f, 0.25f, 0.75f, 0.9f)) {
            val center = artworkPreviewTransitionBounds(source, target, progress).center
            assertEquals(progress, (center.x - source.center.x) / (target.center.x - source.center.x), 0.0001f)
            assertEquals(progress, (center.y - source.center.y) / (target.center.y - source.center.y), 0.0001f)
        }
    }

    @Test
    fun resolutionBlendDoesNotExposeBlackBackgroundIncludingInterruptedChanges() {
        for (weights in listOf(listOf(0.5f, 0.5f), listOf(0.2f, 0.3f, 0.5f), listOf(0.01f, 0.99f))) {
            var white = 0f
            sourceOverAlphas(weights).forEach { alpha -> white = alpha + white * (1f - alpha) }
            assertEquals(1f, white, 0.00001f)
        }
    }
    @Test
    fun portraitPreviewTouchesHorizontalEdgesWithoutCropping() {
        val bounds = artworkPreviewBounds(1080f, 2400f, 1500, 3000)
        assertEquals(540f, bounds.center.x, 0.01f)
        assertEquals(1200f, bounds.center.y, 0.01f)
        assertEquals(0.5f, bounds.width / bounds.height, 0.001f)
        assertEquals(0f, bounds.left, 0.01f)
        assertEquals(1080f, bounds.right, 0.01f)
    }

    @Test
    fun landscapePreviewTouchesHorizontalEdgesWithoutCropping() {
        val bounds = artworkPreviewBounds(2400f, 1080f, 4000, 1000)
        assertEquals(1200f, bounds.center.x, 0.01f)
        assertEquals(540f, bounds.center.y, 0.01f)
        assertEquals(4f, bounds.width / bounds.height, 0.001f)
        assertEquals(0f, bounds.left, 0.01f)
        assertEquals(2400f, bounds.right, 0.01f)
    }

    @Test
    fun tallImageTouchesVerticalEdgesAndRemainsCentered() {
        val bounds = artworkPreviewBounds(1080f, 2400f, 1000, 4000)
        assertEquals(0f, bounds.top, 0.01f)
        assertEquals(2400f, bounds.bottom, 0.01f)
        assertEquals(540f, bounds.center.x, 0.01f)
    }

    @Test
    fun zoomKeepsTheImagePointUnderTheGestureCentroid() {
        val center = Offset(500f, 1000f)
        val centroid = Offset(600f, 1100f)
        val offset = artworkZoomOffset(Offset.Zero, centroid, center, Offset.Zero, 2f)
        assertEquals(Offset(-100f, -100f), offset)
        assertEquals(centroid, center + offset + (centroid - center) * 2f)
    }

    @Test
    fun doubleTapCoversTheViewportForSquareArtworkInPortrait() {
        val viewport = Rect(0f, 0f, 1080f, 2400f)
        val image = artworkPreviewBounds(1080f, 2400f, 1000, 1000)
        val zoom = artworkDoubleTapZoom(image, viewport)

        assertEquals(2400f / 1080f, zoom, 0.0001f)
        assertEquals(viewport.height, image.height * zoom, 0.01f)
        assertEquals(Offset.Zero, clampArtworkPan(Offset.Zero, image, zoom, viewport))
    }

    @Test
    fun doubleTapAnchorsTheTouchedDetailUntilTheImageEdgeLimitsPanning() {
        val viewport = Rect(0f, 0f, 1080f, 2400f)
        val image = artworkPreviewBounds(1080f, 2400f, 1000, 1000)
        val zoom = artworkDoubleTapZoom(image, viewport)
        val touch = Offset(270f, image.center.y)
        val pan = clampArtworkPan(
            artworkZoomOffset(Offset.Zero, touch, image.center, Offset.Zero, zoom),
            image, zoom, viewport,
        )

        val displayedTouch = image.center + pan + (touch - image.center) * zoom
        assertEquals(touch.x, displayedTouch.x, 0.01f)
        assertEquals(touch.y, displayedTouch.y, 0.01f)
        assertEquals(Offset.Zero, clampArtworkPan(Offset(0f, 300f), image, zoom, viewport))
    }

    @Test
    fun doubleTapStillMagnifiesMatchingAspectRatiosAndCapsExtremeRatios() {
        val viewport = Rect(0f, 0f, 1080f, 2400f)
        assertEquals(2f, artworkDoubleTapZoom(viewport, viewport), 0f)
        val panoramic = artworkPreviewBounds(1080f, 2400f, 10000, 1000)
        assertEquals(8f, artworkDoubleTapZoom(panoramic, viewport), 0f)
    }

    @Test
    fun doubleTapInPortraitLetterboxUsesTheNearestVerticalImageEdge() {
        val image = artworkPreviewBounds(1080f, 2400f, 1000, 1000)
        assertEquals(Offset(240f, image.top), artworkPreviewImagePoint(Offset(240f, 100f), image))
        assertEquals(Offset(840f, image.bottom), artworkPreviewImagePoint(Offset(840f, 2300f), image))
    }

    @Test
    fun doubleTapInLandscapeLetterboxUsesTheNearestHorizontalImageEdge() {
        val image = artworkPreviewBounds(2400f, 1080f, 1000, 1000)
        assertEquals(Offset(image.left, 300f), artworkPreviewImagePoint(Offset(100f, 300f), image))
        assertEquals(Offset(image.right, 800f), artworkPreviewImagePoint(Offset(2300f, 800f), image))
        assertEquals(Offset(image.left, image.top), artworkPreviewImagePoint(Offset.Zero, image))
    }

    @Test
    fun panStopsAtImageEdgesAndResetsWhenZoomedOut() {
        val viewport = Rect(0f, 0f, 1000f, 2000f)
        val image = artworkPreviewBounds(1000f, 2000f, 1000, 1000)
        assertEquals(Offset(500f, 0f), clampArtworkPan(Offset(900f, 900f), image, 2f, viewport))
        assertEquals(Offset(-1000f, -500f), clampArtworkPan(Offset(-5000f, -5000f), image, 3f, viewport))
        assertEquals(Offset.Zero, clampArtworkPan(Offset(300f, -200f), image, 1f, viewport))
    }

    @Test
    fun exportNameCannotIntroduceDirectoryTraversalOrControlCharacters() {
        assertEquals("Artwork.png", artworkExportName(null, "png"))
        assertEquals("Artwork.jpg", artworkExportName("...", "jpg"))
        assertEquals("_cover_name_.png", artworkExportName("../cover\\name\n", "png"))
        assertTrue(artworkExportName("a".repeat(200), "webp").length <= 85)
    }
}
