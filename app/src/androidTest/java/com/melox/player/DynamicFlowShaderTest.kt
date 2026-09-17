package com.melox.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.melox.player.ui.component.playback.DynamicFlowBackgroundState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DynamicFlowShaderTest {
    private fun solid(color: Int, width: Int = 2, height: Int = 3) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }

    private fun pixels(state: DynamicFlowBackgroundState): List<Int> {
        val output = solid(Color.BLACK, 7, 11)
        state.drawTo(Canvas(output), 7f, 11f)
        return List(77) { output.getPixel(it % 7, it / 7) }
    }

    @Test
    fun shaderFadeCoversViewportAndPreservesEndpoints() {
        val state = DynamicFlowBackgroundState()
        state.publishFrame(solid(Color.RED))
        state.beginTransition(solid(Color.BLUE, 1, 1))
        assertTrue(pixels(state).all { it == Color.RED })
        state.updateTransition(0.5f)
        assertTrue(pixels(state).all {
            Color.alpha(it) == 255 && Color.red(it) in 126..129 &&
                Color.blue(it) in 126..129 && Color.green(it) == 0
        })
        state.updateTransition(1f)
        val beforeRelease = pixels(state)
        state.publishFrame(requireNotNull(state.displayedFrame))
        assertEquals(beforeRelease, pixels(state))
        assertTrue(beforeRelease.all { it == Color.BLUE })
    }

    @Test
    fun identicalFramesDoNotDarkenDuringFade() {
        val state = DynamicFlowBackgroundState()
        state.publishFrame(solid(Color.GRAY))
        state.beginTransition(solid(Color.GRAY))
        for (step in 0..100) {
            state.updateTransition(step / 100f)
            assertTrue(pixels(state).all { it == Color.GRAY })
        }
    }

    @Test
    fun interruptedFadePreservesMixtureBeforeSourceBuffersAreReused() {
        val red = solid(Color.RED)
        val blue = solid(Color.BLUE)
        val state = DynamicFlowBackgroundState()
        state.publishFrame(red)
        state.beginTransition(blue)
        state.updateTransition(0.25f)
        val before = pixels(state)
        state.freezeTransition()
        red.eraseColor(Color.YELLOW)
        blue.eraseColor(Color.YELLOW)
        state.beginTransition(solid(Color.GREEN))
        assertEquals(before, pixels(state))
        state.updateTransition(1f)
        assertTrue(pixels(state).all { it == Color.GREEN })
    }
}
