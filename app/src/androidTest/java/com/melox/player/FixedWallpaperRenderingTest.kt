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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.melox.player.model.AppSettings
import com.melox.player.ui.component.FixedPageBackgroundHost
import com.melox.player.ui.component.LocalCustomPageBackground
import com.melox.player.ui.component.LocalTopBarBlurSettings
import com.melox.player.ui.component.PageCard
import com.melox.player.ui.component.PageScaffold
import com.melox.player.ui.component.TopBarBlurSettings
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
