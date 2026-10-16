package com.melox.player.ui.screen.playback

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics

@Composable
internal fun Modifier.playerChromeVisibility(visible: Boolean): Modifier {
    // Callers disable input immediately while this layer preserves measured space.
    val contentAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(200, easing = LinearOutSlowInEasing),
        label = "playerChromeAlpha",
    )
    return graphicsLayer { alpha = contentAlpha }
        .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})
}
