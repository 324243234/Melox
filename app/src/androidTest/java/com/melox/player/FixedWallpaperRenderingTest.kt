package com.melox.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.melox.player.model.AppSettings
import com.melox.player.model.LocalPlaylist
import com.melox.player.ui.component.FixedPageBackgroundHost
import com.melox.player.ui.component.LocalCustomPageBackground
import com.melox.player.ui.component.LocalPageCardSurfaceAlpha
import com.melox.player.ui.component.LocalPageSurfaceBackdrop
import com.melox.player.ui.component.LocalTopBarBlurSettings
import com.melox.player.ui.component.PageCard
import com.melox.player.ui.component.PageScaffold
import com.melox.player.ui.component.TopBarBlurSettings
import com.melox.player.ui.component.playlist.PlaylistGridItem
import com.melox.player.ui.theme.MeloxTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FixedWallpaperRenderingTest {
    @Test
    fun sharpBarsSampleTheFixedWallpaperWithBlurDisabled() {
        checkMovingSample(blurEnabled = false, sampleCard = false)
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun cardMaterialResamplesInsideAMovingParentGraphicsLayer() {
        checkMovingSample(blurEnabled = true, sampleCard = true)
    }

    @Test
    fun playlistCardOpacityReachesPaddingAndLabelAreaWithBlurDisabled() {
        val opacity = mutableFloatStateOf(0.8f)
        var bounds = ComposeRect.Zero
        var density = 1f
        val source = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            eraseColor(AndroidColor.RED)
        }
        val image = source.asImageBitmap()
        lateinit var view: ComposeView
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    density = activity.resources.displayMetrics.density
                    view = ComposeView(activity).apply {
                        setContent {
                            MeloxTheme(AppSettings(blurEnabled = false)) {
                                CompositionLocalProvider(
                                    LocalCustomPageBackground provides image,
                                    LocalPageSurfaceBackdrop provides null,
                                    LocalPageCardSurfaceAlpha provides opacity.floatValue,
                                ) {
                                    Box(
                                        Modifier.fillMaxSize().background(Color.Red),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        PlaylistGridItem(
                                            playlist = LocalPlaylist("test", "Playlist", 0L, 0L),
                                            onClick = {},
                                            modifier = Modifier.width(220.dp)
                                                .onGloballyPositioned { bounds = it.boundsInRoot() },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    activity.setContentView(view)
                }
                val before = copyFrame(scenario, view)
                try {
                    assertTrue("Playlist Card was not laid out", bounds.width > 0f && bounds.height > 0f)
                    val samples = listOf(
                        (bounds.left + bounds.width * 0.5f).toInt() to (bounds.bottom - 4f * density).toInt(),
                        (bounds.left + bounds.width * 0.9f).toInt() to (bounds.bottom - 24f * density).toInt(),
                    )
                    scenario.onActivity { opacity.floatValue = 0f }
                    val after = copyFrame(scenario, view)
                    try {
                        for ((x, y) in samples) {
                            assertTrue("Initial Card fill must be visible", before.getPixel(x, y) != AndroidColor.RED)
                            assertEquals("An inner playlist fill masks Card opacity", AndroidColor.RED, after.getPixel(x, y))
                        }
                    } finally {
                        after.recycle()
                    }
                } finally {
                    before.recycle()
                }
            }
        } finally {
            source.recycle()
        }
    }

    private fun checkMovingSample(blurEnabled: Boolean, sampleCard: Boolean) {
        val movement = mutableFloatStateOf(0f)
        val cardCenter = mutableStateOf(0f)
        val source = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(AndroidColor.RED)
            drawRect(200f, 0f, 400f, 400f, Paint().apply { color = AndroidColor.BLUE })
        }
        lateinit var view: ComposeView
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    view = ComposeView(activity).apply {
                        setContent {
                            MeloxTheme(AppSettings()) {
                                CompositionLocalProvider(
                                    LocalCustomPageBackground provides source.asImageBitmap(),
                                    LocalTopBarBlurSettings provides TopBarBlurSettings(blurEnabled, false),
                                ) {
                                    FixedPageBackgroundHost(
                                        modifier = Modifier.fillMaxSize(),
                                        refreshSignal = { movement.floatValue },
                                    ) {
                                        // A cached moving ancestor reproduces the pager's layer handoff.
                                        Box(Modifier.fillMaxSize().graphicsLayer {
                                            translationX = movement.floatValue * size.width
                                        }) {
                                            PageScaffold(
                                                topBar = { Box(Modifier.fillMaxWidth().height(80.dp)) },
                                            ) {
                                                Box(
                                                    Modifier.fillMaxSize()
                                                        .background(Color.Green)
                                                        .onSizeChanged { cardCenter.value = it.height / 2f },
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    if (sampleCard) {
                                                        PageCard(Modifier.fillMaxWidth().height(80.dp)) {
                                                            Box(Modifier.fillMaxWidth().height(80.dp))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    activity.setContentView(view)
                }
                val before = copyFrame(scenario, view)
                try {
                    val x = before.width * 35 / 100
                    val y = if (sampleCard) cardCenter.value.toInt() else 10
                    val expected = before.getPixel(x, y)
                    val opposite = before.getPixel(before.width * 65 / 100, y)
                    assertTrue("Source regions should be distinguishable", AndroidColor.red(expected) > AndroidColor.red(opposite) + 20)
                    scenario.onActivity { movement.floatValue = -0.25f }
                    val after = copyFrame(scenario, view)
                    try {
                        val actual = after.getPixel(x, y)
                        for (channel in listOf(AndroidColor::red, AndroidColor::green, AndroidColor::blue)) {
                            assertEquals("Sample moved with its parent instead of using screen coordinates", channel(expected).toFloat(), channel(actual).toFloat(), 5f)
                        }
                    } finally {
                        after.recycle()
                    }
                } finally {
                    before.recycle()
                }
            }
        } finally {
            source.recycle()
        }
    }

    private fun copyFrame(scenario: ActivityScenario<MainActivity>, view: ComposeView): Bitmap {
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
                        Rect(location[0], location[1], location[0] + view.width, location[1] + view.height),
                        bitmap,
                        { result = it; ready.countDown() },
                        Handler(Looper.getMainLooper()),
                    )
                }
            }
        }
        assertTrue("Frame was not copied", ready.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        return bitmap
    }
}
