package com.melox.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.MotionDurationScale
import com.melox.player.ui.component.playback.ArtworkCrossfadeState
import com.melox.player.ui.component.playback.ArtworkFlipState
import com.melox.player.ui.component.playback.WeightedCrossfadeFrame
import com.melox.player.ui.component.playback.rememberArtworkCrossfadeState
import com.melox.player.ui.component.playback.rememberArtworkFlipState
import com.melox.player.ui.component.playback.rememberReadyArtworkTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val sameTestArtwork: (String, String) -> Boolean = { first, second -> first == second }

class ArtworkTransitionStateTest {
    private data class Input(
        val image: String?,
        val song: String?,
        val ready: Boolean = true,
        val direction: Int = 1,
    )

    @Composable
    private fun rememberFade(input: Input): ArtworkCrossfadeState<String> =
        rememberArtworkCrossfadeState(
            target = rememberReadyArtworkTarget(input.image, input.song, input.ready),
            animate = true,
            durationMillis = 400,
            sameContentDurationMillis = 100,
            easing = LinearEasing,
            sameValue = sameTestArtwork,
        )

    @Composable
    private fun rememberFlip(input: Input): ArtworkFlipState<String> =
        rememberArtworkFlipState(
            target = rememberReadyArtworkTarget(input.image, input.song, input.ready),
            requestedContentKey = input.song,
            targetReady = input.ready,
            animate = true,
            direction = input.direction,
            durationMillis = 400,
        )

    @Test
    fun waitingForTheNextImageKeepsItsPredecessorsOwnerAndDoesNotPauseTheRunningFade() = runBlocking {
        val input = mutableStateOf(Input("a", "a"))
        lateinit var state: ArtworkCrossfadeState<String>
        withArtworkComposition(content = {
            state = rememberFade(input.value)
        }) {
            frame(0)
            input.value = Input("b", "b")
            frame(16)
            frame(116)
            val progress = state.progress.value
            input.value = Input("placeholder", "c", ready = false)
            frame(132)
            assertEquals("b", state.contentKey)
            assertTrue(state.progress.value > progress)
            assertFalse(state.frames.any { it.value == "placeholder" })
            frame(500)
            assertEquals(listOf(WeightedCrossfadeFrame("b", 1f)), state.frames)

            input.value = Input("c", "c")
            frame(516)
            frame(616)
            assertEquals("c", state.contentKey)
            assertEquals(0.25f, state.progress.value, 0.02f)
            frame(916)
            assertEquals(listOf(WeightedCrossfadeFrame("c", 1f)), state.frames)
        }
    }

    @Test
    fun consecutiveReadyTargetsKeepTheVisibleMixtureAndOnlyAnimateTowardTheNewest() = runBlocking {
        val input = mutableStateOf(Input("a", "a"))
        lateinit var state: ArtworkCrossfadeState<String>
        withArtworkComposition(content = { state = rememberFade(input.value) }) {
            frame(0)
            input.value = Input("b", "b")
            frame(16)
            frame(116)
            val visible = state.frames
            input.value = Input("c", "c")
            frame(116)
            assertEquals(visible, state.frames)
            input.value = Input("d", "d", direction = -1)
            frame(116)
            assertEquals(visible, state.frames)
            assertEquals("d", state.currentValue)
            frame(516)
            assertEquals(listOf(WeightedCrossfadeFrame("d", 1f)), state.frames)
        }
    }

    @Test
    fun onlySameSongResolutionChangesUseTheShortFade() = runBlocking {
        val input = mutableStateOf(Input("low-a", "a"))
        lateinit var state: ArtworkCrossfadeState<String>
        withArtworkComposition(content = { state = rememberFade(input.value) }) {
            frame(0)
            input.value = Input("high-a", "a")
            frame(16)
            frame(116)
            assertEquals(listOf(WeightedCrossfadeFrame("high-a", 1f)), state.frames)
            input.value = Input("b", "b")
            frame(132)
            frame(232)
            assertEquals(0.25f, state.progress.value, 0.02f)
        }
    }

    @Test
    fun identicalConfirmedArtworkStillUpdatesItsOwnerBeforeAResolutionUpgrade() = runBlocking {
        val input = mutableStateOf(Input("shared-cover", "a"))
        lateinit var state: ArtworkCrossfadeState<String>
        withArtworkComposition(content = { state = rememberFade(input.value) }) {
            frame(0)
            input.value = Input("shared-cover", "b")
            frame(16)
            assertEquals("b", state.contentKey)
            assertEquals(1f, state.progress.value, 0f)
            input.value = Input("high-b", "b")
            frame(32)
            frame(132)
            assertEquals(listOf(WeightedCrossfadeFrame("high-b", 1f)), state.frames)
        }
    }

    @Test
    fun aFallbackIsPublishedOnlyAfterMissingArtworkAndTheFallbackAreReady() = runBlocking {
        val input = mutableStateOf(Input("a", "a"))
        lateinit var state: ArtworkCrossfadeState<String>
        withArtworkComposition(content = { state = rememberFade(input.value) }) {
            frame(0)
            input.value = Input("fallback", "b", ready = false)
            frame(16)
            assertEquals("a", state.contentKey)
            assertEquals(listOf(WeightedCrossfadeFrame("a", 1f)), state.frames)
            input.value = input.value.copy(ready = true)
            frame(32)
            frame(432)
            assertEquals("b", state.contentKey)
            assertEquals(listOf(WeightedCrossfadeFrame("fallback", 1f)), state.frames)
        }
    }

