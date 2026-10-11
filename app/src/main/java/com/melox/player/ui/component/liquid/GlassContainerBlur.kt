package com.melox.player.ui.component.liquid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
internal fun rememberGlassContainerBlur(): BlurEffect {
    val density = LocalDensity.current
    return remember(density) {
        // Match Miuix v0.9.4's sigma at full resolution. Android converts its blur
        // radius to sigma with radius * 0.57735 + 0.5 (AOSP hwui/utils/Blur.cpp).
        val sigma = with(density) { 4.dp.toPx() } * 0.45f
        val radius = (sigma - 0.5f) / 0.57735f
        BlurEffect(radius, radius)
    }
}
