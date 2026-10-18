package com.melox.player.ui.component.playback

internal data class WeightedCrossfadeFrame<T>(
    val value: T,
    val alpha: Float,
    val isPlaceholder: Boolean = false,
)

internal fun sourceOverAlphas(weights: List<Float>): List<Float> {
    val clampedWeights = weights.map { it.coerceIn(0f, 1f) }
    val totalWeight = clampedWeights.sum().coerceAtMost(1f)
    var accumulatedWeight = 1f - totalWeight
    return clampedWeights.map { weight ->
        val combinedWeight = accumulatedWeight + weight
        val alpha = if (combinedWeight > 0f) weight / combinedWeight else 0f
        accumulatedWeight = combinedWeight
        alpha.coerceIn(0f, 1f)
    }
}

internal fun <T> weightedCrossfadeFrames(
    startingFrames: List<WeightedCrossfadeFrame<T>>,
    currentValue: T?,
    progress: Float,
    sameValue: (T, T) -> Boolean = { first, second -> first == second },
    currentIsPlaceholder: Boolean = false,
): List<WeightedCrossfadeFrame<T>> {
    val targetProgress = progress.coerceIn(0f, 1f)
    val frames = mutableListOf<WeightedCrossfadeFrame<T>>()

    fun add(value: T, alpha: Float, isPlaceholder: Boolean) {
        if (alpha <= 0.001f) return
        val existingIndex = frames.indexOfFirst {
            sameValue(it.value, value) && it.isPlaceholder == isPlaceholder
        }
        if (existingIndex >= 0) {
            val existing = frames[existingIndex]
            frames[existingIndex] = existing.copy(alpha = existing.alpha + alpha)
        } else {
            frames += WeightedCrossfadeFrame(value = value, alpha = alpha, isPlaceholder = isPlaceholder)
        }
    }

    startingFrames.forEach { frame ->
        add(frame.value, frame.alpha * (1f - targetProgress), frame.isPlaceholder)
    }
    currentValue?.let { add(it, targetProgress, currentIsPlaceholder) }
    return frames
}
