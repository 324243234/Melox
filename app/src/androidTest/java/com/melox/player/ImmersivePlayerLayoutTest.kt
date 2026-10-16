package com.melox.player

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.melox.player.model.PlaybackQueueItem
import com.melox.player.model.PlaybackUiState
import com.melox.player.ui.screen.playback.PLAYER_PLAY_PAUSE_TOUCH_SIZE
import com.melox.player.ui.screen.playback.PLAYER_PROGRESS_TOUCH_HEIGHT
import com.melox.player.ui.screen.playback.PlayerDetails
import com.melox.player.ui.screen.playback.PlayerVerticalSpacing
import com.melox.player.ui.screen.playback.PortraitPlayerLayout
import com.melox.player.ui.screen.playback.playerChromeVisibility
import com.melox.player.ui.screen.playback.playerProgressLayoutHeight
import com.melox.player.ui.screen.playback.rememberPlayerHeaderGestureModifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(AndroidJUnit4::class)
class ImmersivePlayerLayoutTest {
    @Test
    fun fadingChromeKeepsBoundsAndDisablesHiddenActionsWhileTransportWorks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val immersive = mutableStateOf(false)
        val ready = CountDownLatch(1)
        val header = AtomicReference(Rect.Zero)
        val artwork = AtomicReference(Rect.Zero)
        val details = AtomicReference(Rect.Zero)
        val seeks = AtomicInteger()
        val queues = AtomicInteger()
        val headerActions = AtomicInteger()
        val playbackClicks = AtomicInteger()
        val spacing = PlayerVerticalSpacing()
        val item = PlaybackQueueItem(
            mediaId = "layout-test", trackId = null, contentUri = "content://layout-test",
            title = "Layout test", artist = null, album = null, durationMs = 120_000,
            dateModifiedEpochSeconds = 0, fileSizeBytes = 0,
        )
        lateinit var view: ComposeView
        var density = 1f
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                density = activity.resources.displayMetrics.density
                view = ComposeView(activity).apply {
                    setContent {
                        MiuixTheme {
                            Column(Modifier.fillMaxSize().width(320.dp)) {
                                Box(
                                    Modifier.width(320.dp).height(48.dp)
                                        .playerChromeVisibility(!immersive.value)
                                        .then(rememberPlayerHeaderGestureModifier(
                                            enabled = !immersive.value,
                                            playWhenReady = false,
                                            onTogglePlayPause = { headerActions.incrementAndGet() },
                                            onPrevious = { headerActions.incrementAndGet() },
                                            onNext = { headerActions.incrementAndGet() },
                                            onOpenLyricsSettings = { headerActions.incrementAndGet() },
                                        ))
                                        .semantics { contentDescription = HEADER_DESCRIPTION }
                                        .onGloballyPositioned { header.set(it.boundsInRoot()) },
                                )
                                PortraitPlayerLayout(
                                    spacing = spacing,
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    artworkContent = {
                                        Box(Modifier.size(96.dp).onGloballyPositioned {
                                            artwork.set(it.boundsInRoot())
                                        })
                                    },
                                    detailsContent = {
                                        PlayerDetails(
                                            contentWidth = 320.dp,
                                            playback = PlaybackUiState(
                                                queue = listOf(item), currentIndex = 0,
                                                durationMs = item.durationMs,
                                            ),
                                            emphasisControlColor = Color.White,
                                            immersiveMode = immersive.value,
                                            onToggleImmersiveMode = { immersive.value = !immersive.value },
                                            onTogglePlayPause = { playbackClicks.incrementAndGet() },
                                            onPrevious = { true }, onNext = { true },
                                            onSeek = { seeks.incrementAndGet() },
                                            onPreviewPositionChange = {},
                                            onCyclePlaybackMode = {},
                                            onOpenPlaybackOptions = {}, onOpenLyricsSettings = {},
                                            onOpenQueue = { queues.incrementAndGet() },
                                            onOpenTrackActions = {},
                                            modifier = Modifier.width(320.dp).onGloballyPositioned {
                                                details.set(it.boundsInRoot())
                                                ready.countDown()
                                            },
                                            spacing = spacing,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                activity.setContentView(view)
            }
            assertTrue("Player did not measure", ready.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            val original = listOf(header.get(), artwork.get(), details.get())
            val panel = details.get()
            val progress = Offset(panel.center.x, panel.top + PLAYER_PROGRESS_TOUCH_HEIGHT.value * density / 2)
            val queue = Offset(panel.center.x + 60 * density, panel.bottom - 20 * density)
            val transport = Offset(
                panel.center.x,
                panel.top + (playerProgressLayoutHeight(spacing.progressToPrimary).value +
                    PLAYER_PLAY_PAUSE_TOUCH_SIZE.value / 2) * density,
            )
            fun press(point: Offset, long: Boolean = false) {
                val down = SystemClock.uptimeMillis()
                fun event(action: Int) = instrumentation.runOnMainSync {
                    MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.x, point.y, 0).also {
                        view.dispatchTouchEvent(it)
                        it.recycle()
                    }
                }
                event(MotionEvent.ACTION_DOWN)
                SystemClock.sleep(if (long) ViewConfiguration.getLongPressTimeout().toLong() + 100 else 70)
                event(MotionEvent.ACTION_UP)
                instrumentation.waitForIdleSync()
            }
            fun settle() {
                SystemClock.sleep(250)
                instrumentation.waitForIdleSync()
                assertEquals(original, listOf(header.get(), artwork.get(), details.get()))
            }
            press(progress)
            press(queue)
            assertEquals(1, seeks.get())
            assertEquals(1, queues.get())
            val visibleRoot = instrumentation.uiAutomation.rootInActiveWindow
            assertTrue(hasDescription(visibleRoot, HEADER_DESCRIPTION))
            assertTrue(hasDescription(visibleRoot, view.context.getString(R.string.open_queue)))

            press(transport, long = true)
            assertTrue(immersive.value)
            assertEquals(0, playbackClicks.get())
            settle()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            assertFalse(hasDescription(root, HEADER_DESCRIPTION))
            assertFalse(hasDescription(root, view.context.getString(R.string.open_queue)))
            press(progress)
            press(queue)
            press(header.get().center, long = true)
            assertEquals(1, seeks.get())
            assertEquals(1, queues.get())
            assertEquals(0, headerActions.get())

            press(transport)
            assertEquals(1, playbackClicks.get())
            press(transport, long = true)
            assertFalse(immersive.value)
            settle()
            press(queue)
            assertEquals(2, queues.get())
        }
    }

    private fun hasDescription(node: AccessibilityNodeInfo?, description: String): Boolean {
        if (node == null) return false
        if (node.contentDescription?.toString() == description) return true
        return (0 until node.childCount).any { hasDescription(node.getChild(it), description) }
    }

    private companion object {
        const val HEADER_DESCRIPTION = "Immersive layout header"
    }
}
