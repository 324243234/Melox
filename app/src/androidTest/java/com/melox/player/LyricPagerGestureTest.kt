package com.melox.player

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
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
    fun retainedPagerIsReadyAfterHiddenPlaybackAndPausedSeek() = runPagerChecks(checkEntry = true)

    @Test
    fun retainedWordPagerIsReadyAfterHiddenPlaybackAndPausedSeek() =
        runPagerChecks(checkEntry = true, wordProgress = true)

    @Test
    fun onlyVerticalListDraggingSuspendsFollow() = runPagerChecks(checkEntry = false)

    @Test
    fun onlyArtworkReboundsWhileHiddenControlsStillPage() =
        runPagerChecks(checkEntry = false, checkOverscroll = true)

    private fun runPagerChecks(
        checkEntry: Boolean,
        wordProgress: Boolean = false,
        checkOverscroll: Boolean = false,
    ) {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val position = mutableStateOf(0L)
        val updateTime = mutableStateOf(SystemClock.elapsedRealtime())
        val playing = mutableStateOf(true)
        val playerActive = mutableStateOf(true)
        val discontinuityRevision = mutableStateOf(0L)
        val resumeKey = mutableStateOf(0)
        val document = LyricsDocument(
            texts.mapIndexed { index, text ->
                LyricLine("", index * 60_000L, (index + 1) * 60_000L, text, emptyList(),
                    text.takeIf { wordProgress })
            },
            LyricsFormat.LRC, LyricsSource.SIDECAR,
        )
        lateinit var pager: PagerState
        lateinit var view: ComposeView
        lateinit var recomposer: Recomposer
        lateinit var recomposition: Job
        val bounds = Rect()
        var artworkCoordinates: LayoutCoordinates? = null
        var controlCoordinates: LayoutCoordinates? = null
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
                            pager = rememberPagerState(initialPage = 0, pageCount = { 2 })
                            PlayerContentPager(
                                pagerState = pager, lyrics = LyricsUiState.Available(document),
                                positionMs = position.value,
                                positionUpdateElapsedRealtimeMs = updateTime.value,
                                playbackIteration = 0L, playbackSpeed = 1f,
                                previewPositionMs = null, isPlaying = playing.value,
                                lyricsActive = playerLyricsAreActive(
                                    PlayerLayout.PORTRAIT, pager.currentPage, pager.targetPage,
                                ),
                                playerActive = playerActive.value,
                                positionDiscontinuityRevision = discontinuityRevision.value,
                                onSeek = {}, contentWidth = 280.dp,
                                controlColor = Color.White, emphasisControlColor = Color.White,
                                lyricsPagingEnabled = true, lyricFontScale = 1f, lyricFontWeight = 400,
                                lyricAnimationMode = if (wordProgress) LyricAnimationMode.ALWAYS else LyricAnimationMode.NEVER,
                                lyricBlurEnabled = true,
                                centerLyrics = false, lyricCenterOffsetY = 0.dp,
                                showLyricsTranslation = wordProgress, showBottomFade = false,
                                resumeFollowRequestKey = resumeKey.value, seekRequestKey = 0,
                                seekPositionMs = 0L,
                                artworkContent = { overscrollModifier ->
                                    if (checkOverscroll) {
                                        Column(Modifier.fillMaxSize()) {
                                            Box(Modifier.fillMaxWidth().weight(1f)
                                                .then(overscrollModifier)
                                                .onGloballyPositioned { artworkCoordinates = it })
                                            Box(Modifier.fillMaxWidth().height(120.dp)
                                                .onGloballyPositioned { controlCoordinates = it })
                                        }
                                    } else {
                                        Box(Modifier.fillMaxSize().then(overscrollModifier)
                                            .background(Color.Black))
                                    }
                                },
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
                val windowDeadline = SystemClock.uptimeMillis() + 5_000L
                while (appRoot() == null && SystemClock.uptimeMillis() < windowDeadline) {
                    SystemClock.sleep(50)
                    frames(1)
                }
                requireNotNull(appRoot()) { "Test window was not available before pager entry" }
                if (checkOverscroll) {
                    fun visualLeft(coordinates: LayoutCoordinates?): Float {
                        var left = 0f
                        instrumentation.runOnMainSync {
                            left = requireNotNull(coordinates).localToWindow(Offset.Zero).x
                        }
                        return left
                    }
                    fun checkArtworkEdge() {
                        val artworkLeft = visualLeft(artworkCoordinates)
                        val controlLeft = visualLeft(controlCoordinates)
                        swipe(bounds, 0.5f, 0.85f, 0f) {
                            assertTrue("Artwork did not rebound", visualLeft(artworkCoordinates) > artworkLeft + 8f)
                            assertTrue("Controls joined edge rebound", abs(visualLeft(controlCoordinates) - controlLeft) <= 1f)
                        }
                        frames(90)
                        assertTrue("Artwork rebound did not settle", abs(visualLeft(artworkCoordinates) - artworkLeft) <= 1f)
                    }
                    checkArtworkEdge()
                    val controlLeft = visualLeft(controlCoordinates)
                    swipe(bounds, 0.8f, 0.2f, 0f) {
                        assertTrue("Controls did not move with normal paging", visualLeft(controlCoordinates) < controlLeft - 8f)
                    }
                    frames(90)
                    assertEquals(1, pager.settledPage)
                    val lyricRight = rowBounds(0).right
                    swipe(bounds, 0.5f, 0.15f, 0f) {
                        assertTrue("Lyrics did not receive edge rebound", rowBounds(0).right < lyricRight - 8)
                    }
                    frames(90)
                    swipe(bounds, 0.2f, 0.8f, 0f)
                    frames(90)
                    assertEquals(0, pager.settledPage)
                    checkArtworkEdge()
                    return@use
                }
                selectPage(pager, 1)
                assertCentered(0, bounds.centerY())
                if (checkEntry) {
                    // Hidden playback must be positioned before any visible catch-up spring.
                    selectPage(pager, 0)
                    scenario.onActivity {
                        position.value = 480_000L
                        updateTime.value = SystemClock.elapsedRealtime()
                    }
                    frames(30)
                    selectPage(pager, 1)
                    assertCentered(8, bounds.centerY())
                    frames(6)
                    assertCentered(8, bounds.centerY())

                    // A paused system-media seek has no frame loop to repair stale time.
                    selectPage(pager, 0)
                    scenario.onActivity {
                        playing.value = false
                        position.value = 60_000L
                        updateTime.value = SystemClock.elapsedRealtime()
                        discontinuityRevision.value++
                    }
                    frames(30)
                    selectPage(pager, 1)
                    assertCentered(1, bounds.centerY())

                    // Resident host collapse must stop the clock even with Lyrics selected.
                    scenario.onActivity { playerActive.value = false }
                    frames(2)
                    scenario.onActivity {
                        position.value = 120_000L
                        updateTime.value = SystemClock.elapsedRealtime()
                    }
                    frames(30)
                    scenario.onActivity { playerActive.value = true }
                    frames(4)
                    assertCentered(2, bounds.centerY())

                    // Reopen artwork after an external seek, then enter Lyrics immediately.
                    selectPage(pager, 0)
                    scenario.onActivity {
                        playerActive.value = false
                        position.value = 360_000L
                        updateTime.value = SystemClock.elapsedRealtime()
                        discontinuityRevision.value++
                    }
                    frames(30)
                    scenario.onActivity { playerActive.value = true }
                    frames(4)
                    selectPage(pager, 1)
                    assertCentered(6, bounds.centerY())
                    frames(6)
                    assertCentered(6, bounds.centerY())
                    return@use
                }
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

    private fun selectPage(pager: PagerState, page: Int) {
        lateinit var request: Job
        instrumentation.runOnMainSync {
            request = CoroutineScope(Dispatchers.Main + clock).launch { pager.scrollToPage(page) }
        }
        frames(4)
        assertTrue("Pager entry did not complete", request.isCompleted)
        assertEquals(page, pager.settledPage)
    }

    private fun swipe(
        bounds: Rect,
        startX: Float,
        endX: Float,
        deltaY: Float,
        onHeld: () -> Unit = {},
    ) {
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
        try { onHeld() } finally { pointer(MotionEvent.ACTION_UP, 1f) }
    }

    private fun rowBounds(index: Int): Rect {
        val root = requireNotNull(appRoot()) { "Missing test window" }
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == texts[index]) return node
            for (child in 0 until node.childCount) {
                node.getChild(child)?.let { find(it)?.let { found -> return found } }
            }
            return null
        }
        var row = requireNotNull(find(root)) { "Missing lyric $index" }
        while (!row.isClickable) row = row.parent ?: break
        return Rect().also(row::getBoundsInScreen)
    }

    private fun appRoot(): AccessibilityNodeInfo? {
        val automation = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        val windows = if (Build.VERSION.SDK_INT >= 30) {
            automation.windowsOnAllDisplays.let { displays ->
                (0 until displays.size()).flatMap { displays.valueAt(it) }
            }
        } else {
            automation.windows
        }
        for (window in windows) {
            val root = window.root
            if (root?.packageName?.toString() == "com.melox.player") return root
        }
        return null
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
