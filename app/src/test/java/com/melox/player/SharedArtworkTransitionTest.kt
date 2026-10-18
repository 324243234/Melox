package com.melox.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.MotionDurationScale
import com.melox.player.ui.component.playback.ArtworkTransitionState
import com.melox.player.ui.component.playback.WeightedCrossfadeFrame
import com.melox.player.ui.component.playback.rememberArtworkTransitionState
import com.melox.player.ui.component.playback.rememberReadyArtworkTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val sameSharedArtwork: (String, String) -> Boolean = { first, second -> first == second }

class SharedArtworkTransitionTest {
    private data class Input(
        val image: String? = "a",
        val song: String? = "a",
        val ready: Boolean = true,
        val flip: Boolean = true,
        val direction: Int = 1,
    )

    private data class Frame(
        val image: String?,
        val key: Any?,
        val progress: Float,
        val rotation: Float?,
        val layers: List<WeightedCrossfadeFrame<String>>,
    )

    @Composable
    private fun rememberTransition(input: Input): ArtworkTransitionState<String> =
        rememberArtworkTransitionState(
            target = rememberReadyArtworkTarget(input.image, input.song, input.ready),
            requestedContentKey = input.song,
            targetReady = input.ready,
            animate = true,
            flipAnimationEnabled = input.flip,
            direction = input.direction,
            durationMillis = 400,
            sameContentDurationMillis = 100,
            easing = LinearEasing,
            sameValue = sameSharedArtwork,
        )

    @Composable
    private fun Observe(state: ArtworkTransitionState<String>, onFrame: (Frame) -> Unit) {
        val frame = Frame(state.currentValue, state.currentContentKey, state.progress,
            state.flipState?.frame?.rotationY, state.frames)
        SideEffect { onFrame(frame) }
    }

    @Test
    fun lateConsumersJoinTheSameFaceAndProgressWithoutRestartingTheOwner() = runBlocking {
        val input = mutableStateOf(Input())
        val overlayMounted = mutableStateOf(false)
        val fullMounted = mutableStateOf(true)
        val observed = mutableMapOf<String, Frame>()
        var ownerCompositions = 0
        lateinit var state: ArtworkTransitionState<String>
        withArtworkComposition(content = {
            state = rememberTransition(input.value)
            SideEffect { ownerCompositions++ }
            Observe(state) { observed["mini"] = it }
            if (fullMounted.value) Observe(state) { observed["full"] = it }
            if (overlayMounted.value) Observe(state) { observed["overlay"] = it }
        }) {
            frame(0)
            input.value = Input("b", "b")
            frame(16)
            val compositions = ownerCompositions
            frame(116)
            frame(256)
            assertEquals(compositions, ownerCompositions)
            assertEquals("b", state.currentValue)
            val progress = state.progress
            overlayMounted.value = true
            frame(256)
            assertEquals(progress, state.progress, 0f)
            assertEquals(observed["mini"], observed["full"])
            assertEquals(observed["mini"], observed["overlay"])
            fullMounted.value = false
            frame(272)
            fullMounted.value = true
            frame(272)
            assertEquals(observed["mini"], observed["full"])
            assertEquals(observed["mini"], observed["overlay"])
            frame(416)
            assertEquals("b", state.currentValue)
            assertFalse(state.isRunning)
        }
    }

    @Test
    fun changingFromFlipToFadeKeepsTheCurrentTurnAndChangesTheFollowingTurn() = runBlocking {
        val input = mutableStateOf(Input())
        lateinit var state: ArtworkTransitionState<String>
        withArtworkComposition(content = { state = rememberTransition(input.value) }) {
            frame(0)
            input.value = Input("b", "b")
            frame(16)
            frame(256)
            val angle = state.flipState!!.frame.rotationY
            input.value = input.value.copy(flip = false)
            frame(256)
            assertTrue(state.flipAnimationEnabled)
            assertEquals("b", state.currentValue)
            assertEquals(angle, state.flipState!!.frame.rotationY, 0f)
            frame(416)
            assertFalse(state.flipAnimationEnabled)
            assertEquals(listOf(WeightedCrossfadeFrame("b", 1f)), state.frames)
            input.value = Input("c", "c", flip = false)
            frame(432)
            frame(532)
            assertEquals(0.25f, state.progress, 0.02f)
            assertEquals(listOf(WeightedCrossfadeFrame("b", 0.75f),
                WeightedCrossfadeFrame("c", 0.25f)), state.frames)
        }
    }

