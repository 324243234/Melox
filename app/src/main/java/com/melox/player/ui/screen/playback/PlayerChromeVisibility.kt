package com.melox.player.ui.screen.playback

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics

private val PlayerChromeFadeInEasing = Easing { fraction ->
    1f - LinearOutSlowInEasing.transform(1f - fraction)
}

@Composable
internal fun playerChromeAlpha(visible: Boolean): State<Float> =
    animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (visible) 300 else 200,
            easing = if (visible) PlayerChromeFadeInEasing else LinearOutSlowInEasing,
        ),
        label = "playerChromeAlpha",
    )

@Composable
internal fun Modifier.playerChromeVisibility(visible: Boolean): Modifier {
    // Callers disable input immediately, including while the chrome fades out.
    val contentAlpha by playerChromeAlpha(visible)
    return graphicsLayer { alpha = contentAlpha }
        .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})
}
