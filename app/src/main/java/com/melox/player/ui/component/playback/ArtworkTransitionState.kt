package com.melox.player.ui.component.playback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

internal data class ArtworkTarget<T>(val value: T?, val contentKey: Any?)

@Composable
internal fun <T> rememberReadyArtworkTarget(
    value: T?,
    contentKey: Any?,
    ready: Boolean,
): ArtworkTarget<T> {
    var published by remember { mutableStateOf(ArtworkTarget<T>(null, null)) }
    val target = if (ready) ArtworkTarget(value, contentKey) else published
    SideEffect { published = target }
    return target
}

internal fun artworkCrossfadeDurationMillis(
    currentContentKey: Any?,
    targetContentKey: Any?,
    contentChangeDurationMillis: Int,
    sameContentDurationMillis: Int?,
): Int = if (targetContentKey == currentContentKey) {
    sameContentDurationMillis ?: contentChangeDurationMillis
} else {
    contentChangeDurationMillis
}

@Composable
internal fun <T> rememberArtworkCrossfadeState(
    target: ArtworkTarget<T>,
    animate: Boolean,
    durationMillis: Int,
    sameContentDurationMillis: Int?,
    easing: Easing,
    sameValue: (T, T) -> Boolean,
): ArtworkCrossfadeState<T> {
    val state = remember { ArtworkCrossfadeState(target.value, target.contentKey, sameValue) }
    LaunchedEffect(target, animate, durationMillis, sameContentDurationMillis, easing) {
        if (!animate) {
            state.snapTo(target.value, target.contentKey)
        } else {
            state.transitionTo(
                target.value,
                target.contentKey,
                durationMillis,
                sameContentDurationMillis,
                easing,
            )
        }
    }
    return state
}

internal class ArtworkCrossfadeState<T>(
    initialValue: T?,
    initialKey: Any?,
    private val sameValue: (T, T) -> Boolean,
) {
    private var startingFrames by mutableStateOf<List<WeightedCrossfadeFrame<T>>>(emptyList())
    private var heldFrames by mutableStateOf<List<WeightedCrossfadeFrame<T>>?>(null)
    var currentValue by mutableStateOf(initialValue)
        private set
    var contentKey: Any? = initialKey
        private set
    val progress = Animatable(1f)
    val frames: List<WeightedCrossfadeFrame<T>>
        get() = heldFrames ?: weightedCrossfadeFrames(startingFrames, currentValue, progress.value, sameValue)

    suspend fun snapTo(value: T?, key: Any?) {
        heldFrames = listOfNotNull(value?.let { WeightedCrossfadeFrame(it, 1f) })
        startingFrames = emptyList()
        currentValue = value
        contentKey = key
        progress.snapTo(1f)
        heldFrames = null
    }

    suspend fun transitionTo(
        value: T?,
        key: Any?,
        durationMillis: Int,
        sameContentDurationMillis: Int?,
        easing: Easing,
    ) {
        val current = currentValue
        if (heldFrames == null && progress.value >= 1f &&
            (current == null && value == null || current != null && value != null && sameValue(current, value))
        ) {
            currentValue = value
            contentKey = key
            return
        }
        val visible = frames
        // Keep the exact image mixture while resetting the interruptible clock.
        heldFrames = visible
        val resolvedDuration = artworkCrossfadeDurationMillis(
            contentKey, key, durationMillis, sameContentDurationMillis,
        )
        progress.snapTo(0f)
        startingFrames = visible
        currentValue = value
        contentKey = key
        heldFrames = null
        progress.animateTo(1f, tween(resolvedDuration, easing = easing))
        startingFrames = emptyList()
    }
}
