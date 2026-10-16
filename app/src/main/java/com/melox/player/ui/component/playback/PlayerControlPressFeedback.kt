package com.melox.player.ui.component.playback

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isOutOfBounds

internal data class PlayerControlPressFeedback(
    val modifier: Modifier,
    val scale: State<Float>,
)

@Composable
internal fun rememberPlayerControlPressFeedback(enabled: Boolean = true): PlayerControlPressFeedback {
    var pressed by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(
        targetValue = if (enabled && pressed) 0.94f else 1f,
        animationSpec = tween(if (pressed) 100 else 140, easing = LinearOutSlowInEasing),
        label = "playerControlPressScale",
    )
    val modifier = Modifier.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var cancelled = false
            pressed = true
            try {
                // Observe visual feedback only; native clickable owns activation and cancellation.
                do {
                    val change = awaitPointerEvent(PointerEventPass.Final).changes
                        .firstOrNull { it.id == down.id } ?: break
                    cancelled = cancelled || change.isOutOfBounds(size, extendedTouchPadding) ||
                        (change.isConsumed && change.position != change.previousPosition)
                    pressed = change.pressed && !cancelled
                } while (change.pressed)
            } finally {
                pressed = false
            }
        }
    }
    return PlayerControlPressFeedback(modifier, scale)
}
