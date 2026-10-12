package com.melox.player

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.melox.player.ui.theme.MeloxTheme
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test
import org.junit.runner.RunWith

/** Same resident pager, fixed artwork and source samples; only clock scope changes. */
@RunWith(AndroidJUnit4::class)
class LyricClockPerformanceTest {
    @Test
    fun compareHiddenSampleClockAndContinuousPlayerClock() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var clockActive by mutableStateOf(false)
        var position by mutableStateOf(0L)
        var anchor by mutableStateOf(SystemClock.elapsedRealtime())
        var discontinuityRevision by mutableStateOf(0L)
        val frameCount = AtomicLong()
        val frameTime = AtomicLong()
        lateinit var window: Window
        val document = LyricsDocument(
            List(200) { index ->
                LyricLine("", index * 3_000L, (index + 1) * 3_000L,
                    "Continuous lyrics motion sample $index 测试歌词进度和翻译",
                    emptyList(), "Translation sample $index")
            }, LyricsFormat.LRC, LyricsSource.SIDECAR,
        )
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frameCount.incrementAndGet()
            frameTime.addAndGet(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
        }
        fun mainCpuMs(): Double {
            var nanos = 0L
            instrumentation.runOnMainSync { nanos = Debug.threadCpuTimeNanos() }
            return nanos / 1_000_000.0
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                window = activity.window
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
                activity.setContentView(ComposeView(activity).apply {
                    setContent {
                        MeloxTheme(AppSettings()) {
                            PlayerContentPager(
                                pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 }),
                                lyrics = LyricsUiState.Available(document), positionMs = position,
                                positionUpdateElapsedRealtimeMs = anchor, playbackIteration = 0L,
                                playbackSpeed = 1f, previewPositionMs = null, isPlaying = true,
                                lyricsActive = false, playerActive = clockActive, onSeek = {},
                                positionDiscontinuityRevision = discontinuityRevision,
                                contentWidth = 320.dp, controlColor = Color.White,
                                emphasisControlColor = Color.White, lyricsPagingEnabled = true,
                                lyricFontScale = 1f, lyricFontWeight = 400,
                                lyricAnimationMode = LyricAnimationMode.ALWAYS, lyricBlurEnabled = true,
                                centerLyrics = false, lyricCenterOffsetY = 0.dp,
                                showLyricsTranslation = true, showBottomFade = false,
                                resumeFollowRequestKey = 0, seekRequestKey = 0, seekPositionMs = 0L,
                                artworkContent = { Box(Modifier.fillMaxSize().background(Color.Black)) },
                                modifier = Modifier.fillMaxSize().background(Color.Black),
                            )
                        }
                    }
                })
            }
            try {
                // Alternate order to reduce JIT and thermal bias; use equal source sample cadence.
                for ((round, modes) in listOf(
                    listOf("sample", "continuous"),
                    listOf("continuous", "sample"),
                    listOf("sample", "continuous"),
                ).withIndex()) {
                    for (mode in modes) {
                        instrumentation.runOnMainSync {
                            clockActive = mode == "continuous"
                            discontinuityRevision++
                            position = 30_000L
                            anchor = SystemClock.elapsedRealtime()
                        }
                        SystemClock.sleep(2_000)
                        val start = SystemClock.elapsedRealtime()
                        val cpuStart = Process.getElapsedCpuTime()
                        val mainStart = mainCpuMs()
                        val framesStart = frameCount.get()
                        val frameTimeStart = frameTime.get()
                        repeat(12) { sample ->
                            SystemClock.sleep(500)
                            instrumentation.runOnMainSync {
                                position = 32_000L + (sample + 1) * 500L
                                anchor = SystemClock.elapsedRealtime()
                            }
                        }
                        val elapsed = SystemClock.elapsedRealtime() - start
                        val cpu = Process.getElapsedCpuTime() - cpuStart
                        val main = mainCpuMs() - mainStart
                        val frames = frameCount.get() - framesStart
                        val totalFrameMs = (frameTime.get() - frameTimeStart) / 1_000_000.0
                        Log.i("LyricClockPerf", "round=$round mode=$mode elapsed_ms=$elapsed " +
                            "process_cpu_ms=$cpu main_cpu_ms=$main frames=$frames frame_total_ms=$totalFrameMs")
                    }
                }
            } finally {
                instrumentation.runOnMainSync {
                    clockActive = false
                    window.removeOnFrameMetricsAvailableListener(listener)
                }
            }
        }
    }
}
