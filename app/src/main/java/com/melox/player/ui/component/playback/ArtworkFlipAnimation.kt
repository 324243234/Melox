package com.melox.player.ui.component.playback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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

/** 翻转结尾越界量：1.04 ≈ 越过平面 7.2°，配合 spring 软回弹收尾更自然。 */
private const val ARTWORK_FLIP_OVERSHOOT = 1.04f

internal fun artworkFlipFrame(progress: Float, direction: Int): ArtworkFlipFrame {
    val raw = progress
    val p = raw.coerceIn(0f, 1f)
    val showBack = p >= 0.5f
    val sign = if (direction < 0) -1f else 1f
    return ArtworkFlipFrame(
        showBack = showBack,
        rotationY = sign * (180f * raw - if (showBack) 180f else 0f),
        blurFraction = if (raw <= 0f || raw >= 1f) 0f else sin(PI * p).toFloat(),
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
    // 组合期只读这个"是否已翻过中点"的派生状态：progress 每帧推进不会触发重组，
    // 只在跨过 0.5 时通知一次，避免快速连切时每帧重组导致画面发颤。
    private val visibleIsBack by derivedStateOf { isRunning && progress.value >= 0.5f }
    val visibleValue: T? get() = if (visibleIsBack) back ?: front else front
    val visibleContentKey: Any? get() = if (visibleIsBack) backKey else frontKey
    val visibleIsPlaceholder: Boolean
        get() = if (visibleIsBack) backIsPlaceholder else frontIsPlaceholder

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
        val start = progress.value
        if (start < 1f) {
            // 还未到过冲点（无论是首次翻转到 0，还是中途被打断的续转）：
            // 继续冲到 ARTWORK_FLIP_OVERSHOOT 再 spring 回正，保留自然的结尾过冲。
            // remaining 给最短时长下限，避免剩余时间接近 0 时瞬跳。
            val remaining = (durationMillis * (1f - start)).roundToInt().coerceAtLeast(60)
            progress.animateTo(
                ARTWORK_FLIP_OVERSHOOT,
                tween(remaining, easing = FastOutSlowInEasing),
            )
            progress.animateTo(
                1f,
                spring(dampingRatio = 1f, stiffness = 100f, visibilityThreshold = 0.001f),
            )
        } else {
            // 已经在过冲/回弹段（前一轮收尾被 LaunchedEffect 重启打断）：
            // 从当前角度平滑 spring 回正面，既不瞬跳、也不重复冲 1.04；过冲已在上一轮发生。
            progress.animateTo(
                1f,
                spring(dampingRatio = 1f, stiffness = 100f, visibilityThreshold = 0.001f),
            )
        }
        front = back
        frontKey = backKey
        frontIsPlaceholder = backIsPlaceholder
        back = null
        backKey = null
        backIsPlaceholder = false
    }
}
