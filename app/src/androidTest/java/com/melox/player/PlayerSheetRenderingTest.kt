package com.melox.player

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect as AndroidRect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.melox.player.ui.component.playback.PlayerSheetDragInputOverlay
import com.melox.player.ui.component.playback.PlayerSheetTransitionState
import com.melox.player.ui.component.playback.recordPlayerLayer
import com.melox.player.ui.component.playback.recordMiniPlayerLayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerSheetRenderingTest {
    @Test
    fun visibleMiniPlayerBootstrapsSharedRecordingReadiness() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val transition = PlayerSheetTransitionState()
        val viewport = mutableStateOf(IntSize.Zero)
        val ready = CountDownLatch(1)
        val intermediate = CountDownLatch(1)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(ComposeView(activity).apply {
                    setContent {
                        val miniLayer = rememberGraphicsLayer()
                        val fullLayer = rememberGraphicsLayer()
                        val window = viewport.value
                        LaunchedEffect(transition.sharedLayersReady) {
                            if (transition.sharedLayersReady) ready.countDown()
                        }
                        LaunchedEffect(transition.animationRequest) {
                            if (transition.targetOpen) transition.animateToTarget()
                        }
                        Box(Modifier.fillMaxSize().onSizeChanged {
                            viewport.value = it
                            transition.updateWindowSize(it)
                            transition.updateFullPlayerBounds(
                                Rect(0f, 0f, it.width.toFloat(), it.height.toFloat()), it,
                            )
                        }) {
                            Canvas(Modifier.fillMaxSize().recordPlayerLayer(
                                layer = fullLayer,
                                drawInPlace = transition.fullPlayerDrawsInPlace,
                                recordingGeneration = transition.currentFrameRecordingGeneration,
                                onRecorded = { generation, size ->
                                    transition.markFullFrameRecorded(window, generation, size)
                                },
                            )) { drawRect(Color.Green) }
                            Canvas(Modifier.align(Alignment.BottomCenter)
                                .fillMaxWidth().height(64.dp)
                                .onGloballyPositioned {
                                    transition.updateMiniPlayerBounds(it.boundsInRoot(), window)
                                }
                                .recordMiniPlayerLayer(
                                    layer = miniLayer,
                                    drawInPlace = !transition.isMounted || !transition.sharedLayersReady,
                                    recordingGeneration = transition.currentFrameRecordingGeneration,
                                    onRecorded = { generation, size ->
                                        transition.markMiniFrameRecorded(window, generation, size)
                                    },
                                )) { drawRect(Color.Magenta) }
                            Canvas(Modifier.fillMaxSize()) {
                                if (transition.progress in 0.1f..0.9f && transition.sharedLayersReady) {
                                    intermediate.countDown()
                                }
                            }
                        }
                    }
                })
            }
            assertTrue("Visible mini player never prepared the transition", ready.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            scenario.onActivity { transition.open() }
            assertTrue("No drawable intermediate transition frame", intermediate.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun settledContentIsNotRecordedBeforeBeingDrawnLive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val firstDraw = CountDownLatch(1)
        var recordings = 0
        lateinit var view: ComposeView

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = ComposeView(activity).apply {
                    setContent {
                        val layer = rememberGraphicsLayer()
                        Canvas(
                            Modifier.fillMaxSize().recordPlayerLayer(
                                layer = layer,
                                drawInPlace = true,
                                onRecorded = { _, _ -> recordings++ },
                            ),
                        ) {
                            drawRect(Color.Green)
                            drawRect(Color.Magenta, Offset(0f, size.height / 2f),
                                Size(size.width, size.height / 2f))
                            firstDraw.countDown()
                        }
                    }
                }
                activity.setContentView(view)
            }
            assertTrue(firstDraw.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertCompleteFrame(scenario, view)
            scenario.onActivity {
                assertEquals("A settled frame must not also record its content", 0, recordings)
            }
        }
    }

    @Test
    fun recordingHostHandoffStaysCompleteAcrossReopenings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val live = mutableStateOf(false)
        val firstDraw = CountDownLatch(1)
        lateinit var view: ComposeView

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = ComposeView(activity).apply {
                    setContent {
                        val layer = rememberGraphicsLayer()
                        Box(Modifier.fillMaxSize()) {
                            Canvas(Modifier.fillMaxSize()) { drawRect(Color.Red) }
                            Canvas(
                                Modifier.fillMaxSize().zIndex(if (live.value) 1f else -1f)
                                    .recordPlayerLayer(layer, drawInPlace = live.value) { _, _ ->
                                        firstDraw.countDown()
                                    },
                            ) {
                                drawRect(Color.Green)
                                drawRect(Color.Magenta, Offset(0f, size.height / 2f),
                                    Size(size.width, size.height / 2f))
                            }
                            if (!live.value) {
                                Canvas(Modifier.fillMaxSize()) { drawLayer(layer) }
                            }
                        }
                    }
                }
                activity.setContentView(view)
            }
            assertTrue(firstDraw.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            repeat(3) {
                scenario.onActivity { live.value = true }
                assertCompleteFrame(scenario, view)
                scenario.onActivity { live.value = false }
                instrumentation.waitForIdleSync()
            }
        }
    }

    @Test
    fun settledDrawingDoesNotDependOnTheTransitionLayersTransform() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val live = mutableStateOf(false)
        val firstDraw = CountDownLatch(1)
        lateinit var recording: GraphicsLayer
        lateinit var view: ComposeView

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = ComposeView(activity).apply {
                    setContent {
                        val layer = rememberGraphicsLayer()
                        recording = layer
                        Box(Modifier.fillMaxSize()) {
                            Canvas(
                                Modifier.fillMaxSize().recordPlayerLayer(
                                    layer = layer,
                                    drawInPlace = live.value,
                                    onRecorded = { _, _ -> firstDraw.countDown() },
                                ),
                            ) {
                                drawRect(Color.Green)
                                drawRect(
                                    Color.Magenta,
                                    topLeft = Offset(0f, size.height / 2f),
                                    size = Size(size.width, size.height / 2f),
                                )
                            }
                            if (!live.value) {
                                Canvas(Modifier.fillMaxSize()) { drawLayer(layer) }
                            }
                        }
                    }
                }
                activity.setContentView(view)
            }
            assertTrue(firstDraw.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()

            repeat(3) {
                scenario.onActivity {
                    recording.alpha = 1f
                    recording.scaleY = 1f
                    live.value = false
                }
                assertCompleteFrame(scenario, view)
                scenario.onActivity {
                    // Simulate transform/alpha residue in an independently replayed source.
                    recording.pivotOffset = Offset.Zero
                    recording.scaleY = 0.15f
                    recording.alpha = 0.1f
                    live.value = true
                }
                assertCompleteFrame(scenario, view)
            }
        }
    }

    @Test
    fun transitionInputLeavesTheExposedPageInteractiveAndBlocksHiddenControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val transition = PlayerSheetTransitionState(initialProgress = 1f)
        val firstDraw = CountDownLatch(1)
        var size = IntSize.Zero
        var pageTaps = 0
        var hiddenControlTaps = 0
        lateinit var view: ComposeView

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = ComposeView(activity).apply {
                    setContent {
                        Box(
                            Modifier.fillMaxSize().onSizeChanged {
                                if (size != IntSize.Zero) return@onSizeChanged
                                size = it
                                transition.updateFullPlayerBounds(
                                    Rect(0f, 0f, it.width.toFloat(), it.height.toFloat()),
                                )
                                transition.updateMiniPlayerBounds(
                                    Rect(6f, it.height * 0.9f, it.width - 6f, it.height * 0.98f),
                                )
                                transition.beginFullPlayerDrag()
                                transition.dragBy(it.height * 0.45f)
                            }.drawWithContent {
                                drawContent()
                                firstDraw.countDown()
                            },
                        ) {
                            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                                detectTapGestures { pageTaps++ }
                            })
                            Box(
                                Modifier.fillMaxSize().zIndex(-1f).pointerInput(Unit) {
                                    detectTapGestures { hiddenControlTaps++ }
                                },
                            )
                            PlayerSheetDragInputOverlay(
                                transition = transition,
                                hasItem = true,
                                modifier = Modifier.zIndex(2f),
                            )
                        }
                    }
                }
                activity.setContentView(view)
            }
            assertTrue(firstDraw.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()

            scenario.onActivity {
                tap(view, size.width * 0.5f, size.height * 0.1f)
                tap(view, size.width * 0.5f, size.height * 0.7f)
                assertEquals(1, pageTaps)
                assertEquals(0, hiddenControlTaps)
            }
        }
    }

    private fun assertCompleteFrame(scenario: ActivityScenario<MainActivity>, view: ComposeView) {
        val ready = CountDownLatch(1)
        lateinit var bitmap: Bitmap
        var result = -1
        scenario.onActivity { activity ->
            view.postOnAnimation {
                view.postOnAnimation {
                    val location = IntArray(2)
                    view.getLocationInWindow(location)
                    bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    PixelCopy.request(
                        activity.window,
                        AndroidRect(location[0], location[1], location[0] + view.width, location[1] + view.height),
                        bitmap,
                        { result = it; ready.countDown() },
                        Handler(Looper.getMainLooper()),
                    )
                }
            }
        }
        assertTrue("Frame was not copied", ready.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        try {
            assertEquals(AndroidColor.GREEN, bitmap.getPixel(bitmap.width / 2, bitmap.height / 4))
            assertEquals(AndroidColor.MAGENTA, bitmap.getPixel(bitmap.width / 2, bitmap.height * 3 / 4))
        } finally {
            bitmap.recycle()
        }
    }

    private fun tap(view: ComposeView, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time + if (action == MotionEvent.ACTION_UP) 16 else 0, action, x, y, 0)
            try {
                view.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }
}
