package com.melox.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.melox.player.ui.component.playback.PlayerSheetInputShape
import com.melox.player.ui.component.playback.sharedContainerCornerRadius
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSheetInputShapeTest {
    @Test
    fun roundShellRejectsTransparentCornersAndKeepsItsVisibleCenter() {
        val rounded = outline(24f) as Outline.Rounded

        for (point in listOf(Offset(1f, 1f), Offset(199f, 1f), Offset(1f, 99f), Offset(199f, 99f))) {
            assertFalse(rounded.roundRect.contains(point))
        }
        assertTrue(rounded.roundRect.contains(Offset(100f, 1f)))
        assertTrue(rounded.roundRect.contains(Offset(100f, 50f)))
    }

    @Test
    fun compactBarRadiusCannotExceedHalfItsHeight() {
        val rounded = outline(32f, Rect(0f, 0f, 200f, 48f)) as Outline.Rounded

        assertEquals(24f, rounded.roundRect.topLeftCornerRadius.x, 0f)
        assertFalse(rounded.roundRect.contains(Offset(1f, 1f)))
        assertTrue(rounded.roundRect.contains(Offset(100f, 1f)))
    }

    @Test
    fun outlineKeepsItsOffsetInTheHostCoordinateSpace() {
        val rounded = outline(20f, Rect(10f, 30f, 210f, 130f)) as Outline.Rounded

        assertFalse(rounded.roundRect.contains(Offset(11f, 31f)))
        assertTrue(rounded.roundRect.contains(Offset(110f, 31f)))
        assertFalse(rounded.roundRect.contains(Offset(110f, 20f)))
    }

    @Test
    fun expandedEndpointKeepsAllCornersAvailable() {
        val radius = sharedContainerCornerRadius(24f, 46f, 1f)
        val rectangle = outline(radius) as Outline.Rectangle

        assertTrue(rectangle.rect.contains(Offset(1f, 1f)))
        assertTrue(rectangle.rect.contains(Offset(199f, 99f)))
    }

    @Test
    fun reversingProgressRestoresTheSameHitContour() {
        val cornerPoint = Offset(16f, 5f)
        for ((progress, expectedHit) in listOf(0.2f to true, 0.99f to false, 0.2f to true)) {
            val rounded = outline(sharedContainerCornerRadius(24f, 46f, progress)) as Outline.Rounded
            assertEquals(expectedHit, rounded.roundRect.contains(cornerPoint))
        }
    }

    @Test
    fun existingCallersKeepTheirRectangularDefault() {
        val bounds = Rect(10f, 30f, 210f, 130f)
        val rectangle = PlayerSheetInputShape(bounds, null).createOutline(
            Size(240f, 160f), LayoutDirection.Ltr, Density(1f),
        ) as Outline.Rectangle

        assertEquals(bounds, rectangle.rect)
    }

    @Test
    fun emptyBoundsHaveNoHitArea() {
        val rectangle = outline(24f, Rect.Zero) as Outline.Rectangle

        assertEquals(Rect.Zero, rectangle.rect)
        assertFalse(rectangle.rect.contains(Offset.Zero))
    }

    private fun outline(radius: Float, bounds: Rect = Rect(0f, 0f, 200f, 100f)): Outline =
        PlayerSheetInputShape(bounds, null, cornerRadiusPx = radius).createOutline(
            Size(240f, 160f), LayoutDirection.Ltr, Density(1f),
        )
}
