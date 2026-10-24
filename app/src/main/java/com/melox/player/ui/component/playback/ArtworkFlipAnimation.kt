package com.melox.player.ui.component.playback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.roundToInt

private val ArtworkFlipReboundEasing = Easing { fraction ->
    // Slow down before the incoming face reaches rest, covering both overshoot and return.
    val turnFraction = if (fraction <= 0.2f) fraction * 2f
        else 0.4f + (fraction - 0.2f) * 0.75f
    FastOutSlowInEasing.transform(turnFraction)
}

internal data class ArtworkFlipFrame(
    val showBack: Boolean,
    val rotationY: Float,
    val blurFraction: Float,
)

internal fun artworkFlipCameraDistance(width: Float, height: Float): Float =
    // RenderNode forwards this to SkCamera in inches, which converts to points.
    // A three-edge distance shortens the far edge as in the supplied reference.
    maxOf(width, height, 1f) * 3f / 72f

internal fun artworkFlipFrame(progress: Float, direction: Int): ArtworkFlipFrame {
    val p = progress.coerceIn(0f, 1f)
    val showBack = p >= 0.5f
    // Keep chronological progress bounded so interrupted flips retain their face and timing.
    val turnProgress = if (showBack) 0.5f + 0.5f * EaseOutBack.transform((p - 0.5f) * 2f) else p
    val blurProgress = turnProgress.coerceIn(0f, 1f)
    val sign = if (direction < 0) -1f else 1f
    return ArtworkFlipFrame(
        showBack = showBack,
        rotationY = sign * (180f * turnProgress - if (showBack) 180f else 0f),
        blurFraction = if (blurProgress == 0f || blurProgress == 1f) 0f
            else sin(PI * blurProgress).toFloat(),
    )
}

@Composable
internal fun <T> rememberArtworkFlipState(
    target: ArtworkTarget<T>,
    requestedContentKey: Any?,
    targetReady: Boolean,
    animate: Boolean,
    direction: Int,
    durationMillis: Int,
): ArtworkFlipState<T> {
    val state = remember { ArtworkFlipState(target.value, target.contentKey) }
    LaunchedEffect(target, requestedContentKey, targetReady, animate, direction, durationMillis) {
        if (!animate) {
            state.snapTo(target.value, target.contentKey)
        } else if (!targetReady) {
            state.settleVisible(durationMillis)
        } else {
            val value = target.value
            if (value == null) state.snapTo(null, target.contentKey)
            else state.transitionTo(value, target.contentKey, direction, durationMillis)
        }
    }
    return state
}

internal class ArtworkFlipState<T>(initialValue: T?, initialKey: Any?, initialPlaceholder: Boolean = false) {
    var front by mutableStateOf(initialValue)
        private set
    var back by mutableStateOf<T?>(null)
        private set
    private var frontIsPlaceholder by mutableStateOf(initialPlaceholder)
    private var backIsPlaceholder by mutableStateOf(false)
    private var frontKey: Any? = initialKey
    private var backKey: Any? = null
    private var direction by mutableStateOf(1)
    val progress = Animatable(1f)
    // Keep the incoming face active until it is promoted to the front.
    val isRunning: Boolean get() = back != null
    val frame: ArtworkFlipFrame get() = artworkFlipFrame(if (isRunning) progress.value else 0f, direction)
    val visibleValue: T? get() = if (isRunning && frame.showBack) back else front
    val visibleContentKey: Any? get() = if (isRunning && frame.showBack) backKey else frontKey
    val visibleIsPlaceholder: Boolean
        get() = if (isRunning && frame.showBack) backIsPlaceholder else frontIsPlaceholder

    suspend fun snapTo(value: T?, key: Any?, isPlaceholder: Boolean = false) {
        front = value
        frontKey = key
        frontIsPlaceholder = isPlaceholder
        back = null
        backKey = null
        backIsPlaceholder = false
        progress.snapTo(1f)
    }

    suspend fun transitionTo(
        value: T,
        key: Any?,
        requestedDirection: Int,
        durationMillis: Int,
        isPlaceholder: Boolean = false,
        canStartNextTurn: () -> Boolean = { true },
    ) {
        if (front == null) {
            snapTo(value, key, isPlaceholder)
            return
        }
        if (isRunning) {
            if (key == backKey || value === back) {
                back = value
                backKey = key
                backIsPlaceholder = isPlaceholder
                finish(durationMillis)
                return
            }
            if (progress.value < 0.5f) {
                if (key == frontKey || value === front) {
                    frontKey = key
                    settleToFront(durationMillis)
                    front = value
                    frontIsPlaceholder = isPlaceholder
                    return
                }
                back = value
                backKey = key
                backIsPlaceholder = isPlaceholder
                finish(durationMillis)
                return
            }
            finish(durationMillis)
            if (!canStartNextTurn()) return
        }
        if (key == frontKey) {
            front = value
            frontIsPlaceholder = isPlaceholder
            return
        }
        // Confirmed identical artwork can belong to consecutive songs.
        if (value === front) {
            frontKey = key
            frontIsPlaceholder = isPlaceholder
            return
        }
        // Reset before installing the next back so it cannot inherit the endpoint.
        progress.snapTo(0f)
        back = value
        backKey = key
        backIsPlaceholder = isPlaceholder
        direction = if (requestedDirection < 0) -1 else 1
        finish(durationMillis)
    }

    suspend fun settleVisible(durationMillis: Int) {
        if (!isRunning) return
        if (progress.value < 0.5f) settleToFront(durationMillis) else finish(durationMillis)
    }

    private suspend fun settleToFront(durationMillis: Int) {
        progress.animateTo(
            0f,
            tween((durationMillis * progress.value).roundToInt(), easing = FastOutSlowInEasing),
        )
        back = null
        backKey = null
        backIsPlaceholder = false
    }

    private suspend fun finish(durationMillis: Int) {
        progress.animateTo(
            1f,
            tween((durationMillis * 2f * (1f - progress.value)).roundToInt(),
                easing = ArtworkFlipReboundEasing),
        )
        front = back
        frontKey = backKey
        frontIsPlaceholder = backIsPlaceholder
        back = null
        backKey = null
        backIsPlaceholder = false
    }
}
