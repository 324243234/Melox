package com.melox.player

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.melox.player.ui.component.playback.playerSheetHostLayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerSheetInputOutlineTest {
    @Test
    fun roundShellLeavesTransparentCornersToThePage() = withShell { fixture ->
        fixture.tapCorners()
        assertEquals(4, fixture.pageTaps.get())
        assertEquals(0, fixture.shellTaps.get())

        fixture.tap(0.5f, 0.5f)
        assertEquals(1, fixture.shellTaps.get())
    }

    @Test
    fun squircleShellLeavesTransparentCornersToThePage() = withShell(squircle = true) { fixture ->
        fixture.tapCorners()
        assertEquals(4, fixture.pageTaps.get())
        assertEquals(0, fixture.shellTaps.get())

        fixture.tap(0.5f, 0.5f)
        assertEquals(1, fixture.shellTaps.get())
    }

    @Test
    fun squareEndpointKeepsTheCornersInThePlayer() = withShell(radiusDp = 0f) { fixture ->
        fixture.tapCorners()
        assertEquals(0, fixture.pageTaps.get())
        assertEquals(4, fixture.shellTaps.get())
    }

    @Test
    fun miniPlayerExclusionKeepsItsExistingInputOwnership() = withShell(excludeMini = true) { fixture ->
        fixture.tap(0.5f, 0.8f)
        assertEquals(1, fixture.pageTaps.get())
        assertEquals(0, fixture.shellTaps.get())

        fixture.tap(0.5f, 0.4f)
        assertEquals(1, fixture.shellTaps.get())
    }

    @Test
    fun dragStartedInsideContinuesAcrossTheTransparentCorner() = withShell(drag = true) { fixture ->
        val downTime = SystemClock.uptimeMillis()
        fixture.event(MotionEvent.ACTION_DOWN, downTime, 0.5f, 0.6f)
        fixture.event(MotionEvent.ACTION_MOVE, downTime, 0.5f, 0.25f)
        fixture.event(MotionEvent.ACTION_MOVE, downTime, 0.01f, 0.01f)
        fixture.event(MotionEvent.ACTION_UP, downTime, 0.01f, 0.01f)

        assertTrue(fixture.dragSamples.get() > 0)
        assertEquals(1, fixture.dragEnds.get())
        assertEquals(0, fixture.dragCancels.get())
    }

    private class Fixture {
        lateinit var view: ComposeView
        val bounds = AtomicReference(Rect.Zero)
        val pageTaps = AtomicInteger()
        val shellTaps = AtomicInteger()
        val dragSamples = AtomicInteger()
        val dragEnds = AtomicInteger()
        val dragCancels = AtomicInteger()

        fun tapCorners() {
            tap(0.01f, 0.01f)
            tap(0.99f, 0.01f)
            tap(0.01f, 0.99f)
            tap(0.99f, 0.99f)
        }

        fun tap(x: Float, y: Float) {
            val downTime = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, downTime, x, y)
            event(MotionEvent.ACTION_UP, downTime, x, y)
        }

        fun event(action: Int, downTime: Long, x: Float, y: Float) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync {
                val rect = bounds.get()
                val event = MotionEvent.obtain(
                    downTime, SystemClock.uptimeMillis(), action,
                    rect.left + rect.width * x, rect.top + rect.height * y, 0,
                )
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try {
                    view.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun withShell(
        squircle: Boolean = false,
        radiusDp: Float = 40f,
        excludeMini: Boolean = false,
        drag: Boolean = false,
        check: (Fixture) -> Unit,
    ) {
        val fixture = Fixture()
        val ready = CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                fixture.view = ComposeView(activity).apply {
                    setContent {
                        val density = LocalDensity.current
                        val shell = with(density) { Rect(0f, 0f, 200.dp.toPx(), 120.dp.toPx()) }
                        val radiusPx = with(density) { radiusDp.dp.toPx() }
                        Box(Modifier.fillMaxSize().padding(24.dp)) {
                            Box(Modifier.size(200.dp, 120.dp)
                                .onGloballyPositioned { fixture.bounds.set(it.boundsInRoot()) }
                                .drawWithContent { drawContent(); ready.countDown() }) {
                                Box(Modifier.fillMaxSize().pointerInput(Unit) {
                                    detectTapGestures { fixture.pageTaps.incrementAndGet() }
                                })
                                Box(Modifier.fillMaxSize().playerSheetHostLayer(
                                    hostBounds = shell,
                                    inputBounds = shell,
                                    miniPlayerBounds = if (excludeMini) {
                                        Rect(shell.width * 0.3f, shell.height * 0.65f,
                                            shell.width * 0.7f, shell.height * 0.95f)
                                    } else null,
                                    inputCornerRadiusPx = radiusPx,
                                    squircleEnabled = squircle,
                                ).pointerInput(Unit) {
                                    if (drag) {
                                        detectVerticalDragGestures(
                                            onDragEnd = { fixture.dragEnds.incrementAndGet() },
                                            onDragCancel = { fixture.dragCancels.incrementAndGet() },
                                        ) { change, _ ->
                                            fixture.dragSamples.incrementAndGet()
                                            change.consume()
                                        }
                                    } else {
                                        detectTapGestures { fixture.shellTaps.incrementAndGet() }
                                    }
                                })
                            }
                        }
                    }
                }
                activity.setContentView(fixture.view)
            }
            assertTrue("Input shell did not draw", ready.await(5, TimeUnit.SECONDS))
            check(fixture)
        }
    }
}