    @Test
    fun aPendingFlipBeforeTheMidpointSettlesOnTheFrontWithoutShowingTheObsoleteBack() = runBlocking {
        val input = mutableStateOf(Input("a", "a"))
        lateinit var state: ArtworkFlipState<String>
        val observed = mutableListOf<String?>()
        withArtworkComposition(content = {
            state = rememberFlip(input.value)
            val visible = state.visibleValue
            SideEffect { observed += visible }
        }) {
            frame(0)
            input.value = Input("b", "b")
            frame(16)
            frame(96)
            assertFalse(state.frame.showBack)
            input.value = Input("placeholder", "c", ready = false)
            frame(96)
            frame(196)
            assertFalse(state.isRunning)
            assertEquals("a", state.visibleValue)
            assertFalse(observed.contains("b"))
            assertFalse(observed.contains("placeholder"))
            input.value = Input("c", "c")
            frame(212)
            frame(612)
            assertEquals("c", state.visibleValue)
        }
    }

    @Test
    fun retainingAnImageDuringLoadingDoesNotGiveItTheNextSongsIdentity() = runBlocking {
        val input = mutableStateOf(Input("a", "a"))
        lateinit var state: ArtworkFlipState<String>
        withArtworkComposition(content = { state = rememberFlip(input.value) }) {
            frame(0)
            input.value = Input("a", "b", ready = false)
            frame(16)
            assertEquals("a", state.visibleValue)
            assertFalse(state.isRunning)
            input.value = Input("b", "b")
            frame(32)
            frame(112)
            assertTrue(state.isRunning)
            assertEquals("a", state.visibleValue)
            frame(432)
            assertEquals("b", state.visibleValue)
        }
    }

    @Test
    fun aPendingLatestSongDiscardsTheQueuedTurnAfterTheVisibleBackSettles() = runBlocking {
        val input = mutableStateOf(Input("a", "a"))
        lateinit var state: ArtworkFlipState<String>
        val observed = mutableListOf<String?>()
        withArtworkComposition(content = {
            state = rememberFlip(input.value)
            val visible = state.visibleValue
            SideEffect { observed += visible }
        }) {
            frame(0)
            input.value = Input("b", "b")
            frame(16)
            frame(256)
            assertEquals("b", state.visibleValue)
            input.value = Input("c", "c")
            frame(256)
            input.value = Input("placeholder", "d", ready = false, direction = -1)
            frame(256)
            frame(416)
            assertFalse(state.isRunning)
            assertEquals("b", state.visibleValue)
            assertFalse(observed.contains("c"))
            input.value = Input("d", "d", direction = -1)
            frame(432)
            frame(832)
            assertEquals("d", state.visibleValue)
            assertFalse(observed.contains("c"))
            assertFalse(observed.contains("placeholder"))
        }
    }

    @Test
    fun aNewerReadyTargetReplacesTheQueuedFlipWithoutChangingTheVisibleFace() = runBlocking {
        val input = mutableStateOf(Input("a", "a"))
        lateinit var state: ArtworkFlipState<String>
        val observed = mutableListOf<String?>()
        withArtworkComposition(content = {
            state = rememberFlip(input.value)
            val visible = state.visibleValue
            SideEffect { observed += visible }
        }) {
            frame(0)
            input.value = Input("b", "b")
            frame(16)
            frame(256)
            input.value = Input("c", "c")
            frame(256)
            input.value = Input("d", "d")
            frame(272)
            assertEquals("b", state.visibleValue)
            frame(416)
            frame(900)
            assertEquals("d", state.visibleValue)
            assertFalse(observed.contains("c"))
        }
    }

    @Test
    fun aSameSongUpgradeKeepsTheCurrentFlipAngle() = runBlocking {
        val input = mutableStateOf(Input("a", "a"))
        lateinit var state: ArtworkFlipState<String>
        withArtworkComposition(content = { state = rememberFlip(input.value) }) {
            frame(0)
            input.value = Input("low-b", "b")
            frame(16)
            frame(256)
            val angle = state.frame.rotationY
            input.value = Input("high-b", "b")
            frame(256)
            assertEquals(angle, state.frame.rotationY, 0.001f)
            assertEquals("high-b", state.visibleValue)
            frame(416)
            assertFalse(state.isRunning)
            assertEquals("high-b", state.visibleValue)
        }
    }

    @Test
    fun zeroSystemMotionScaleSettlesBothModesOnTheReadyTarget() {
        val scale = object : MotionDurationScale { override val scaleFactor = 0f }
        runBlocking(scale) {
            val input = mutableStateOf(Input("a", "a"))
            lateinit var fade: ArtworkCrossfadeState<String>
            lateinit var flip: ArtworkFlipState<String>
            withArtworkComposition(content = {
                fade = rememberFade(input.value)
                flip = rememberFlip(input.value)
            }) {
                frame(0)
                input.value = Input("b", "b")
                frame(16)
                assertEquals(listOf(WeightedCrossfadeFrame("b", 1f)), fade.frames)
                assertEquals("b", flip.visibleValue)
                assertFalse(flip.isRunning)
                assertEquals(0f, flip.frame.blurFraction, 0f)
            }
        }
    }
}