    @Test
    fun changingFromFadeToFlipKeepsTheVisibleMixtureUntilTheFadeFinishes() = runBlocking {
        val input = mutableStateOf(Input(flip = false))
        lateinit var state: ArtworkTransitionState<String>
        withArtworkComposition(content = { state = rememberTransition(input.value) }) {
            frame(0)
            input.value = Input("b", "b", flip = false)
            frame(16)
            frame(116)
            val visible = state.frames
            input.value = input.value.copy(flip = true)
            frame(116)
            assertFalse(state.flipAnimationEnabled)
            assertEquals(visible, state.frames)
            frame(416)
            assertTrue(state.flipAnimationEnabled)
            assertFalse(state.isRunning)
            assertEquals("b", state.currentValue)
            input.value = Input("c", "c", direction = -1)
            frame(432)
            frame(512)
            assertTrue(state.flipState!!.frame.rotationY < 0f)
            assertEquals("b", state.currentValue)
            frame(832)
            assertEquals("c", state.currentValue)
        }
    }

    @Test
    fun repeatedModeChangesApplyOnlyTheLastSelectionAtCompletion() = runBlocking {
        val input = mutableStateOf(Input())
        lateinit var state: ArtworkTransitionState<String>
        withArtworkComposition(content = { state = rememberTransition(input.value) }) {
            frame(0)
            input.value = Input("b", "b")
            frame(16)
            frame(96)
            for (flip in listOf(false, true, false)) {
                input.value = input.value.copy(flip = flip)
                frame(96)
                assertTrue(state.flipAnimationEnabled)
            }
            frame(416)
            assertFalse(state.flipAnimationEnabled)
            assertEquals("b", state.currentValue)
            assertEquals("b", state.currentContentKey)
            input.value = input.value.copy(flip = true)
            frame(432)
            assertTrue(state.flipAnimationEnabled)
            assertFalse(state.isRunning)
            assertEquals("b", state.currentValue)
        }
    }

    @Test
    fun aModeChangeWhileWaitingDoesNotReviveTheHiddenBack() = runBlocking {
        val input = mutableStateOf(Input())
        lateinit var state: ArtworkTransitionState<String>
        withArtworkComposition(content = { state = rememberTransition(input.value) }) {
            frame(0)
            input.value = Input("b", "b")
            frame(16)
            frame(96)
            input.value = Input("placeholder", "c", ready = false, flip = false)
            frame(96)
            frame(196)
            assertFalse(state.flipAnimationEnabled)
            assertFalse(state.isRunning)
            assertEquals("a", state.currentValue)
            assertEquals("a", state.currentContentKey)
            frame(500)
            assertEquals(listOf(WeightedCrossfadeFrame("a", 1f)), state.frames)
            input.value = Input("c", "c", flip = false)
            frame(516)
            frame(616)
            assertEquals(listOf(WeightedCrossfadeFrame("a", 0.75f),
                WeightedCrossfadeFrame("c", 0.25f)), state.frames)
        }
    }

    @Test
    fun aQueuedTargetUsesTheNewModeAfterTheVisibleBackCompletes() = runBlocking {
        val input = mutableStateOf(Input())
        val flippedBacks = mutableListOf<String?>()
        lateinit var state: ArtworkTransitionState<String>
        withArtworkComposition(content = {
            state = rememberTransition(input.value)
            val back = state.flipState?.back
            SideEffect { flippedBacks += back }
        }) {
            frame(0)
            input.value = Input("b", "b")
            frame(16)
            frame(256)
            input.value = Input("c", "c", flip = false)
            frame(256)
            assertTrue(state.flipAnimationEnabled)
            assertEquals("b", state.currentValue)
            frame(416)
            assertFalse(state.flipAnimationEnabled)
            assertFalse(flippedBacks.contains("c"))
            frame(516)
            assertTrue(state.frames.any { it.value == "b" && it.alpha > 0f })
            assertTrue(state.frames.any { it.value == "c" && it.alpha > 0f })
            frame(816)
            assertEquals(listOf(WeightedCrossfadeFrame("c", 1f)), state.frames)
        }
    }

