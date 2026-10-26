package com.melox.player.ui.component.playback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.channels.Channel
import kotlin.math.roundToInt

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
    initialPlaceholder: Boolean = false,
) {
    private var startingFrames by mutableStateOf<List<WeightedCrossfadeFrame<T>>>(emptyList())
    private var heldFrames by mutableStateOf<List<WeightedCrossfadeFrame<T>>?>(null)
    var currentValue by mutableStateOf(initialValue)
        private set
    var isPlaceholderArtwork by mutableStateOf(initialPlaceholder)
        private set
    var contentKey: Any? = initialKey
        private set
    val progress = Animatable(1f)
    private var animationDurationMillis = 0
    private var animationEasing: Easing = LinearEasing
    val isRunning: Boolean get() = heldFrames != null || progress.value < 1f
    val frames: List<WeightedCrossfadeFrame<T>>
        get() = heldFrames ?: weightedCrossfadeFrames(
            startingFrames, currentValue, progress.value, sameValue, isPlaceholderArtwork,
        )

    suspend fun snapTo(value: T?, key: Any?, isPlaceholder: Boolean = false) {
        heldFrames = listOfNotNull(value?.let { WeightedCrossfadeFrame(it, 1f, isPlaceholder) })
        startingFrames = emptyList()
        currentValue = value
        isPlaceholderArtwork = isPlaceholder
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
        isPlaceholder: Boolean = false,
    ) {
        val current = currentValue
        if (heldFrames == null && progress.value >= 1f && isPlaceholderArtwork == isPlaceholder &&
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
        animationDurationMillis = resolvedDuration
        animationEasing = easing
        progress.snapTo(0f)
        startingFrames = visible
        currentValue = value
        isPlaceholderArtwork = isPlaceholder
        contentKey = key
        heldFrames = null
        finishCurrent()
    }

    fun updateCurrentValue(value: T?, isPlaceholder: Boolean = isPlaceholderArtwork) {
        currentValue = value
        isPlaceholderArtwork = isPlaceholder
    }

    suspend fun finishCurrent() {
        if (!isRunning) return
        progress.animateTo(
            1f,
            tween((animationDurationMillis * (1f - progress.value)).roundToInt(), easing = animationEasing),
        )
        startingFrames = emptyList()
    }
}

private data class ArtworkTransitionRequest<T>(
    val target: ArtworkTarget<T>,
    val targetReady: Boolean,
    val targetIsPlaceholder: Boolean,
    val animate: Boolean,
    val direction: Int,
    val durationMillis: Int,
    val sameContentDurationMillis: Int?,
    val easing: Easing,
)

@Composable
internal fun <T> rememberArtworkTransitionState(
    target: ArtworkTarget<T>,
    requestedContentKey: Any?,
    targetReady: Boolean,
    targetIsPlaceholder: Boolean = false,
    animate: Boolean,
    flipAnimationEnabled: Boolean,
    direction: Int,
    durationMillis: Int,
    sameContentDurationMillis: Int?,
    easing: Easing,
    sameValue: (T, T) -> Boolean,
): ArtworkTransitionState<T> {
    val state = remember {
        ArtworkTransitionState(target, flipAnimationEnabled, sameValue, targetIsPlaceholder)
    }
    val activeFlip = state.flipAnimationEnabled
    SideEffect { state.requestMode(flipAnimationEnabled) }
    // ── 请求队列（conflated）驱动动画 ──
    // 旧实现 LaunchedEffect 直接 suspend 运行 transitionTo：切歌时 target/targetReady/
    // requestedContentKey 的多次变化会取消进行中的 animateTo —— 翻转半路被打断、反复重来，
    // 快速连切时观感就是"来不及转，抖两下就不转了"。
    // 现在 key 变化只投递一个最新请求（未处理的旧请求被覆盖），动画由独立协程按序执行：
    // 当前这一圈一定完整播完（含结尾过冲回弹），再立刻转向最新目标，从而连贯。
    val requests = remember(state) { Channel<ArtworkTransitionRequest<T>>(Channel.CONFLATED) }
    LaunchedEffect(state) {
        for (request in requests) {
            state.transitionTo(
                request.target, request.targetReady, request.targetIsPlaceholder,
                request.animate, request.direction, request.durationMillis,
                request.sameContentDurationMillis, request.easing,
            )
        }
    }
    LaunchedEffect(
        target, targetIsPlaceholder, activeFlip, animate, durationMillis, sameContentDurationMillis, easing,
        if (activeFlip) requestedContentKey else null,
        if (activeFlip) targetReady else true,
        if (activeFlip) direction else 0,
    ) {
        requests.trySend(
            ArtworkTransitionRequest(
                target = target,
                targetReady = targetReady,
                targetIsPlaceholder = targetIsPlaceholder,
                animate = animate,
                direction = direction,
                durationMillis = durationMillis,
                sameContentDurationMillis = sameContentDurationMillis,
                easing = easing,
            ),
        )
    }
    return state
}

internal class ArtworkTransitionState<T>(
    initialTarget: ArtworkTarget<T>,
    initialFlipAnimationEnabled: Boolean,
    private val sameValue: (T, T) -> Boolean,
    initialPlaceholder: Boolean = false,
) {
    private var flip = ArtworkFlipState(initialTarget.value, initialTarget.contentKey, initialPlaceholder)
    private var fade = ArtworkCrossfadeState(initialTarget.value, initialTarget.contentKey, sameValue, initialPlaceholder)
    private var requestedFlipAnimationEnabled = initialFlipAnimationEnabled
    var flipAnimationEnabled by mutableStateOf(initialFlipAnimationEnabled)
        private set
    val isRunning: Boolean get() = if (flipAnimationEnabled) flip.isRunning else fade.isRunning
    val currentValue: T? get() = if (flipAnimationEnabled) flip.visibleValue else fade.currentValue
    val currentContentKey: Any? get() = if (flipAnimationEnabled) flip.visibleContentKey else fade.contentKey
    val isPlaceholderArtwork: Boolean
        get() = if (flipAnimationEnabled) flip.visibleIsPlaceholder else fade.isPlaceholderArtwork
    val progress: Float get() = if (flipAnimationEnabled) flip.progress.value else fade.progress.value
    val flipState: ArtworkFlipState<T>?
        get() = if (flipAnimationEnabled) flip.takeIf { it.isRunning } else null
    val frames: List<WeightedCrossfadeFrame<T>>
        get() = if (!flipAnimationEnabled) fade.frames else if (flip.isRunning) emptyList()
        else listOfNotNull(flip.visibleValue?.let { WeightedCrossfadeFrame(it, 1f, flip.visibleIsPlaceholder) })

    fun requestMode(enabled: Boolean) {
        requestedFlipAnimationEnabled = enabled
        applyModeIfSettled()
    }

    suspend fun transitionTo(
        target: ArtworkTarget<T>,
        targetReady: Boolean,
        targetIsPlaceholder: Boolean,
        animate: Boolean,
        direction: Int,
        durationMillis: Int,
        sameContentDurationMillis: Int?,
        easing: Easing,
    ) {
        val installingInitialPlaceholder = targetReady && targetIsPlaceholder && currentValue == null && !isRunning
        if (!animate || installingInitialPlaceholder) {
            if (flipAnimationEnabled) flip.snapTo(target.value, target.contentKey, targetIsPlaceholder)
            else fade.snapTo(target.value, target.contentKey, targetIsPlaceholder)
        } else if (!targetReady) {
            if (flipAnimationEnabled) flip.settleVisible(durationMillis) else fade.finishCurrent()
        } else if (flipAnimationEnabled) {
            val value = target.value
            if (value == null) flip.snapTo(null, target.contentKey, targetIsPlaceholder)
            else flip.transitionTo(value, target.contentKey, direction, durationMillis,
                isPlaceholder = targetIsPlaceholder,
                canStartNextTurn = { requestedFlipAnimationEnabled == flipAnimationEnabled })
        } else {
            if (fade.isRunning && requestedFlipAnimationEnabled != flipAnimationEnabled &&
                target.contentKey != fade.contentKey
            ) {
                fade.finishCurrent()
                // The queued song is processed by the new mode's effect after this boundary.
                if (requestedFlipAnimationEnabled != flipAnimationEnabled || flipAnimationEnabled) {
                    applyModeIfSettled()
                    return
                }
            }
            if (fade.isRunning && target.contentKey == fade.contentKey) {
                fade.updateCurrentValue(target.value, targetIsPlaceholder)
                fade.finishCurrent()
            } else {
                fade.transitionTo(target.value, target.contentKey, durationMillis,
                    sameContentDurationMillis, easing, targetIsPlaceholder)
            }
        }
        applyModeIfSettled()
    }

    private fun applyModeIfSettled() {
        if (requestedFlipAnimationEnabled == flipAnimationEnabled || isRunning) return
        val value = currentValue
        val key = currentContentKey
        val isPlaceholder = isPlaceholderArtwork
        if (requestedFlipAnimationEnabled) flip = ArtworkFlipState(value, key, isPlaceholder)
        else fade = ArtworkCrossfadeState(value, key, sameValue, isPlaceholder)
        flipAnimationEnabled = requestedFlipAnimationEnabled
    }
}
