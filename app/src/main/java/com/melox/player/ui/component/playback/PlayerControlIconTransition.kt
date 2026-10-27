package com.melox.player.ui.component.playback

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith

internal fun AnimatedContentTransitionScope<*>.playerControlIconTransition() =
    (
        fadeIn(tween(180, delayMillis = 130, easing = LinearOutSlowInEasing)) +
            scaleIn(tween(180, delayMillis = 130, easing = LinearOutSlowInEasing), initialScale = 0.6f)
    ).togetherWith(
        fadeOut(tween(120, delayMillis = 20, easing = LinearEasing)) +
            scaleOut(tween(140, easing = LinearOutSlowInEasing), targetScale = 0.5f),
    ).using(SizeTransform(clip = false))