    @Test
    fun anotherSkipDuringAModeHandoffKeepsOnlyTheNewestTarget() = runBlocking {
        val input = mutableStateOf(Input())
        val observed = mutableListOf<String>()
        lateinit var state: ArtworkTransitionState<String>
        withArtworkComposition(content = {
            state = rememberTransition(input.value)
            val layers = state.frames.map { it.value } + listOfNotNull(state.currentValue)
            SideEffect { observed += layers }
        }) {
            frame(0)
            input.value = Input("b", "b")
            frame(16)
            frame(256)
            input.value = Input("c", "c", flip = false)
            frame(256)
            input.value = Input("d", "d", flip = false, direction = -1)
            frame(272)
            assertEquals("b", state.currentValue)
            frame(416)
            assertFalse(state.flipAnimationEnabled)
            frame(516)
            assertFalse(observed.contains("c"))
            assertTrue(state.frames.any { it.value == "d" && it.alpha > 0f })
            frame(816)
            assertEquals(listOf(WeightedCrossfadeFrame("d", 1f)), state.frames)
        }
    }

    @Test
    fun aPendingSongDoesNotInterruptTheFadeBeforeItsRequestedModeHandoff() = runBlocking {
        val input = mutableStateOf(Input(flip = false))
        lateinit var state: ArtworkTransitionState<String>
        withArtworkComposition(content = { state = rememberTransition(input.value) }) {
            frame(0)
            input.value = Input("b", "b", flip = false)
            frame(16)
            frame(116)
            val visible = state.frames
            input.value = Input("placeholder", "c", ready = false, flip = true)
            frame(116)
            assertFalse(state.flipAnimationEnabled)
            assertEquals(visible, state.frames)
            frame(216)
            assertEquals(0.5f, state.progress, 0.02f)
            frame(416)
            assertTrue(state.flipAnimationEnabled)
            assertFalse(state.isRunning)
            assertEquals("b", state.currentValue)
            assertEquals("b", state.currentContentKey)
            input.value = Input("c", "c")
            frame(432)
            frame(512)
            assertEquals("b", state.currentValue)
            frame(832)
            assertEquals("c", state.currentValue)
        }
    }

    @Test
    fun aReadyQueuedSongUsesFlipAfterTheCurrentFadeCompletes() = runBlocking {
        val input = mutableStateOf(Input(flip = false))
        lateinit var state: ArtworkTransitionState<String>
        withArtworkComposition(content = { state = rememberTransition(input.value) }) {
            frame(0)
            input.value = Input("b", "b", flip = false)
            frame(16)
            frame(116)
            input.value = Input("c", "c", flip = true)
            frame(116)
            assertFalse(state.flipAnimationEnabled)
            frame(416)
            assertTrue(state.flipAnimationEnabled)
            assertEquals("b", state.currentValue)
            frame(496)
            assertTrue(state.flipState != null)
            assertEquals("c", state.flipState!!.back)
            frame(816)
            assertEquals("c", state.currentValue)
        }
    }

    @Test
    fun withdrawingAModeChangeStillProcessesTheLatestSongAfterSettling() = runBlocking {
        val input = mutableStateOf(Input(flip = false))
        lateinit var state: ArtworkTransitionState<String>
        withArtworkComposition(content = { state = rememberTransition(input.value) }) {
            frame(0)
            input.value = Input("b", "b", flip = false)
            frame(16)
            frame(116)
            input.value = Input("c", "c", flip = true)
            frame(116)
            input.value = input.value.copy(flip = false)
            frame(216)
            frame(416)
            assertFalse(state.flipAnimationEnabled)
            frame(816)
            assertEquals("c", state.currentValue)
            assertEquals(listOf(WeightedCrossfadeFrame("c", 1f)), state.frames)
        }
    }

