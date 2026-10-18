package com.melox.player

import com.melox.player.ui.component.playback.WeightedCrossfadeFrame
import com.melox.player.ui.component.playback.sourceOverAlphas
import com.melox.player.ui.component.playback.weightedCrossfadeFrames
import org.junit.Assert.assertEquals
import org.junit.Test

class InterruptibleCrossfadeTest {
    @Test
    fun interruptedPlaceholderWeightsKeepRealAndPlaceholderRolesSeparate() {
        val frames = weightedCrossfadeFrames(
            startingFrames = listOf(
                WeightedCrossfadeFrame("real-a", 0.6f),
                WeightedCrossfadeFrame("placeholder", 0.4f, isPlaceholder = true),
            ),
            currentValue = "real-c",
            progress = 0.5f,
        )
        val weights = frames.map { it.alpha * if (it.isPlaceholder) 0.5f else 1f }
        val alphas = sourceOverAlphas(weights)
        val contributions = alphas.indices.map { index ->
            alphas[index] * alphas.drop(index + 1).fold(1f) { total, alpha -> total * (1f - alpha) }
        }
        assertEquals(0.3f, contributions[0], 0.0001f)
        assertEquals(0.1f, contributions[1], 0.0001f)
        assertEquals(0.5f, contributions[2], 0.0001f)
    }

    @Test
    fun sourceOverAlphasKeepAnOpaqueTwoFrameBlend() {
        val alphas = sourceOverAlphas(listOf(0.6f, 0.4f))

        assertEquals(1f, alphas[0], 0.0001f)
        assertEquals(0.4f, alphas[1], 0.0001f)
    }

    @Test
    fun sourceOverAlphasPreserveAnInterruptedThreeFrameMixture() {
        val weights = listOf(0.4f, 0.3f, 0.3f)
        val alphas = sourceOverAlphas(weights)
        val visibleWeights = alphas.indices.map { index ->
            alphas[index] * alphas.drop(index + 1).fold(1f) { value, alpha ->
                value * (1f - alpha)
            }
        }

        weights.indices.forEach { index ->
            assertEquals(weights[index], visibleWeights[index], 0.0001f)
        }
    }

    @Test
    fun sourceOverAlphasRetainFallbackWeightWhenTheTargetIsMissing() {
        val alphas = sourceOverAlphas(listOf(0.6f))

        assertEquals(0.6f, alphas.single(), 0.0001f)
    }

    @Test
    fun ordinaryTransitionKeepsTheExistingTwoFrameCurve() {
        val frames = weightedCrossfadeFrames(
            startingFrames = listOf(WeightedCrossfadeFrame("a", 1f)),
            currentValue = "b",
            progress = 0.4f,
        )

        assertEquals(listOf("a", "b"), frames.map { it.value })
        assertEquals(0.6f, frames[0].alpha, 0.0001f)
        assertEquals(0.4f, frames[1].alpha, 0.0001f)
    }

    @Test
    fun interruptedTransitionKeepsTheExactVisibleMixture() {
        val firstTransition = weightedCrossfadeFrames(
            startingFrames = listOf(WeightedCrossfadeFrame("a", 1f)),
            currentValue = "b",
            progress = 0.3f,
        )

        val interruptedTransition = weightedCrossfadeFrames(
            startingFrames = firstTransition,
            currentValue = "c",
            progress = 0f,
        )

        assertEquals(listOf("a", "b"), interruptedTransition.map { it.value })
        assertEquals(listOf(0.7f, 0.3f), interruptedTransition.map { it.alpha })
    }

    @Test
    fun returningToAnExistingFrameMergesItsWeight() {
        val frames = weightedCrossfadeFrames(
            startingFrames = listOf(
                WeightedCrossfadeFrame("a", 0.7f),
                WeightedCrossfadeFrame("b", 0.3f),
            ),
            currentValue = "a",
            progress = 0.5f,
        )

        assertEquals(listOf("a", "b"), frames.map { it.value })
        assertEquals(0.85f, frames[0].alpha, 0.0001f)
        assertEquals(0.15f, frames[1].alpha, 0.0001f)
    }

    @Test
    fun missingTargetFadesTheVisibleFramesIntoTheFallback() {
        val frames = weightedCrossfadeFrames(
            startingFrames = listOf(WeightedCrossfadeFrame("a", 1f)),
            currentValue = null,
            progress = 0.4f,
        )

        assertEquals(1, frames.size)
        assertEquals(0.6f, frames.single().alpha, 0.0001f)
    }
}
