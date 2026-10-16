package com.melox.player.ui.component.playback

import androidx.compose.animation.core.Animatable
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
    val sign = if (direction < 0) -1f else 1f
    return ArtworkFlipFrame(
        showBack = showBack,
        rotationY = sign * (180f * p - if (showBack) 180f else 0f),
        blurFraction = if (p == 0f || p == 1f) 0f else sin(PI * p).toFloat(),
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

internal class ArtworkFlipState<T>(initialValue: T?, initialKey: Any?) {
    var front by mutableStateOf(initialValue)
        private set
    var back by mutableStateOf<T?>(null)
        private set
    private var frontKey: Any? = initialKey
    private var backKey: Any? = null
    private var direction by mutableStateOf(1)
    val progress = Animatable(1f)
    val isRunning: Boolean get() = back != null && progress.value < 1f
    val frame: ArtworkFlipFrame get() = artworkFlipFrame(if (isRunning) progress.value else 0f, direction)
    val visibleValue: T? get() = if (isRunning && frame.showBack) back else front

    suspend fun snapTo(value: T?, key: Any?) {
        front = value
        frontKey = key
        back = null
        backKey = null
        progress.snapTo(1f)
    }

    suspend fun transitionTo(value: T, key: Any?, requestedDirection: Int, durationMillis: Int) {
        if (front == null) {
            snapTo(value, key)
            return
        }
        if (isRunning) {
            if (key == backKey || value === back) {
                back = value
                backKey = key
                finish(durationMillis)
                return
            }
            if (progress.value < 0.5f) {
                if (key == frontKey || value === front) {
                    frontKey = key
                    settleToFront(durationMillis)
                    front = value
                    return
                }
                back = value
                backKey = key
                finish(durationMillis)
                return
            }
            finish(durationMillis)
        }
        if (key == frontKey) {
            front = value
            return
        }
        // Confirmed identical artwork can belong to consecutive songs.
        if (value === front) {
            frontKey = key
            return
        }
        back = value
        backKey = key
        direction = if (requestedDirection < 0) -1 else 1
        progress.snapTo(0f)
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
    }

    private suspend fun finish(durationMillis: Int) {
        progress.animateTo(
            1f,
            tween((durationMillis * (1f - progress.value)).roundToInt(), easing = FastOutSlowInEasing),
        )
        front = back
        frontKey = backKey
        back = null
        backKey = null
    }
}
