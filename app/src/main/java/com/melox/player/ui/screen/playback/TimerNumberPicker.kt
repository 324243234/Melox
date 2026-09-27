// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// Adapted from Miuix 0.9.4 NumberPicker to expose the visible value at timer start.

package com.melox.player.ui.screen.playback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.NumberPickerDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

@Stable
internal class TimerNumberPickerState(initialValue: Int, val range: IntRange) {
    var position by mutableFloatStateOf(initialValue.coerceIn(range).toFloat())
        private set
    var inputLocked by mutableStateOf(false)
        private set
    var isScrolling by mutableStateOf(false)
        private set
    private var motion: Job? = null
    private var startTargetPosition: Float? = null

    val value: Int get() = valueAt(position.roundToInt())

    fun valueAt(index: Int): Int {
        val count = range.last - range.first + 1
        return range.first + ((index - range.first) % count + count) % count
    }

    fun beginDrag() {
        if (inputLocked) return
        motion?.cancel()
        isScrolling = true
    }

    fun dragBy(delta: Float, itemHeight: Int) {
        if (!inputLocked && itemHeight > 0) position -= delta / itemHeight
    }

    fun fling(scope: CoroutineScope, velocity: Float, onSettled: (Int) -> Unit) {
        if (inputLocked) return
        motion?.cancel()
        motion = scope.launch {
            val animation = Animatable(position)
            animation.animateDecay(velocity, exponentialDecay(frictionMultiplier = 2f)) {
                position = value
            }
            animation.animateTo(
                animation.value.roundToInt().toFloat(),
                spring(dampingRatio = 1f, stiffness = 400f),
            ) { position = value }
            position = value.toFloat()
            isScrolling = false
            onSettled(value)
        }
    }

    fun captureForStart(): Int {
        inputLocked = true
        motion?.cancel()
        motion = null
        isScrolling = false
        val target = position.roundToInt().toFloat()
        startTargetPosition = target
        return valueAt(target.toInt())
    }

    suspend fun alignForStart(): Int {
        val target = startTargetPosition ?: position.roundToInt().toFloat()
        if (position != target) {
            Animatable(position).animateTo(
                target,
                spring(dampingRatio = 1f, stiffness = 400f),
            ) { position = value }
        }
        position = target
        return valueAt(target.toInt())
    }

    fun unlock() {
        inputLocked = false
        startTargetPosition = null
    }
}

@Composable
internal fun TimerNumberPicker(
    state: TimerNumberPickerState,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentEnabled by rememberUpdatedState(enabled)
    val haptic = LocalHapticFeedback.current
    var itemHeightPx by remember { mutableIntStateOf(0) }
    val draggableState = rememberDraggableState { state.dragBy(it, itemHeightPx) }
    LaunchedEffect(state) {
        var previous = state.value
        snapshotFlow { state.value }.distinctUntilChanged().collect {
            if (it != previous && state.isScrolling) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            previous = it
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NumberPickerDefaults.ItemHeight * 3)
            .clipToBounds()
            .semantics {
                contentDescription = "${state.value.toString().padStart(2, '0')}, " +
                    "${state.range.first} - ${state.range.last}"
            }
            .onSizeChanged { itemHeightPx = it.height / 3 }
            .draggable(
                orientation = Orientation.Vertical,
                state = draggableState,
                enabled = enabled && !state.inputLocked,
                onDragStarted = { state.beginDrag() },
                onDragStopped = { velocity ->
                    if (currentEnabled && itemHeightPx > 0) {
                        state.fling(scope, -velocity / itemHeightPx) { currentOnValueChange(it) }
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (itemHeightPx > 0) {
            val roundedPosition = state.position.roundToInt()
            val centerOffset = state.position - roundedPosition
            val selectedColor = MiuixTheme.colorScheme.onSurface
            val unselectedColor = MiuixTheme.colorScheme.onSurfaceSecondary
            val textStyle = MiuixTheme.textStyles.title1.let {
                if (it.fontWeight == null) it.copy(fontWeight = FontWeight.SemiBold) else it
            }
            for (i in -2..2) {
                val distance = i - centerOffset
                val normalizedDistance = (abs(distance) / 1.5f).coerceIn(0f, 1f)
                val alpha = (1f - normalizedDistance) * (1f - normalizedDistance * 0.5f)
                val scale = 1f - 0.2f * normalizedDistance
                Text(
                    text = state.valueAt(roundedPosition + i).toString().padStart(2, '0'),
                    modifier = Modifier.graphicsLayer {
                        this.alpha = alpha
                        scaleX = scale
                        scaleY = scale
                        translationY = distance * itemHeightPx
                    },
                    style = textStyle,
                    color = Color(
                        red = lerp(selectedColor.red, unselectedColor.red, normalizedDistance),
                        green = lerp(selectedColor.green, unselectedColor.green, normalizedDistance),
                        blue = lerp(selectedColor.blue, unselectedColor.blue, normalizedDistance),
                        alpha = lerp(selectedColor.alpha, unselectedColor.alpha, normalizedDistance),
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
