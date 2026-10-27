package com.melox.player.ui.component.playback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal class TrackSkipIconMotion {
    val triangle = Animatable(0f, visibilityThreshold = 0.01f)
    val bar = Animatable(0f, visibilityThreshold = 0.01f)
    val scale = Animatable(1f, visibilityThreshold = 0.001f)
    val barHeight = Animatable(1f, visibilityThreshold = 0.001f)
    private var barVelocity = 0f

    suspend fun play() = coroutineScope {
        launch {
            triangle.animateTo(1f, tween(60, easing = LinearOutSlowInEasing))
            triangle.animateTo(0f, tween(300, easing = LinearOutSlowInEasing))
        }
        launch {
            bar.animateTo(
                1f,
                tween(100, delayMillis = if (bar.value == 0f) 60 else 0, easing = LinearEasing),
                initialVelocity = barVelocity,
            ) { barVelocity = velocity }
            bar.animateTo(
                0f,
                spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = 0.01f),
                initialVelocity = barVelocity,
            ) { barVelocity = velocity }
            barVelocity = 0f
        }
        launch {
            scale.animateTo(0.82f, tween(100, easing = FastOutSlowInEasing))
            scale.animateTo(1f, spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = 0.001f))
        }
        launch {
            barHeight.animateTo(
                0.84f,
                tween(160, delayMillis = if (barHeight.value == 1f) 60 else 0, easing = FastOutSlowInEasing),
            )
            barHeight.animateTo(
                1f,
                spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = 0.001f),
            )
        }
    }
}

internal const val TRACK_SKIP_GLYPH_WIDTH = 22.3531f - 1.875f
internal const val TRACK_SKIP_TRIANGLE_TRAVEL = TRACK_SKIP_GLYPH_WIDTH * 0.13f
internal const val TRACK_SKIP_BAR_TRAVEL = TRACK_SKIP_GLYPH_WIDTH * 0.36f
