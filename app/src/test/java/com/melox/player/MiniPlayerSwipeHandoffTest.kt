package com.melox.player

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.melox.player.ui.component.playback.MiniPlayerSwipeState
import com.melox.player.ui.component.playback.PlayerSheetTransitionState
import com.melox.player.ui.component.playback.rememberMiniPlayerSwipeState
import com.melox.player.ui.component.playback.scaledMiniPlayerBounds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerSwipeHandoffTest {
    private val window = IntSize(360, 800)
    private val bar = Rect(16f, 700f, 344f, 764f)
    private val content = Rect(16f, 708f, 344f, 756f)
    private val controls = Rect(240f, 712f, 328f, 752f)
    private val artwork = Rect(32f, 708f, 80f, 756f)

    private fun sheet(): PlayerSheetTransitionState = PlayerSheetTransitionState().apply {
        updateWindowSize(window)
        updateMiniPlayerBounds(bar, window)
        updateMiniPlayerContentBounds(content, window)
        updateMiniPlayerControlsBounds(controls, window)
        updateMiniArtworkBounds(artwork, window)
        updateFullPlayerBounds(Rect(0f, 0f, 360f, 800f), window)
        updateFullArtworkBounds(Rect(48f, 96f, 312f, 360f), window)
    }

    @Test
    fun openingCapturesTheVisibleOffsetForAllSourcesAndThePreparationFrame() {
        for (offset in listOf(-4f, 4f)) {
            for (scale in listOf(1f, 1.05f)) {
                val state = sheet()
                state.open(miniPlayerScale = scale, miniPlayerOffsetX = offset)
                assertFalse(state.sharedLayersReady)
                assertEquals(offset, state.capturedMiniPlayerOffsetX!!, 0f)
                for ((source, rendered) in listOf(
                    bar to state.overlayMiniPlayerBounds,
                    content to state.overlayMiniPlayerContentBounds,
                    controls to state.overlayMiniPlayerControlsBounds,
                    artwork to state.overlayMiniArtworkBounds,
                )) {
                    assertEquals(
                        scaledMiniPlayerBounds(source, bar, scale).translate(Offset(offset, 0f)),
                        rendered,
                    )
                }
            }
        }
    }

    @Test
    fun anInterruptedReturnKeepsTheNewDragAndThenSettlesExactlyAtZero() = runBlocking {
        lateinit var swipe: MiniPlayerSwipeState
        withArtworkComposition(content = {
            swipe = rememberMiniPlayerSwipeState(true, false)
        }) {
            frame(0)
            swipe.dragBy(-220f)
            swipe.release()
            frame(16)
            frame(116)
            assertTrue(swipe.distance in -220f..-1f)
            swipe.begin()
            val interrupted = swipe.distance
            swipe.dragBy(50f)
            frame(200)
            assertEquals(interrupted + 50f, swipe.distance, 0f)
            swipe.release()
            frame(216)
            frame(2216)
            assertEquals(0f, swipe.distance, 0f)
        }
    }

    @Test
    fun recordingAndGlassChangesCancelOldReturnJobsAndClearTheOffset() = runBlocking {
        val recording = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        lateinit var swipe: MiniPlayerSwipeState
        withArtworkComposition(content = {
            swipe = rememberMiniPlayerSwipeState(enabled.value, recording.value)
        }) {
            frame(0)
            swipe.dragBy(-220f)
            swipe.release()
            frame(16)
            frame(116)
            recording.value = true
            frame(132)
            assertEquals(0f, swipe.distance, 0f)
            frame(2132)
            assertEquals(0f, swipe.distance, 0f)
            recording.value = false
            frame(2148)
            swipe.dragBy(180f)
            enabled.value = false
            frame(2164)
            assertEquals(0f, swipe.distance, 0f)
            enabled.value = true
            frame(2180)
            assertEquals(0f, swipe.distance, 0f)
        }
    }

    @Test
    fun openingDuringTheSwipeReturnAndClosingRepeatedlyAlwaysEndsCentered() = runBlocking {
        val state = sheet()
        lateinit var swipe: MiniPlayerSwipeState
        withArtworkComposition(content = {
            swipe = rememberMiniPlayerSwipeState(true, state.isMounted)
            LaunchedEffect(state.animationRequest) { state.animateToTarget() }
        }) {
            frame(0)
            var time = 16L
            for (distance in listOf(-220f, 220f, -80f)) {
                swipe.begin()
                swipe.dragBy(distance)
                swipe.release()
                frame(time)
                frame(time + 100)
                val visibleOffset = swipe.translationX(bar.width, 4f)
                assertTrue(visibleOffset != 0f)
                state.open(miniPlayerScale = 1.04f, miniPlayerOffsetX = visibleOffset)
                assertEquals(visibleOffset, state.capturedMiniPlayerOffsetX!!, 0f)
                frame(time + 116)
                assertEquals(0f, swipe.distance, 0f)
                frame(time + 2116)
                assertTrue(state.isFullyExpanded)
                assertEquals(0f, state.overlayMiniPlayerOffsetX, 0f)
                state.close()
                frame(time + 2132)
                frame(time + 4132)
                assertFalse(state.isMounted)
                assertEquals(bar, state.overlayMiniPlayerBounds)
                assertEquals(0f, state.overlayMiniPlayerOffsetX, 0f)
                assertEquals(0f, swipe.translationX(bar.width, 4f), 0f)
                time += 4200
            }
        }
    }

    @Test
    fun cancelingAStartAndReopeningItsReturnKeepsContinuousGeometry() = runBlocking {
        val state = sheet()
        withArtworkComposition(content = {
            LaunchedEffect(state.animationRequest) { state.animateToTarget() }
        }) {
            frame(0)
            state.beginMiniPlayerDrag(miniPlayerScale = 1.06f, miniPlayerOffsetX = -4f)
            state.dragBy(-100f)
            val beforeCancel = state.overlayMiniPlayerBounds
            state.cancelDrag()
            assertEquals(beforeCancel, state.overlayMiniPlayerBounds)
            frame(16)
            frame(48)
            assertTrue(state.overlayMiniPlayerOffsetX in -4f..0f)
            val beforeReopen = state.overlayMiniPlayerBounds
            state.open()
            assertEquals(beforeReopen, state.overlayMiniPlayerBounds)
            frame(64)
            frame(2064)
            assertTrue(state.isFullyExpanded)
            state.close()
            frame(2080)
            frame(4080)
            assertEquals(bar, state.overlayMiniPlayerBounds)
            assertEquals(0f, state.overlayMiniPlayerOffsetX, 0f)
        }
    }

    @Test
    fun aWindowChangeClearsTheCapturedOffset() {
        val state = sheet()
        state.open(miniPlayerOffsetX = -4f)
        state.updateWindowSize(IntSize(800, 360))
        assertEquals(0f, state.overlayMiniPlayerOffsetX, 0f)
    }
}
