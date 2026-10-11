package com.melox.player

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.melox.player.model.AppSettings
import com.melox.player.model.LyricAnimationMode
import com.melox.player.model.LyricLine
import com.melox.player.model.LyricsDocument
import com.melox.player.model.LyricsFormat
import com.melox.player.model.LyricsSource
import com.melox.player.model.LyricsUiState
import com.melox.player.ui.screen.playback.PlayerContentPager
import com.melox.player.ui.screen.playback.PlayerLayout
import com.melox.player.ui.screen.playback.playerLyricsAreActive
import com.melox.player.ui.screen.playback.LYRICS_MANUAL_FOLLOW_RESUME_DELAY_MS
import com.melox.player.ui.theme.MeloxTheme
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricPagerGestureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val clock = BroadcastFrameClock()
    private var frameNanos = 0L
    private val texts = List(12) { "Gesture lyric row $it" }

    @Test
    fun horizontalPagingRetainsAutomaticFollowButVerticalListDragSuspendsIt() {
        val position = mutableStateOf(0L)
        val updateTime = mutableStateOf(SystemClock.elapsedRealtime())
        val playing = mutableStateOf(true)
        val resumeKey = mutableStateOf(0)
        val document = LyricsDocument(
            texts.mapIndexed { index, text ->
                LyricLine("", index * 60_000L, (index + 1) * 60_000L, text, emptyList(), null)
            },
            LyricsFormat.LRC, LyricsSource.SIDECAR,
        )
        lateinit var pager: PagerState
        lateinit var view: ComposeView
        lateinit var recomposer: Recomposer
        lateinit var recomposition: Job
        val bounds = Rect()
        var density = 1f
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                recomposer = Recomposer(Dispatchers.Main + clock)
                recomposition = CoroutineScope(Dispatchers.Main + clock).launch {
                    recomposer.runRecomposeAndApplyChanges()
                }
                view = ComposeView(activity).apply {
                    setParentCompositionContext(recomposer)
                    setContent {
                        MeloxTheme(AppSettings()) {
                            pager = rememberPagerState(initialPage = 1, pageCount = { 2 })
                            PlayerContentPager(
                                pagerState = pager, lyrics = LyricsUiState.Available(document),
                                positionMs = position.value,
                                positionUpdateElapsedRealtimeMs = updateTime.value,
                                playbackIteration = 0L, playbackSpeed = 1f,
                                previewPositionMs = null, isPlaying = playing.value,
                                lyricsActive = playerLyricsAreActive(
                                    PlayerLayout.PORTRAIT, pager.currentPage, pager.targetPage,
                                ),
                                onSeek = {}, contentWidth = 280.dp,
                                controlColor = Color.White, emphasisControlColor = Color.White,
                                lyricsPagingEnabled = true, lyricFontScale = 1f, lyricFontWeight = 400,
                                lyricAnimationMode = LyricAnimationMode.NEVER, lyricBlurEnabled = true,
                                centerLyrics = false, lyricCenterOffsetY = 0.dp,
                                showLyricsTranslation = false, showBottomFade = false,
                                resumeFollowRequestKey = resumeKey.value, seekRequestKey = 0,
                                seekPositionMs = 0L,
                                artworkContent = { Box(Modifier.fillMaxSize().background(Color.Black)) },
                                modifier = Modifier.fillMaxSize().background(Color.Black),
                            )
                        }
                    }
                }
                activity.setContentView(view)
                density = activity.resources.displayMetrics.density
            }
            try {
                frames(120)
                scenario.onActivity {
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    bounds.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
                }
                assertCentered(0, bounds.centerY())
                // Vertical noise exceeds slop after the pager has acquired the horizontal drag.
                swipe(bounds, 0.2f, 0.8f, 28f * density)
                frames(90)
                assertEquals(0, pager.settledPage)
                swipe(bounds, 0.8f, 0.2f, -28f * density)
                frames(90)
                assertEquals(1, pager.settledPage)
                assertCentered(0, bounds.centerY())
                scenario.onActivity { position.value = 60_000L; updateTime.value = SystemClock.elapsedRealtime() }
                frames(120)
                assertCentered(1, bounds.centerY())

                scenario.onActivity { playing.value = false }
                frames(2)
                swipe(bounds, 0.5f, 0.5f, -100f * density)
                frames(90)
                val before = rowBounds(2).centerY()
                assertTrue("Vertical drag did not move the list", abs(rowBounds(1).centerY() - bounds.centerY()) > 12)
                scenario.onActivity {
                    position.value = 120_000L
                    updateTime.value = SystemClock.elapsedRealtime()
                    playing.value = true
                }
                frames(30)
                assertTrue("Browsing failed to suspend automatic centering", abs(rowBounds(2).centerY() - before) <= 3)
                scenario.onActivity { resumeKey.value++ }
                frames(120)
                assertCentered(2, bounds.centerY())

                // Page visibility must not cancel the playback-based browsing timeout.
                scenario.onActivity { playing.value = false }
                frames(2)
                swipe(bounds, 0.5f, 0.5f, -100f * density)
                frames(90)
                assertTrue("Second vertical drag did not browse", abs(rowBounds(2).centerY() - bounds.centerY()) > 12)
                scenario.onActivity {
                    position.value = 180_000L
                    updateTime.value = SystemClock.elapsedRealtime()
                    playing.value = true
                }
                frames(2)
                swipe(bounds, 0.2f, 0.8f, 0f)
                frames(90)
                assertEquals(0, pager.settledPage)
                // Coroutine delay uses real time, independently of the test's frame clock.
                SystemClock.sleep(LYRICS_MANUAL_FOLLOW_RESUME_DELAY_MS + 100L)
                frames(2)
                swipe(bounds, 0.8f, 0.2f, 0f)
                frames(90)
                assertEquals(1, pager.settledPage)
                assertCentered(3, bounds.centerY())
                scenario.onActivity {
                    position.value = 240_000L
                    updateTime.value = SystemClock.elapsedRealtime()
                }
                frames(120)
                assertCentered(4, bounds.centerY())
            } finally {
                scenario.onActivity { recomposer.cancel(); recomposition.cancel() }
            }
        }
    }

    private fun swipe(bounds: Rect, startX: Float, endX: Float, deltaY: Float) {
        val downTime = SystemClock.uptimeMillis()
        fun pointer(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action,
                bounds.left + bounds.width() * (startX + (endX - startX) * fraction),
                bounds.centerY() + deltaY * fraction, 0,
            )
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            frames(1)
        }
        pointer(MotionEvent.ACTION_DOWN, 0f)
        for (step in 1..12) pointer(MotionEvent.ACTION_MOVE, step / 12f)
        // Release without a fling so the subsequent follow assertion measures browsing state.
        frames(15)
        pointer(MotionEvent.ACTION_UP, 1f)
    }

    private fun rowBounds(index: Int): Rect {
        val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == texts[index]) return node
            for (child in 0 until node.childCount) {
                node.getChild(child)?.let { find(it)?.let { found -> return found } }
            }
            return null
        }
        val row = requireNotNull(find(root)) { "Missing lyric $index" }
        return Rect().also(row::getBoundsInScreen)
    }

    private fun assertCentered(index: Int, centerY: Int) {
        assertTrue("Lyric $index did not follow to center", abs(rowBounds(index).centerY() - centerY) <= 3)
    }

    private fun frames(count: Int) {
        repeat(count) {
            instrumentation.runOnMainSync {
                Snapshot.sendApplyNotifications()
                frameNanos += 16_666_667L
                clock.sendFrame(frameNanos)
            }
            instrumentation.waitForIdleSync()
        }
    }
}
