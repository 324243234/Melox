package com.melox.player

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.melox.player.ui.component.library.PlaybackArtworkFrame
import com.melox.player.ui.component.playback.ArtworkFlipLayer
import com.melox.player.ui.component.playback.ArtworkFlipState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(AndroidJUnit4::class)
class ArtworkFlipRenderingTest {
    @Test
    fun fullArtworkNextHasPerspectiveClippedBlurAndContinuousShadow() = verifyFlip(200.dp, 1)

    @Test
    fun fullArtworkPreviousUsesTheOppositeDirection() = verifyFlip(200.dp, -1)

    @Test
    fun miniArtworkNextMatchesTheFullArtworkDirection() = verifyFlip(48.dp, 1)

    @Test
    fun miniArtworkPreviousMatchesTheFullArtworkDirection() = verifyFlip(48.dp, -1)

    private fun verifyFlip(artworkSize: Dp, direction: Int) {
        val image = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
        }
        val replacement = image.copy(Bitmap.Config.ARGB_8888, false)
        val state = ArtworkFlipState(image, "front")
        val rotating = mutableStateOf(false)
        val circular = mutableStateOf(false)
        val scope = CoroutineScope(Dispatchers.Main + Job() + BroadcastFrameClock())
        lateinit var view: ComposeView
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = ComposeView(activity).apply {
                    setContent {
                        MiuixTheme {
                            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.LightGray),
                                contentAlignment = Alignment.Center) {
                                if (rotating.value) {
                                    ArtworkFlipLayer(state) { face, blur ->
                                        PlaybackArtworkFrame(face, artworkSize, artworkSize * 0.12f,
                                            useSquircleClip = true, drawArtworkShadow = true,
                                            circular = circular.value, artworkEffect = blur)
                                    }
                                } else PlaybackArtworkFrame(image, artworkSize, artworkSize * 0.12f,
                                    useSquircleClip = true, drawArtworkShadow = true,
                                    circular = circular.value)
                            }
                        }
                    }
                }
                activity.setContentView(view)
            }
            val name = "${artworkSize.value.toInt()}-$direction"
            val rest = capture(scenario, view, "$name-rest")
            scenario.onActivity {
                scope.launch { state.transitionTo(replacement, "back", direction, 400) }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                scope.launch { state.progress.snapTo(0f) }
                rotating.value = true
            }
            val start = capture(scenario, view, "$name-start")
            val restBounds = faceBounds(rest)
            val density = view.resources.displayMetrics.density
            val shadowY = restBounds.bottom + (6 * density).toInt()
            fun shadowStrength(bitmap: Bitmap): Int =
                (restBounds.left..restBounds.right).sumOf { x ->
                    (211 - Color.red(bitmap.getPixel(x, shadowY))).coerceAtLeast(0)
                }
            val restingShadow = shadowStrength(rest)
            assertTrue("Resting shadow missing", restingShadow > 100)
            assertTrue("Shadow disappeared at flip start",
                shadowStrength(start) > restingShadow * 0.9)

            for (progress in listOf(0.25f, 0.75f)) {
                scenario.onActivity { scope.launch { state.progress.snapTo(progress) } }
                val frame = capture(scenario, view, "$name-face-$progress")
                val bounds = faceBounds(frame)
                fun columnHeight(x: Int): Int = (bounds.top..bounds.bottom).count { y ->
                    isFace(frame.getPixel(x, y))
                }
                val leftHeight = columnHeight(bounds.left + bounds.width() / 4)
                val rightHeight = columnHeight(bounds.left + bounds.width() * 3 / 4)
                assertTrue("Native face is still orthographic: $leftHeight / $rightHeight",
                    kotlin.math.abs(leftHeight - rightHeight) > restBounds.height() * 0.09)
                assertTrue("Flip reversed perspective incorrectly",
                    if ((progress < 0.5f) == (direction > 0)) leftHeight > rightHeight
                    else rightHeight > leftHeight)
                assertTrue("Far edge remains too tall relative to the near edge",
                    minOf(leftHeight, rightHeight).toFloat() / maxOf(leftHeight, rightHeight) < 0.9f)
                // Rounded silhouette must stay empty near the projected bounding corners
                // even at strong blur; an image-only effect must not fill these pixels.
                assertTrue("Blur escaped the rounded silhouette",
                    !isTinted(frame.getPixel(bounds.left + 2, bounds.top + 2)) &&
                        !isTinted(frame.getPixel(bounds.right - 2, bounds.bottom - 2)))
                frame.recycle()
            }
            scenario.onActivity { scope.launch { state.progress.snapTo(0.9999f) } }
            val end = capture(scenario, view, "$name-end")
            assertTrue("Shadow disappeared before settling", shadowStrength(end) > restingShadow * 0.9)
            scenario.onActivity {
                circular.value = true
                scope.launch { state.progress.snapTo(0.25f) }
            }
            val circle = capture(scenario, view, "$name-circle")
            val circleBounds = faceBounds(circle)
            assertTrue("Blur escaped the circular silhouette",
                !isTinted(circle.getPixel(circleBounds.left + 5, circleBounds.top + 5)))
            listOf(rest, start, end, circle).forEach(Bitmap::recycle)
        }
        scope.cancel()
        image.recycle()
        replacement.recycle()
    }

    private fun isFace(pixel: Int): Boolean = Color.red(pixel) > 220 &&
        Color.blue(pixel) > 220 && Color.green(pixel) < 100

    private fun isTinted(pixel: Int): Boolean = Color.red(pixel) - Color.green(pixel) > 4

    private fun faceBounds(bitmap: Bitmap): Rect {
        var left = bitmap.width
        var top = bitmap.height
        var right = 0
        var bottom = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (isFace(bitmap.getPixel(x, y))) {
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        assertTrue("Artwork was not rendered", right > left && bottom > top)
        return Rect(left, top, right, bottom)
    }

    private fun capture(scenario: ActivityScenario<MainActivity>, view: ComposeView, name: String): Bitmap {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(150)
        val done = CountDownLatch(1)
        lateinit var bitmap: Bitmap
        var result = -1
        scenario.onActivity { activity ->
            val location = IntArray(2)
            view.getLocationInWindow(location)
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(activity.window,
                Rect(location[0], location[1], location[0] + view.width, location[1] + view.height),
                bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue("Frame copy failed", done.await(10, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "flip-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }
}
