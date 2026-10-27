// SPDX-License-Identifier: Apache-2.0
package com.melox.player.ui.screen.playback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import com.melox.player.ui.component.playback.PLAYBACK_BACKGROUND_TRANSITION_DURATION_MILLIS
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal const val LYRIC_CONTENT_DURATION_MS = PLAYBACK_BACKGROUND_TRANSITION_DURATION_MILLIS
internal const val LYRIC_CONTENT_OVERLAP_MS = 100
internal const val LYRIC_CONTENT_PHASE_MS = (LYRIC_CONTENT_DURATION_MS + LYRIC_CONTENT_OVERLAP_MS) / 2
internal const val LYRIC_CONTENT_ENTER_DELAY_MS = LYRIC_CONTENT_DURATION_MS - LYRIC_CONTENT_PHASE_MS
internal const val LYRIC_CONTENT_MIN_SCALE = 0.92f

internal fun lyricContentEnterSpec(fromEmpty: Boolean = false) =
    if (fromEmpty) tween<Float>(LYRIC_CONTENT_DURATION_MS, easing = LinearOutSlowInEasing)
    else tween<Float>(LYRIC_CONTENT_PHASE_MS, LYRIC_CONTENT_ENTER_DELAY_MS, LinearOutSlowInEasing)

internal fun lyricContentExitSpec() = tween<Float>(LYRIC_CONTENT_PHASE_MS, easing = LinearEasing)

/** Shared by every row, so lazy-item creation never replays a translation entrance. */
internal class LyricTranslationMotion(initiallyVisible: Boolean) {
    val expansion = Animatable(if (initiallyVisible) 1f else 0f)
    val opacity = Animatable(if (initiallyVisible) 1f else 0f)
    private var expansionVelocity = 0f

    suspend fun transitionTo(
        visible: Boolean,
        expansionSpec: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 300f),
    ) = coroutineScope {
        val target = if (visible) 1f else 0f
        launch { fadeTo(target) }
        launch {
            if (expansion.value != target) {
                expansion.animateTo(target, expansionSpec, initialVelocity = expansionVelocity) {
                    expansionVelocity = velocity
                }
                expansionVelocity = 0f
            }
        }
    }

    suspend fun snapTo(visible: Boolean) {
        val target = if (visible) 1f else 0f
        expansionVelocity = 0f
        expansion.snapTo(target)
        opacity.snapTo(target)
    }

    private suspend fun fadeTo(target: Float) {
        if (opacity.value == target) return
        opacity.animateTo(target, tween(180, easing = LinearOutSlowInEasing))
    }
}

internal fun lyricTranslationPaddingDp(
    hasTranslation: Boolean,
    expansion: Float,
    lyricFontScale: Float = 1f,
): Float {
    val hidden = lyricLineVerticalPaddingDp(false, hasTranslation, false, lyricFontScale)
    val shown = lyricLineVerticalPaddingDp(false, hasTranslation, true, lyricFontScale)
    return hidden + (shown - hidden) * expansion.coerceIn(0f, 1f)
}
