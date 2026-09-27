package com.melox.player.ui.screen.playback

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.melox.player.R

@Composable
internal fun rememberPlayerHeaderGestureModifier(
    enabled: Boolean,
    playWhenReady: Boolean,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenLyricsSettings: () -> Unit,
): Modifier {
    val currentOnTogglePlayPause by rememberUpdatedState(onTogglePlayPause)
    val currentOnPrevious by rememberUpdatedState(onPrevious)
    val currentOnNext by rememberUpdatedState(onNext)
    val currentOnOpenLyricsSettings by rememberUpdatedState(onOpenLyricsSettings)
    val hapticFeedback = LocalHapticFeedback.current
    val thresholdPx = with(LocalDensity.current) { 48.dp.toPx() }
    val toggleLabel = stringResource(if (playWhenReady) R.string.pause else R.string.play)
    val previousLabel = stringResource(R.string.previous_track)
    val nextLabel = stringResource(R.string.next_track)
    val settingsLabel = stringResource(R.string.player_settings_open)
    if (!enabled) return Modifier

    fun performWithHaptic(action: () -> Unit) {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
        action()
    }

    return Modifier
        .semantics(mergeDescendants = true) {
            onClick(label = toggleLabel) {
                performWithHaptic(currentOnTogglePlayPause)
                true
            }
            customActions = listOf(
                CustomAccessibilityAction(previousLabel) {
                    performWithHaptic(currentOnPrevious)
                    true
                },
                CustomAccessibilityAction(nextLabel) {
                    performWithHaptic(currentOnNext)
                    true
                },
                CustomAccessibilityAction(settingsLabel) {
                    performWithHaptic(currentOnOpenLyricsSettings)
                    true
                },
            )
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = { performWithHaptic(currentOnTogglePlayPause) },
                onLongPress = { performWithHaptic(currentOnOpenLyricsSettings) },
            )
        }
        .pointerInput(thresholdPx) {
            var distancePx = 0f
            detectHorizontalDragGestures(
                onDragStart = { distancePx = 0f },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    distancePx += dragAmount
                },
                onDragCancel = { distancePx = 0f },
                onDragEnd = {
                    when (playerHeaderSwipeDirection(distancePx, thresholdPx)) {
                        -1 -> performWithHaptic(currentOnPrevious)
                        1 -> performWithHaptic(currentOnNext)
                    }
                    distancePx = 0f
                },
            )
        }
}

internal fun playerHeaderSwipeDirection(distancePx: Float, thresholdPx: Float): Int = when {
    distancePx >= thresholdPx -> -1
    distancePx <= -thresholdPx -> 1
    else -> 0
}