    @Test
    fun settledQualityUpgradesKeepTheirShortDurationAndTracksKeepTheirNormalDuration() = runBlocking {
        val input = mutableStateOf(Input(flip = false))
        lateinit var state: ArtworkTransitionState<String>
        withArtworkComposition(content = { state = rememberTransition(input.value) }) {
            frame(0)
            input.value = Input("high-a", "a", flip = false)
            frame(16)
            frame(66)
            assertEquals(0.5f, state.progress, 0.02f)
            frame(116)
            assertEquals(listOf(WeightedCrossfadeFrame("high-a", 1f)), state.frames)
            input.value = Input("b", "b", flip = false)
            frame(132)
            frame(232)
            assertEquals(0.25f, state.progress, 0.02f)
            frame(532)
            assertEquals(listOf(WeightedCrossfadeFrame("b", 1f)), state.frames)
        }
    }

    @Test
    fun aLateQualityImageKeepsTheActiveFadesProgressAndOutgoingLayer() = runBlocking {
        val input = mutableStateOf(Input(flip = false))
        lateinit var state: ArtworkTransitionState<String>
        withArtworkComposition(content = { state = rememberTransition(input.value) }) {
            frame(0)
            input.value = Input("low-b", "b", flip = false)
            frame(16)
            frame(116)
            val progress = state.progress
            input.value = input.value.copy(image = "high-b")
            frame(116)
            assertEquals(progress, state.progress, 0f)
            assertEquals(listOf(WeightedCrossfadeFrame("a", 0.75f),
                WeightedCrossfadeFrame("high-b", 0.25f)), state.frames)
            input.value = Input("placeholder", "c", ready = false, flip = false)
            frame(132)
            assertTrue(state.progress > progress)
            frame(416)
            assertEquals(listOf(WeightedCrossfadeFrame("high-b", 1f)), state.frames)
        }
    }

    @Test
    fun aLateQualityImageKeepsTheActiveFlipAngleForEveryConsumer() = runBlocking {
        val input = mutableStateOf(Input())
        val observed = mutableMapOf<String, Frame>()
        lateinit var state: ArtworkTransitionState<String>
        withArtworkComposition(content = {
            state = rememberTransition(input.value)
            Observe(state) { observed["mini"] = it }
            Observe(state) { observed["full"] = it }
            Observe(state) { observed["overlay"] = it }
        }) {
            frame(0)
            input.value = Input("low-b", "b")
            frame(16)
            frame(256)
            val angle = state.flipState!!.frame.rotationY
            input.value = input.value.copy(image = "high-b")
            frame(256)
            assertEquals(angle, state.flipState!!.frame.rotationY, 0f)
            assertEquals("high-b", state.currentValue)
            assertEquals(observed["mini"], observed["full"])
            assertEquals(observed["mini"], observed["overlay"])
            frame(416)
            assertEquals("high-b", state.currentValue)
        }
    }

    @Test
    fun zeroMotionScaleSettlesModeChangesAndTheNewestReadyTarget() {
        val scale = object : MotionDurationScale { override val scaleFactor = 0f }
        runBlocking(scale) {
            val input = mutableStateOf(Input())
            lateinit var state: ArtworkTransitionState<String>
            withArtworkComposition(content = { state = rememberTransition(input.value) }) {
                frame(0)
                input.value = Input("b", "b", flip = false)
                frame(16)
                assertFalse(state.isRunning)
                assertFalse(state.flipAnimationEnabled)
                assertEquals("b", state.currentValue)
                input.value = Input("c", "c", flip = true)
                frame(32)
                assertFalse(state.isRunning)
                assertTrue(state.flipAnimationEnabled)
                assertEquals("c", state.currentValue)
            }
        }
    }
}
