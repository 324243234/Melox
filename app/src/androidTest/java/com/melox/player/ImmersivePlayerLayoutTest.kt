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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
import com.melox.player.ui.screen.playback.PLAYER_PROGRESS_IDLE_HEIGHT
import com.melox.player.ui.screen.playback.PLAYER_PROGRESS_TOUCH_HEIGHT
import com.melox.player.ui.screen.playback.PLAYER_PROGRESS_TIME_SPACING
import com.melox.player.ui.screen.playback.PlayerLyricActionsRow
import com.melox.player.ui.screen.playback.PlayerDetails
import com.melox.player.ui.screen.playback.PlayerVerticalSpacing
import com.melox.player.ui.screen.playback.PortraitPlayerLayout
import com.melox.player.ui.screen.playback.playerChromeVisibility
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
        val translationToggles = AtomicInteger()
        val translationSheets = AtomicInteger()
        val lyricSettings = AtomicInteger()
        val translation = mutableStateOf(true)
        val lyricActionsVisible = mutableStateOf(true)
        val lyricViewport = AtomicReference(Rect.Zero)
        val spacing = PlayerVerticalSpacing(artworkToProgress = 0.dp)
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
                                        ))
                                        .semantics { contentDescription = HEADER_DESCRIPTION }
                                        .onGloballyPositioned { header.set(it.boundsInRoot()) },
                                )
                                PortraitPlayerLayout(
                                    spacing = spacing,
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    artworkBottomOverlap =
                                        (PLAYER_PROGRESS_TOUCH_HEIGHT - PLAYER_PROGRESS_IDLE_HEIGHT) / 2f,
                                    artworkContent = {
                                        Box(Modifier.fillMaxSize().onGloballyPositioned {
                                            lyricViewport.set(it.boundsInRoot())
                                        }) {
                                            Box(Modifier.align(Alignment.Center).size(96.dp).onGloballyPositioned {
                                                artwork.set(it.boundsInRoot())
                                            })
                                            if (lyricActionsVisible.value) {
                                                PlayerLyricActionsRow(
                                                    showLyricsTranslation = translation.value,
                                                    emphasisControlColor = Color.White,
                                                    onToggleLyricsTranslation = {
                                                        translation.value = !translation.value
                                                        translationToggles.incrementAndGet()
                                                    },
                                                    onOpenTranslationSettings = { translationSheets.incrementAndGet() },
                                                    onOpenLyricsSettings = { lyricSettings.incrementAndGet() },
                                                    enabled = !immersive.value,
                                                    modifier = Modifier.align(Alignment.BottomCenter)
                                                        .width(320.dp).padding(bottom = PLAYER_PROGRESS_TIME_SPACING + 12.dp)
                                                        .playerChromeVisibility(!immersive.value),
                                                )
                                            }
                                        }
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
                                            onOpenAudioSettings = {}, onOpenSleepTimer = {},
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
            fun nodeBounds(predicate: (AccessibilityNodeInfo) -> Boolean): Rect {
                val node = requireNotNull(findNode(instrumentation.uiAutomation.rootInActiveWindow, predicate))
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                val origin = IntArray(2)
                instrumentation.runOnMainSync { view.getLocationOnScreen(origin) }
                return Rect((bounds.left - origin[0]).toFloat(), (bounds.top - origin[1]).toFloat(),
                    (bounds.right - origin[0]).toFloat(), (bounds.bottom - origin[1]).toFloat())
            }
            fun actionBounds(resource: Int) = nodeBounds {
                it.contentDescription?.toString() == view.context.getString(resource)
            }
            val progressBounds = nodeBounds { it.rangeInfo != null }
            val progress = progressBounds.center
            val queue = actionBounds(R.string.open_queue).center
            val transport = actionBounds(R.string.play).center
            val translateBounds = actionBounds(R.string.lyrics_translation)
            val settingsBounds = actionBounds(R.string.player_settings_open)
            val moreBounds = actionBounds(R.string.track_actions)
            val timeBounds = nodeBounds { it.text?.toString() == "00:00" }
            val trackTop = progressBounds.center.y - PLAYER_PROGRESS_IDLE_HEIGHT.value * density / 2f
            val trackBottom = progressBounds.center.y + PLAYER_PROGRESS_IDLE_HEIGHT.value * density / 2f
            assertEquals(moreBounds.center.x, translateBounds.center.x, density)
            assertEquals(settingsBounds.bottom, translateBounds.bottom, density)
            assertEquals(lyricViewport.get().bottom, trackTop, density)
            assertEquals(40f * density, translateBounds.height, density)
            assertEquals(
                12f * density,
                (trackTop - translateBounds.bottom) - (timeBounds.top - trackBottom),
                density,
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
            instrumentation.runOnMainSync { lyricActionsVisible.value = false }
            SystemClock.sleep(250)
            instrumentation.waitForIdleSync()
            assertEquals(original, listOf(header.get(), artwork.get(), details.get()))
            val artworkPageRoot = instrumentation.uiAutomation.rootInActiveWindow
            assertFalse(hasDescription(artworkPageRoot, view.context.getString(R.string.lyrics_translation)))
            assertFalse(hasDescription(artworkPageRoot, view.context.getString(R.string.player_settings_open)))
            instrumentation.runOnMainSync { lyricActionsVisible.value = true }
            settle()
            press(header.get().center, long = true)
            assertEquals(0, headerActions.get())
            press(translateBounds.center)
            assertFalse(translation.value)
            assertEquals(1, translationToggles.get())
            press(translateBounds.center, long = true)
            assertEquals(1, translationSheets.get())
            assertEquals(1, translationToggles.get())
            press(settingsBounds.center)
            assertEquals(1, lyricSettings.get())
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
            assertFalse(hasDescription(root, view.context.getString(R.string.lyrics_translation)))
            press(translateBounds.center, long = true)
            assertEquals(1, translationSheets.get())
            assertEquals(1, translationToggles.get())
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

    private fun findNode(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        return (0 until node.childCount).firstNotNullOfOrNull { findNode(node.getChild(it), predicate) }
    }

    private fun hasDescription(node: AccessibilityNodeInfo?, description: String): Boolean =
        findNode(node) { it.contentDescription?.toString() == description } != null

    private companion object {
        const val HEADER_DESCRIPTION = "Immersive layout header"
    }
}
