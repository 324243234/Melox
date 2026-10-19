package com.melox.player

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.melox.player.ui.component.playback.PlayerControlIconButton
import com.melox.player.ui.component.playback.rememberPlayerControlPressFeedback
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(AndroidJUnit4::class)
class PlayerControlPressInputTest {
    @Test
    fun disabledControlIgnoresPressScaleTapAndLongPress() =
        withButton(longClickEnabled = true, enabled = false) { button ->
            val down = button.down()
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100)
            assertEquals(1f, button.scale.get().value, 0f)
            assertEquals(0, button.clicks.get())
            assertEquals(0, button.longClicks.get())
            button.up(down)
            assertEquals(0, button.clicks.get())
            assertEquals(0, button.longClicks.get())
        }

    @Test
    fun shortAndLongHeldReleaseUseNativeClickActivation() = withButton { button ->
        val down = button.down()
        assertEquals(0, button.clicks.get())
        SystemClock.sleep(70)
        button.up(down)
        assertEquals(1, button.clicks.get())

        val held = button.down()
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100)
        assertEquals(1, button.clicks.get())
        button.up(held)
        assertEquals(2, button.clicks.get())

        val next = button.down()
        SystemClock.sleep(70)
        button.up(next)
        assertEquals(3, button.clicks.get())
    }

    @Test
    fun dragOutAndBackDoesNotActivateOnRelease() = withButton { button ->
        val down = button.down()
        val bounds = button.bounds.get()
        button.event(MotionEvent.ACTION_MOVE, down, bounds.right + bounds.width, bounds.center.y)
        button.event(MotionEvent.ACTION_MOVE, down, bounds.center.x, bounds.center.y)
        button.up(down)
        assertEquals(0, button.clicks.get())
    }

    @Test
    fun suppressesParentIndicationAndKeepsTheOriginalTarget() = withButton { button ->
        val original = button.bounds.get()
        assertNotSame(button.parentIndication.get(), button.buttonIndication.get())
        val down = button.down()
        SystemClock.sleep(160)
        assertTrue(button.scale.get().value in 0.94f..0.99f)
        assertEquals(original, button.bounds.get())
        assertEquals(0, button.clicks.get())
        button.up(down)
        assertEquals(1, button.clicks.get())
    }

    @Test
    fun longPressDoesNotAlsoActivateThePlaybackClick() = withButton(longClickEnabled = true) { button ->
        val down = button.down()
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100)
        assertEquals(1, button.longClicks.get())
        assertEquals(0, button.clicks.get())
        button.up(down)
        assertEquals(0, button.clicks.get())

        val tap = button.down()
        SystemClock.sleep(70)
        button.up(tap)
        assertEquals(1, button.clicks.get())
        assertEquals(1, button.longClicks.get())

        val heldAgain = button.down()
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100)
        button.up(heldAgain)
        assertEquals(2, button.longClicks.get())
        assertEquals(1, button.clicks.get())
    }

    private class ButtonFixture {
        lateinit var view: ComposeView
        val bounds = AtomicReference(Rect.Zero)
        val clicks = AtomicInteger()
        val longClicks = AtomicInteger()
        val scale = AtomicReference<State<Float>>()
        val parentIndication = AtomicReference<Indication>()
        val buttonIndication = AtomicReference<Indication>()

        fun down(): Long {
            val time = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, time, bounds.get().center.x, bounds.get().center.y)
            return time
        }

        fun up(downTime: Long) {
            event(MotionEvent.ACTION_UP, downTime, bounds.get().center.x, bounds.get().center.y)
        }

        fun event(action: Int, downTime: Long, x: Float, y: Float) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).also {
                    it.source = InputDevice.SOURCE_TOUCHSCREEN
                    view.dispatchTouchEvent(it)
                    it.recycle()
                }
            }
        }
    }

    private fun withButton(
        longClickEnabled: Boolean = false,
        enabled: Boolean = true,
        check: (ButtonFixture) -> Unit,
    ) {
        val ready = CountDownLatch(1)
        val fixture = ButtonFixture()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                fixture.view = ComposeView(activity).apply {
                    setContent {
                        MiuixTheme {
                            fixture.parentIndication.set(LocalIndication.current)
                            val press = rememberPlayerControlPressFeedback(enabled)
                            SideEffect { fixture.scale.set(press.scale) }
                            Box(Modifier.fillMaxSize().padding(16.dp)) {
                                PlayerControlIconButton(
                                    enabled = enabled,
                                    onClick = { fixture.clicks.incrementAndGet() },
                                    onLongClick = if (longClickEnabled) {
                                        { fixture.longClicks.incrementAndGet() }
                                    } else {
                                        null
                                    },
                                    onLongClickLabel = "Toggle immersive mode",
                                    minWidth = 72.dp,
                                    minHeight = 72.dp,
                                    modifier = Modifier
                                        .then(press.modifier)
                                        .onGloballyPositioned { fixture.bounds.set(it.boundsInRoot()) }
                                        .drawWithContent { drawContent(); ready.countDown() },
                                ) {
                                    fixture.buttonIndication.set(LocalIndication.current)
                                    Icon(
                                        painter = painterResource(R.drawable.ic_player_play),
                                        contentDescription = null,
                                        modifier = Modifier.size(42.dp).graphicsLayer {
                                            scaleX = press.scale.value
                                            scaleY = press.scale.value
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                activity.setContentView(fixture.view)
            }
            assertTrue("Button did not draw", ready.await(5, TimeUnit.SECONDS))
            check(fixture)
        }
    }
}
