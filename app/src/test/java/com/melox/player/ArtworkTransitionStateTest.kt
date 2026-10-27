package com.melox.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.MotionDurationScale
import com.melox.player.ui.component.playback.ArtworkCrossfadeState
import com.melox.player.ui.component.playback.ArtworkFlipState
import com.melox.player.ui.component.playback.ArtworkTransitionState
import com.melox.player.ui.component.playback.WeightedCrossfadeFrame
import com.melox.player.ui.component.playback.rememberArtworkCrossfadeState
import com.melox.player.ui.component.playback.rememberArtworkFlipState
import com.melox.player.ui.component.playback.rememberArtworkTransitionState
import com.melox.player.ui.component.playback.rememberReadyArtworkTarget
import com.melox.player.ui.component.playback.playbackArtworkTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val sameTestArtwork: (String, String) -> Boolean = { first, second -> first == second }

class ArtworkTransitionStateTest {
    private var lastFrameMs = 0L

    private suspend fun ArtworkTestComposition.advanceTo(millis: Long) {
        if (millis < lastFrameMs) lastFrameMs = millis
        while (lastFrameMs + 16 < millis) {
            lastFrameMs += 16
            frame(lastFrameMs)
        }
        lastFrameMs = millis
        frame(millis)
    }

    private data class Input(
        val image: String?,
        val song: String?,
        val ready: Boolean = true,
        val direction: Int = 1,
        val placeholder: Boolean = false,
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
            advanceTo(0)
            input.value = Input("b", "b")
            advanceTo(16)
            advanceTo(116)
            val progress = state.progress.value
            input.value = Input("placeholder", "c", ready = false)
            advanceTo(132)
            assertEquals("b", state.contentKey)
            assertTrue(state.progress.value > progress)
            assertFalse(state.frames.any { it.value == "placeholder" })
            advanceTo(500)
            assertEquals(listOf(WeightedCrossfadeFrame("b", 1f)), state.frames)

            input.value = Input("c", "c")
            advanceTo(516)
            advanceTo(616)
            assertEquals("c", state.contentKey)
            assertEquals(0.25f, state.progress.value, 0.02f)
            advanceTo(1316)
            assertEquals(listOf(WeightedCrossfadeFrame("c", 1f)), state.frames)
        }
    }

    @Test
    fun unresolvedEmptyPlayerRetainsPlaceholderOwnershipWithoutABitmap() = runBlocking {
        val input = mutableStateOf(Input(null, null, placeholder = true))
        lateinit var state: ArtworkTransitionState<String>
        withArtworkComposition(content = {
            state = rememberArtworkTransitionStateForTest(input.value)
        }) {
            advanceTo(0)
            assertTrue(state.isPlaceholderArtwork)
            assertEquals(null, state.currentValue)
            assertEquals(1f, state.progress, 0f)
        }
    }

    @Composable
    private fun rememberForegroundArtworkForTest(
        input: Input,
        flip: Boolean,
    ): ArtworkTransitionState<String> {
        val target = playbackArtworkTarget(
            contentUri = input.song,
            artwork = input.image.takeUnless { input.placeholder },
            placeholder = input.image.takeIf { input.placeholder },
            placeholderArtworkResId = 123,
        )
        return rememberArtworkTransitionState(
            target = rememberReadyArtworkTarget(target.value, target.contentKey, input.ready),
            requestedContentKey = target.contentKey,
            targetReady = input.ready,
            targetIsPlaceholder = input.placeholder,
            animate = true,
            flipAnimationEnabled = flip,
            direction = input.direction,
            durationMillis = 400,
            sameContentDurationMillis = 100,
            easing = LinearEasing,
            sameValue = sameTestArtwork,
        )
    }

    @Test
    fun firstSongTransitionsFromTheEmptyBarsVisiblePlaceholderInBothModes() = runBlocking {
        for (flip in listOf(false, true)) {
            val input = mutableStateOf(Input("empty-placeholder", null, placeholder = true))
            lateinit var state: ArtworkTransitionState<String>
            withArtworkComposition(content = { state = rememberForegroundArtworkForTest(input.value, flip) }) {
                advanceTo(0)
                assertEquals("empty-placeholder", state.currentValue)
                input.value = Input("cover", "song")
                advanceTo(16)
                advanceTo(116)
                assertTrue(state.isRunning)
                if (flip) {
                    assertEquals("empty-placeholder", state.currentValue)
                    assertTrue(state.isPlaceholderArtwork)
                } else {
                    assertEquals(setOf("empty-placeholder", "cover"), state.frames.map { it.value }.toSet())
                    assertTrue(state.frames.single { it.value == "empty-placeholder" }.isPlaceholder)
                    assertFalse(state.frames.single { it.value == "cover" }.isPlaceholder)
                    assertEquals(0.25f, state.progress, 0.02f)
                }
                advanceTo(1316)
                assertEquals(listOf(WeightedCrossfadeFrame("cover", 1f)), state.frames)
            }
        }
    }

    @Test
    fun firstSongLoadingKeepsTheEmptyPlaceholderAndClearingTransitionsBackToIt() = runBlocking {
        for (flip in listOf(false, true)) {
            val input = mutableStateOf(Input("empty-placeholder", null, placeholder = true))
            lateinit var state: ArtworkTransitionState<String>
            withArtworkComposition(content = { state = rememberForegroundArtworkForTest(input.value, flip) }) {
                advanceTo(0)
                input.value = Input(null, "song", ready = false)
                advanceTo(16)
                advanceTo(516)
                assertEquals(listOf(WeightedCrossfadeFrame("empty-placeholder", 1f, true)), state.frames)
                input.value = Input("cover", "song")
                advanceTo(532)
                advanceTo(1832)
                input.value = Input("empty-placeholder", null, placeholder = true)
                advanceTo(1848)
                advanceTo(1948)
                assertTrue(state.isRunning)
                advanceTo(3048)
                assertEquals(listOf(WeightedCrossfadeFrame("empty-placeholder", 1f, true)), state.frames)
            }
        }
    }

    @Test
    fun firstDecodedEmptyPlaceholderReplacesItsFallbackWithoutAnEntranceFade() = runBlocking {
        for (flip in listOf(false, true)) {
            val input = mutableStateOf(Input(null, null, ready = false, placeholder = true))
            lateinit var state: ArtworkTransitionState<String>
            withArtworkComposition(content = { state = rememberForegroundArtworkForTest(input.value, flip) }) {
                advanceTo(0)
                input.value = Input("empty-placeholder", null, placeholder = true)
                advanceTo(16)
                assertFalse(state.isRunning)
                assertEquals(listOf(WeightedCrossfadeFrame("empty-placeholder", 1f, true)), state.frames)
            }
        }
    }

    @Composable
    private fun rememberArtworkTransitionStateForTest(
        input: Input,
        flipAnimationEnabled: Boolean = false,
    ): ArtworkTransitionState<String> =
        rememberArtworkTransitionState(
            target = rememberReadyArtworkTarget(input.image, input.song, input.ready),
            requestedContentKey = input.song,
            targetReady = input.ready,
            targetIsPlaceholder = input.placeholder,
            animate = true,
            flipAnimationEnabled = flipAnimationEnabled,
            direction = input.direction,
            durationMillis = 400,
            sameContentDurationMillis = 100,
            easing = LinearEasing,
            sameValue = sameTestArtwork,
        )

    @Test
    fun interruptedFadesKeepPlaceholderIdentityOnEachImageWhileTheNextSongLoads() = runBlocking {
        val input = mutableStateOf(Input("real-a", "a"))
        lateinit var state: ArtworkTransitionState<String>
        withArtworkComposition(content = {
            state = rememberArtworkTransitionStateForTest(input.value)
        }) {
            advanceTo(0)
            input.value = Input("placeholder", "b", placeholder = true)
            advanceTo(16)
            advanceTo(116)
            assertEquals(false, state.frames.single { it.value == "real-a" }.isPlaceholder)
            assertEquals(true, state.frames.single { it.value == "placeholder" }.isPlaceholder)

            val mixture = state.frames
            input.value = Input("real-c", "c")
            advanceTo(116)
            assertEquals(mixture, state.frames)
            advanceTo(216)
            assertEquals(
                mapOf("real-a" to false, "placeholder" to true, "real-c" to false),
                state.frames.associate { it.value to it.isPlaceholder },
            )
            input.value = Input(null, "d", ready = false, placeholder = true)
            advanceTo(232)
            assertEquals(true, state.frames.single { it.value == "placeholder" }.isPlaceholder)
            assertFalse(state.frames.single { it.value == "real-c" }.isPlaceholder)
            advanceTo(700)
            assertEquals(listOf(WeightedCrossfadeFrame("real-c", 1f)), state.frames)
        }
    }

    @Test
    fun fadingFromPlaceholderToRealCoverKeepsTheOutgoingPlaceholderDimmed() = runBlocking {
        val input = mutableStateOf(Input("placeholder", "a", placeholder = true))
        lateinit var state: ArtworkTransitionState<String>
        withArtworkComposition(content = {
            state = rememberArtworkTransitionStateForTest(input.value)
        }) {
            advanceTo(0)
            input.value = Input("real-b", "b")
            advanceTo(16)
            advanceTo(116)
            assertTrue(state.frames.single { it.value == "placeholder" }.isPlaceholder)
            assertFalse(state.frames.single { it.value == "real-b" }.isPlaceholder)
            advanceTo(416)
            assertFalse(state.isPlaceholderArtwork)
        }
    }

    @Test
    fun flipPlaceholderIdentityFollowsTheVisibleFaceInBothDirections() = runBlocking {
        val input = mutableStateOf(Input("real-a", "a"))
        lateinit var state: ArtworkTransitionState<String>
        withArtworkComposition(content = {
            state = rememberArtworkTransitionStateForTest(input.value, flipAnimationEnabled = true)
        }) {
            advanceTo(0)
            input.value = Input("placeholder", "b", placeholder = true)
            advanceTo(16)
            advanceTo(116)
            assertEquals("real-a", state.currentValue)
            assertFalse(state.isPlaceholderArtwork)
            advanceTo(316)
            assertEquals("placeholder", state.currentValue)
            assertTrue(state.isPlaceholderArtwork)
            advanceTo(1216)
            assertEquals(listOf(WeightedCrossfadeFrame("placeholder", 1f, true)), state.frames)

            input.value = Input("real-c", "c")
            advanceTo(1232)
            advanceTo(1332)
            assertEquals("placeholder", state.currentValue)
            assertTrue(state.isPlaceholderArtwork)
            advanceTo(1532)
            assertEquals("real-c", state.currentValue)
            assertFalse(state.isPlaceholderArtwork)
        }
    }

    @Test
    fun pendingLoadBeforeFlipMidpointSettlesToTheOriginalFacesIdentity() = runBlocking {
        val input = mutableStateOf(Input("real-a", "a"))
        lateinit var state: ArtworkTransitionState<String>
        withArtworkComposition(content = {
            state = rememberArtworkTransitionStateForTest(input.value, flipAnimationEnabled = true)
        }) {
            advanceTo(0)
            input.value = Input("placeholder", "b", placeholder = true)
            advanceTo(16)
            advanceTo(116)
            input.value = Input(null, "c", ready = false)
            advanceTo(132)
            advanceTo(600)
            assertEquals("real-a", state.currentValue)
            assertFalse(state.isPlaceholderArtwork)
            assertEquals(listOf(WeightedCrossfadeFrame("real-a", 1f)), state.frames)
        }
    }

    @Test
    fun deferredModeChangesPreserveTheSettledPlaceholdersIdentity() = runBlocking {
        val input = mutableStateOf(Input("real-a", "a"))
        val flip = mutableStateOf(false)
        lateinit var state: ArtworkTransitionState<String>
        withArtworkComposition(content = {
            state = rememberArtworkTransitionStateForTest(input.value, flip.value)
        }) {
            advanceTo(0)
            input.value = Input("placeholder", "b", placeholder = true)
            advanceTo(16)
            advanceTo(116)
            flip.value = true
            advanceTo(132)
            assertFalse(state.flipAnimationEnabled)
            advanceTo(600)
            assertTrue(state.flipAnimationEnabled)
            assertTrue(state.isPlaceholderArtwork)
            assertEquals(listOf(WeightedCrossfadeFrame("placeholder", 1f, true)), state.frames)

            flip.value = false
            advanceTo(616)
            assertFalse(state.flipAnimationEnabled)
            assertTrue(state.isPlaceholderArtwork)
            assertEquals(listOf(WeightedCrossfadeFrame("placeholder", 1f, true)), state.frames)
        }
    }

    @Test
    fun consecutiveReadyTargetsKeepTheVisibleMixtureAndOnlyAnimateTowardTheNewest() = runBlocking {
        val input = mutableStateOf(Input("a", "a"))
        lateinit var state: ArtworkCrossfadeState<String>
        withArtworkComposition(content = { state = rememberFade(input.value) }) {
            advanceTo(0)
            input.value = Input("b", "b")
            advanceTo(16)
            advanceTo(116)
            val visible = state.frames
            input.value = Input("c", "c")
            advanceTo(116)
            assertEquals(visible, state.frames)
            input.value = Input("d", "d", direction = -1)
            advanceTo(116)
            assertEquals(visible, state.frames)
            assertEquals("d", state.currentValue)
            advanceTo(516)
            assertEquals(listOf(WeightedCrossfadeFrame("d", 1f)), state.frames)
        }
    }

    @Test
    fun onlySameSongResolutionChangesUseTheShortFade() = runBlocking {
        val input = mutableStateOf(Input("low-a", "a"))
        lateinit var state: ArtworkCrossfadeState<String>
        withArtworkComposition(content = { state = rememberFade(input.value) }) {
            advanceTo(0)
            input.value = Input("high-a", "a")
            advanceTo(16)
            advanceTo(116)
            assertEquals(listOf(WeightedCrossfadeFrame("high-a", 1f)), state.frames)
            input.value = Input("b", "b")
            advanceTo(132)
            advanceTo(232)
            assertEquals(0.25f, state.progress.value, 0.02f)
        }
    }

    @Test
    fun identicalConfirmedArtworkStillUpdatesItsOwnerBeforeAResolutionUpgrade() = runBlocking {
        val input = mutableStateOf(Input("shared-cover", "a"))
        lateinit var state: ArtworkCrossfadeState<String>
        withArtworkComposition(content = { state = rememberFade(input.value) }) {
            advanceTo(0)
            input.value = Input("shared-cover", "b")
            advanceTo(16)
            assertEquals("b", state.contentKey)
            assertEquals(1f, state.progress.value, 0f)
            input.value = Input("high-b", "b")
            advanceTo(32)
            advanceTo(132)
            assertEquals(listOf(WeightedCrossfadeFrame("high-b", 1f)), state.frames)
        }
    }

    @Test
    fun aFallbackIsPublishedOnlyAfterMissingArtworkAndTheFallbackAreReady() = runBlocking {
        val input = mutableStateOf(Input("a", "a"))
        lateinit var state: ArtworkCrossfadeState<String>
        withArtworkComposition(content = { state = rememberFade(input.value) }) {
            advanceTo(0)
            input.value = Input("fallback", "b", ready = false)
            advanceTo(16)
            assertEquals("a", state.contentKey)
            assertEquals(listOf(WeightedCrossfadeFrame("a", 1f)), state.frames)
            input.value = input.value.copy(ready = true)
            advanceTo(32)
            advanceTo(432)
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
            advanceTo(0)
            input.value = Input("b", "b")
            advanceTo(16)
            advanceTo(96)
            assertFalse(state.frame.showBack)
            input.value = Input("placeholder", "c", ready = false)
            advanceTo(96)
            advanceTo(196)
            assertFalse(state.isRunning)
            assertEquals("a", state.visibleValue)
            assertFalse(observed.contains("b"))
            assertFalse(observed.contains("placeholder"))
            input.value = Input("c", "c")
            advanceTo(212)
            advanceTo(612)
            assertEquals("c", state.visibleValue)
        }
    }

    @Test
    fun retainingAnImageDuringLoadingDoesNotGiveItTheNextSongsIdentity() = runBlocking {
        val input = mutableStateOf(Input("a", "a"))
        lateinit var state: ArtworkFlipState<String>
        withArtworkComposition(content = { state = rememberFlip(input.value) }) {
            advanceTo(0)
            input.value = Input("a", "b", ready = false)
            advanceTo(16)
            assertEquals("a", state.visibleValue)
            assertFalse(state.isRunning)
            input.value = Input("b", "b")
            advanceTo(32)
            advanceTo(112)
            assertTrue(state.isRunning)
            assertEquals("a", state.visibleValue)
            advanceTo(432)
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
            advanceTo(0)
            input.value = Input("b", "b")
            advanceTo(16)
            advanceTo(256)
            assertEquals("b", state.visibleValue)
            input.value = Input("c", "c")
            advanceTo(256)
            input.value = Input("placeholder", "d", ready = false, direction = -1)
            advanceTo(256)
            advanceTo(1216)
            assertFalse(state.isRunning)
            assertEquals("b", state.visibleValue)
            assertFalse(observed.contains("c"))
            input.value = Input("d", "d", direction = -1)
            advanceTo(1232)
            advanceTo(2032)
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
            advanceTo(0)
            input.value = Input("b", "b")
            advanceTo(16)
            advanceTo(256)
            input.value = Input("c", "c")
            advanceTo(256)
            input.value = Input("d", "d")
            advanceTo(272)
            assertEquals("b", state.visibleValue)
            advanceTo(1216)
            advanceTo(2100)
            assertEquals("d", state.visibleValue)
            assertFalse(observed.contains("c"))
        }
    }

    @Test
    fun aSameSongUpgradeKeepsTheCurrentFlipAngle() = runBlocking {
        val input = mutableStateOf(Input("a", "a"))
        lateinit var state: ArtworkFlipState<String>
        withArtworkComposition(content = { state = rememberFlip(input.value) }) {
            advanceTo(0)
            input.value = Input("low-b", "b")
            advanceTo(16)
            advanceTo(256)
            val angle = state.frame.rotationY
            input.value = Input("high-b", "b")
            advanceTo(256)
            assertEquals(angle, state.frame.rotationY, 0.001f)
            assertEquals("high-b", state.visibleValue)
            advanceTo(1216)
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
                advanceTo(0)
                input.value = Input("b", "b")
                advanceTo(16)
                assertEquals(listOf(WeightedCrossfadeFrame("b", 1f)), fade.frames)
                assertEquals("b", flip.visibleValue)
                assertFalse(flip.isRunning)
                assertEquals(0f, flip.frame.blurFraction, 0f)
            }
        }
    }
}
