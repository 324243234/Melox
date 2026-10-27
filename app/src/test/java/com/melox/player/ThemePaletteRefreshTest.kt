package com.melox.player

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import com.melox.player.model.AppSettings
import com.melox.player.model.DynamicColorSource
import com.melox.player.model.ThemeMode
import com.melox.player.ui.theme.rememberPlaybackContentColor
import com.melox.player.ui.theme.ThemePaletteTarget
import com.melox.player.ui.theme.rememberAnimatedThemeColors
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.lightColorScheme

class ThemePaletteRefreshTest {
    @Test
    fun wallpaperFallbackRefreshesWithoutAnArtworkSeedChange() = runBlocking {
        withPaletteComposition { palette, target, rendered, frame ->
            frame(0)
            palette.value = lightColorScheme().copy(primary = Color.Green)
            frame(16)
            assertEquals(Color.Green, rendered())
            assertEquals(null, target.value.keyColor)

            palette.value = lightColorScheme().copy(primary = Color.Blue)
            frame(32)
            assertEquals(Color.Blue, rendered())
        }
    }

    @Test
    fun systemAppearanceChangeCancelsTheOldArtworkPalette() = runBlocking {
        withPaletteComposition { palette, target, rendered, frame ->
            frame(0)
            target.value = target.value.copy(keyColor = Color.Blue)
            palette.value = lightColorScheme().copy(primary = Color.Blue)
            frame(16)
            frame(116)
            assertNotEquals(Color.Blue, rendered())

            target.value = target.value.copy(isDark = true)
            palette.value = lightColorScheme().copy(primary = Color.Black)
            frame(132)
            assertEquals(Color.Black, rendered())
            frame(900)
            assertEquals(Color.Black, rendered())
        }
    }

    @Test
    fun artworkTransitionFinishesAndLaterPlatformColorsRemainLive() = runBlocking {
        withPaletteComposition { palette, target, rendered, frame ->
            frame(0)
            target.value = target.value.copy(keyColor = Color.Blue)
            palette.value = lightColorScheme().copy(primary = Color.Blue)
            frame(16)
            frame(116)
            assertNotEquals(Color.Blue, rendered())
            frame(800)
            assertEquals(Color.Blue, rendered())

            target.value = target.value.copy(keyColor = null)
            palette.value = lightColorScheme().copy(primary = Color.Green)
            frame(816)
            frame(1600)
            assertEquals(Color.Green, rendered())

            palette.value = lightColorScheme().copy(primary = Color.Yellow)
            frame(1616)
            assertEquals(Color.Yellow, rendered())
        }
    }

    @Test
    fun lightPlayerUsesItsOwnDynamicPaletteAndRetainsForegroundFallbacks() = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(EmptyApplier(), recomposer)
        val settings = mutableStateOf(
            AppSettings(
                themeMode = ThemeMode.DARK,
                dynamicColorEnabled = true,
                dynamicColorSource = DynamicColorSource.PLAYBACK_ARTWORK,
            ),
        )
        val seed = mutableStateOf(Color.Red)
        val dark = mutableStateOf(false)
        val supported = mutableStateOf(true)
        val hasItem = mutableStateOf(true)
        var rendered = Color.Unspecified
        var targetLightAccent = Color.Unspecified
        suspend fun frame(millis: Long) {
            repeat(4) {
                Snapshot.sendApplyNotifications()
                yield()
                clock.sendFrame(millis * 1_000_000L)
                yield()
            }
        }
        try {
            composition.setContent {
                val color = rememberPlaybackContentColor(
                    settings = settings.value,
                    playbackArtworkColor = seed.value,
                    backgroundDark = dark.value,
                    hasItem = hasItem.value,
                    dynamicColorSupported = supported.value,
                )
                val lightAccent = ThemeController(
                    colorSchemeMode = ColorSchemeMode.MonetLight,
                    keyColor = seed.value,
                ).currentColors().primary
                SideEffect {
                    rendered = color
                    targetLightAccent = lightAccent
                }
            }
            frame(0)
            assertEquals(targetLightAccent, rendered)
            assertNotEquals(Color(0xFF282828), rendered)
            val originalAccent = rendered
            settings.value = settings.value.copy(themeMode = ThemeMode.LIGHT)
            frame(16)
            assertEquals(originalAccent, rendered)

            seed.value = Color.Blue
            frame(32)
            frame(132)
            assertNotEquals(originalAccent, rendered)
            assertNotEquals(targetLightAccent, rendered)
            frame(800)
            assertEquals(targetLightAccent, rendered)

            hasItem.value = false
            frame(801)
            assertEquals(Color(0xFF282828), rendered)
            settings.value = settings.value.copy(dynamicColorSource = DynamicColorSource.DESKTOP)
            frame(802)
            assertEquals(Color(0xFF282828), rendered)
            dark.value = true
            frame(803)
            assertEquals(Color.White, rendered)
            dark.value = false
            hasItem.value = true
            settings.value = settings.value.copy(dynamicColorSource = DynamicColorSource.PLAYBACK_ARTWORK)
            frame(804)
            assertEquals(targetLightAccent, rendered)

            settings.value = settings.value.copy(dynamicColorEnabled = false)
            frame(816)
            assertEquals(Color(0xFF282828), rendered)
            settings.value = settings.value.copy(dynamicColorEnabled = true)
            supported.value = false
            frame(832)
            assertEquals(Color(0xFF282828), rendered)
            supported.value = true
            dark.value = true
            frame(848)
            assertEquals(Color.White, rendered)
            settings.value = settings.value.copy(dynamicColorEnabled = false)
            frame(864)
            assertEquals(Color.White, rendered)
        } finally {
            composition.dispose()
            recomposer.close()
            runner.cancelAndJoin()
        }
    }

    private suspend fun CoroutineScope.withPaletteComposition(
        test: suspend (
            MutableState<Colors>,
            MutableState<ThemePaletteTarget>,
            () -> Color,
            suspend (Long) -> Unit,
        ) -> Unit,
    ) {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(EmptyApplier(), recomposer)
        val palette = mutableStateOf(lightColorScheme().copy(primary = Color.Red))
        val target = mutableStateOf(
            ThemePaletteTarget(ColorSchemeMode.MonetSystem, null, false, true),
        )
        var rendered = Color.Unspecified
        try {
            composition.setContent {
                val colors = rememberAnimatedThemeColors(palette.value, target.value)
                SideEffect { rendered = colors.primary }
            }
            test(palette, target, { rendered }) { millis ->
                repeat(4) {
                    Snapshot.sendApplyNotifications()
                    yield()
                    clock.sendFrame(millis * 1_000_000L)
                    yield()
                }
            }
        } finally {
            composition.dispose()
            recomposer.close()
            runner.cancelAndJoin()
        }
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
