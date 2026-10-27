package com.melox.player

import com.melox.player.ui.component.playback.sharedContainerCornerRadius
import com.melox.player.ui.component.playback.sharedContainerSourceCornerRadius
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingMiniPlayerCornerTest {
    @Test
    fun compactFloatingStartsFromItsActualRadiusAndHeadsDirectlyToScreenCorners() {
        val source = sharedContainerSourceCornerRadius(32f, 350f, 48f)
        assertEquals(24f, source, 0f)
        for (progress in listOf(0f, 0.01f, 0.2f, 0.6f, 0.9f, 0.6f, 0.2f, 0.01f, 0f)) {
            assertEquals(
                24f + (46f - 24f) * progress,
                sharedContainerCornerRadius(source, 46f, progress),
                0.001f,
            )
        }
        assertEquals(0f, sharedContainerCornerRadius(source, 46f, 1f), 0f)
    }

    @Test
    fun pressedSourceScaleRetainsTheCompactRadiusWithoutALargerBarPhase() {
        val scale = 1.04f
        val source = sharedContainerSourceCornerRadius(32f * scale, 350f * scale, 48f * scale)
        assertEquals(24f * scale, source, 0.001f)
        assertEquals(source / 2f, sharedContainerCornerRadius(source, 0f, 0.5f), 0.001f)
    }

    @Test
    fun normalAndRegularFloatingSourceCornersRemainUnchanged() {
        assertEquals(18f, sharedContainerSourceCornerRadius(18f, 350f, 54f), 0f)
        assertEquals(18f, sharedContainerSourceCornerRadius(18f, 350f, 68f), 0f)
        assertEquals(32f, sharedContainerSourceCornerRadius(32f, 350f, 64f), 0f)
    }
}
