package com.melox.player.ui.component.playback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

internal class MiniPlayerSwipeState(private val scope: CoroutineScope) {
    var distance by mutableFloatStateOf(0f)
        private set
    private var returnJob: Job? = null

    fun begin() {
        returnJob?.cancel()
    }

    fun dragBy(amount: Float) {
        distance += amount
    }

    fun release() {
        returnJob?.cancel()
        if (distance == 0f) return
        returnJob = scope.launch {
            Animatable(distance).animateTo(0f, spring(1f, 300f, 0.5f)) {
                distance = value
            }
            distance = 0f
        }
    }

    fun reset() {
        returnJob?.cancel()
        distance = 0f
    }

    fun translationX(width: Float, limit: Float): Float {
        val fraction = (distance / width.coerceAtLeast(1f)).coerceIn(-1f, 1f)
        return limit * fraction.sign * EaseOut.transform(abs(fraction))
    }
}

@Composable
internal fun rememberMiniPlayerSwipeState(
    enabled: Boolean,
    recordingForTransition: Boolean,
): MiniPlayerSwipeState {
    val scope = rememberCoroutineScope()
    val state = remember(scope) { MiniPlayerSwipeState(scope) }
    LaunchedEffect(enabled, recordingForTransition) {
        state.reset()
    }
    return state
}
