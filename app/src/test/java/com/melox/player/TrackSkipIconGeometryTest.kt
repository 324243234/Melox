package com.melox.player

import com.melox.player.ui.component.playback.TRACK_SKIP_BAR_TRAVEL
import com.melox.player.ui.component.playback.TRACK_SKIP_TRIANGLE_TRAVEL
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSkipIconGeometryTest {
    @Test
    fun splitNextMatchesOriginalOutline() = assertRestingSilhouette("next")

    @Test
    fun splitPreviousMatchesOriginalOutline() = assertRestingSilhouette("previous")

    @Test
    fun bothDirectionsExposeASeparateTriangleWithoutClippingTheTouchRegion() {
        for ((name, direction) in listOf("next" to 1, "previous" to -1)) {
            val triangle = Area(vector(name, "triangle"))
            val bar = Area(vector(name, "bar"))
            triangle.transform(AffineTransform.getTranslateInstance(direction * TRACK_SKIP_TRIANGLE_TRAVEL.toDouble(), 0.0))
            bar.transform(AffineTransform.getTranslateInstance(direction * TRACK_SKIP_BAR_TRAVEL.toDouble(), 0.0))
            val overlap = Area(triangle).apply { intersect(bar) }
            assertTrue(overlap.isEmpty)
            val bounds = Area(triangle).apply { add(bar) }.bounds2D
            val touchPadding = (56.0 - 26.0) / 2.0
            assertTrue(bounds.minX * 26.0 / 24.0 >= -touchPadding)
            assertTrue(bounds.maxX * 26.0 / 24.0 <= 26.0 + touchPadding)
            assertTrue(bounds.height > 22.0)
        }
    }

    private fun assertRestingSilhouette(name: String) {
        val original = vector(name, "track")
        val triangle = vector(name, "triangle")
        val bar = vector(name, "bar")
        for (row in 0 until 192) {
            for (column in 0 until 192) {
                val x = (column + 0.5) / 8.0
                val y = (row + 0.5) / 8.0
                assertEquals(
                    "$name outline differs at ($x, $y)",
                    original.contains(x, y),
                    triangle.contains(x, y) || bar.contains(x, y),
                )
            }
        }
    }

    private fun vector(name: String, part: String): Path2D.Double {
        val xml = if (part == "track") {
            val source = if (name == "next") "Next Track.svg" else "Previous Track.svg"
            File("src/test/resources/icons/$source").readText()
        } else {
            File("src/main/res/drawable/ic_player_${name}_$part.xml").readText()
        }
        val pathAttribute = if (part == "track") "d" else "android:pathData"
        val data = Regex("\\b$pathAttribute=\"([^\"]+)\"").find(xml)!!.groupValues[1]
        return Path2D.Double().apply {
            for (command in Regex("([MLCHVZ])([^MLCHVZ]*)").findAll(data)) {
                val values = Regex("-?\\d+(?:\\.\\d+)?").findAll(command.groupValues[2])
                    .map { it.value.toDouble() }.toList()
                when (command.groupValues[1]) {
                    "M" -> moveTo(values[0], values[1])
                    "L" -> lineTo(values[0], values[1])
                    "H" -> lineTo(values[0], currentPoint.y)
                    "V" -> lineTo(currentPoint.x, values[0])
                    "C" -> curveTo(values[0], values[1], values[2], values[3], values[4], values[5])
                    "Z" -> closePath()
                }
            }
        }
    }
}
