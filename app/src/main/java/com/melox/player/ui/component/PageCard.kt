package com.melox.player.ui.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun PageCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CardDefaults.CornerRadius,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    colors: CardColors = CardDefaults.defaultColors(),
    pressFeedbackType: PressFeedbackType = PressFeedbackType.None,
    showIndication: Boolean = false,
    holdDownState: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val backdrop = LocalPageSurfaceBackdrop.current.takeIf { colors.color.alpha > 0f }
    val blurRadius = LocalPageCardBlurRadius.current
    val surfaceAlpha = LocalPageCardSurfaceAlpha.current
    val backgroundColor = pageCardBackgroundColor(
        color = colors.color,
        hasWallpaper = LocalCustomPageBackground.current != null,
        hasBackdrop = backdrop != null,
        surfaceAlpha = surfaceAlpha,
    )
    MiuixCard(
        modifier = modifier,
        cornerRadius = cornerRadius,
        insideMargin = if (backdrop == null) insideMargin else PaddingValues(0.dp),
        colors = colors.copy(color = backgroundColor),
        pressFeedbackType = pressFeedbackType,
        showIndication = showIndication,
        holdDownState = holdDownState,
        onClick = onClick,
        onLongPress = onLongPress,
    ) {
        if (backdrop == null) {
            content()
        } else {
            // Keep the material inside Miuix's native shape and press-feedback layer.
            Column(
                modifier = Modifier.fillMaxWidth()
                    .pageTextureBlur(backdrop, blurRadius = blurRadius, surfaceAlpha = surfaceAlpha)
                    .padding(insideMargin),
                content = content,
            )
        }
    }
}

internal fun pageCardBackgroundColor(
    color: Color,
    hasWallpaper: Boolean,
    hasBackdrop: Boolean,
    surfaceAlpha: Float,
): Color = when {
    color.alpha <= 0f -> color
    hasBackdrop -> Color.Transparent
    hasWallpaper -> color.copy(alpha = color.alpha * surfaceAlpha.coerceIn(0f, 1f))
    else -> color
}

@Composable
internal fun Modifier.pageSurfaceBlur(cornerRadius: Dp): Modifier {
    val backdrop = LocalPageSurfaceBackdrop.current ?: return this
    return squircleClip(cornerRadius).pageTextureBlur(backdrop)
}
