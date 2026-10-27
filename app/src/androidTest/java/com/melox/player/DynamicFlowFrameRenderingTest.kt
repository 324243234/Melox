package com.melox.player

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.melox.player.ui.component.playback.DynamicFlowBackgroundState
import com.melox.player.ui.component.playback.DynamicFlowFrameBufferPool
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class DynamicFlowFrameRenderingTest {
    @Test
    fun resizePreparesTheCorrectPixelsBeforeDrawingWithoutAdvancingTheClock() {
        val cover = cover()
        val pool = DynamicFlowFrameBufferPool()
        val state = DynamicFlowBackgroundState().apply { elapsedMillis = 17_000L }
        var renderCount = 0
        state.setViewportRenderer { size, _ ->
            renderCount++
            render(pool, cover, state.displayedFrame, size.width, size.height, 123, state.elapsedMillis)
        }
        for (size in listOf(IntSize(156, 70), IntSize(70, 156), IntSize(156, 70))) {
            state.prepareViewport(size, 420)
            val frame = requireNotNull(state.displayedFrame)
            val expected = render(DynamicFlowFrameBufferPool(), cover, null,
                size.width, size.height, 123, 17_000L)
            assertArrayEquals("The first resized frame must use current geometry", pixels(expected), pixels(frame))
            assertEquals(17_000L, state.elapsedMillis)
            val count = renderCount
            state.prepareViewport(size, 420)
            assertEquals("Ordinary draws must reuse the prepared frame", count, renderCount)
        }
        assertEquals(3, renderCount)
    }

    @Test
    fun outgoingTransitionFramesCropUniformlyWhenTheAspectRatioChanges() {
        val oldFrame = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
            for (y in 0 until height) for (x in 0 until 20) {
                setPixel(x, y, Color.RED)
                setPixel(width - 1 - x, y, Color.GREEN)
            }
        }
        val state = DynamicFlowBackgroundState()
        state.publishFrame(oldFrame)
        state.beginTransition(Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888))
        val output = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888)
        state.drawTo(Canvas(output), 40f, 80f)
        assertTrue("The old landscape frame must be cropped, not squeezed", pixels(output).all { it == Color.BLUE })
        state.freezeTransition()
        assertEquals(40, requireNotNull(state.displayedFrame).width)
        assertEquals(80, requireNotNull(state.displayedFrame).height)
        state.drawTo(Canvas(output), 40f, 80f)
        assertTrue("Interrupted fading must preserve the new viewport's crop", pixels(output).all { it == Color.BLUE })
    }

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
