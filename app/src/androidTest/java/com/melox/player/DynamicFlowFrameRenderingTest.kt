package com.melox.player

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.melox.player.ui.component.playback.DynamicFlowFrameBufferPool
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class DynamicFlowFrameRenderingTest {
    private fun cover() = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until height) {
            for (x in 0 until width) {
                setPixel(x, y, Color.argb(if (x < 8) 96 else 255, x * 16, y * 16, 160))
            }
        }
    }

    private fun render(
        pool: DynamicFlowFrameBufferPool,
        cover: Bitmap,
        displayed: Bitmap?,
        width: Int,
        height: Int,
        seed: Int,
        time: Long,
    ) = pool.render(
        displayedFrame = displayed,
        cover = cover,
        meshSeed = seed,
        width = width,
        height = height,
        timeMillis = time,
        blurRadius = 18,
        washPrimaryArgb = 0x3d243448,
        washSecondaryArgb = 0x29121316,
        backgroundArgb = 0xff243448.toInt(),
    )

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    @Test
    fun firstMotionFramesStayOpaqueAndMatchTargetAtTheSameTime() {
        val cover = cover()
        for ((width, height) in listOf(70 to 156, 156 to 70)) {
            for (seed in listOf(0, 123, 456)) {
                for (time in listOf(0L, 17_000L, 70_000L)) {
                    val pool = DynamicFlowFrameBufferPool()
                    var displayed = render(pool, cover, null, width, height, seed, time)
                    val target = pixels(displayed)
                    assertTrue("Target must be opaque", target.all { Color.alpha(it) == 255 })
                    // Exercise every buffer twice, with history enabled but no motion.
                    repeat(6) {
                        displayed = render(pool, cover, displayed, width, height, seed, time)
                        val actual = pixels(displayed)
                        actual.indices.forEach { index ->
                            assertEquals("History must preserve opacity", 255, Color.alpha(actual[index]))
                            for (shift in listOf(0, 8, 16)) {
                                val before = (target[index] ushr shift) and 255
                                val after = (actual[index] ushr shift) and 255
                                assertTrue("History changed a stationary pixel", abs(before - after) <= 1)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun historyResetProducesTheSameTargetAsAFreshPool() {
        val pool = DynamicFlowFrameBufferPool()
        val oldCover = cover()
        var displayed: Bitmap? = null
        repeat(6) { frame ->
            displayed = render(pool, oldCover, displayed, 70, 156, 123, frame * 1_000L)
        }
        val newCover = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
        }
        pool.resetHistory()
        val reused = render(pool, newCover, displayed, 70, 156, 456, 17_000L)
        val fresh = render(DynamicFlowFrameBufferPool(), newCover, null, 70, 156, 456, 17_000L)
        assertTrue(pixels(reused).all { Color.alpha(it) == 255 })
        assertArrayEquals(pixels(fresh), pixels(reused))
    }
}
