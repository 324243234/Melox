package com.melox.player.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.offset
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.layout.BottomSheetDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class TopBarBlurSettings(
    val blurEnabled: Boolean,
    val progressiveEnabled: Boolean,
)

internal val LocalTopBarBlurSettings = compositionLocalOf<TopBarBlurSettings> {
    error("No TopBarBlurSettings provided")
}

internal val LocalBottomSheetBlurBackdrop = compositionLocalOf<LayerBackdrop?> { null }

@Composable
internal fun rememberBlurBackdrop(): LayerBackdrop? {
    val currentSettings = LocalTopBarBlurSettings.current
    if (!currentSettings.blurEnabled || !isRuntimeShaderSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

@Composable
internal fun LayerBackdrop?.miuixBarColor(): Color =
    if (this == null) MiuixTheme.colorScheme.surface else Color.Transparent

@Composable
internal fun bottomSheetMaterialColor(): Color =
    if (LocalBottomSheetBlurBackdrop.current == null) {
        BottomSheetDefaults.backgroundColor()
    } else {
        Color.Transparent
    }

@Composable
internal fun bottomSheetCardColor(): Color {
    if (LocalBottomSheetBlurBackdrop.current == null) {
        return MiuixTheme.colorScheme.secondaryContainer
    }
    return MiuixTheme.colorScheme.secondaryContainer.copy(
        alpha = BottomSheetCardMaterialAlpha,
    )
}

@Composable
internal fun bottomSheetDirectContentColor(): Color =
    if (LocalBottomSheetBlurBackdrop.current == null) {
        MiuixTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }

@Composable
internal fun bottomSheetGlassModifier(): Modifier {
    val backdrop = LocalBottomSheetBlurBackdrop.current ?: return Modifier
    val elasticExtensionPx =
        (LocalWindowInfo.current.containerSize.height / BottomSheetDragDampingDivisor)
            .coerceAtLeast(1)
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val highlight = if (isDark) {
        Highlight.GlassStrokeBigDark
    } else {
        Highlight.GlassStrokeBigLight
    }
    return Modifier
        .layout { measurable, constraints ->
            val placeable = measurable.measure(
                constraints.offset(vertical = elasticExtensionPx),
            )
            layout(placeable.width, placeable.height - elasticExtensionPx) {
                placeable.place(0, 0)
            }
        }
        .textureBlur(
            backdrop = backdrop,
            shape = RoundedCornerShape(
                topStart = BottomSheetDefaults.cornerRadius,
                topEnd = BottomSheetDefaults.cornerRadius,
            ),
            blurRadius = 100f,
            colors = BlurDefaults.blurColors(
                blendColors = listOf(
                    BlendColorEntry(
                        color = MiuixTheme.colorScheme.surface.copy(
                            alpha = BottomSheetMaterialAlpha,
                        ),
                    ),
                ),
            ),
            highlight = highlight,
        )
        .layout { measurable, constraints ->
            val placeable = measurable.measure(
                constraints.offset(vertical = -elasticExtensionPx),
            )
            layout(placeable.width, placeable.height + elasticExtensionPx) {
                placeable.place(0, 0)
            }
        }
}

private const val BottomSheetDragDampingDivisor = 10
private const val BottomSheetMaterialAlpha = 0.75f
private const val BottomSheetCardMaterialAlpha = 0.5f

@Composable
internal fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurEnabled: Boolean,
    scrollBehavior: ScrollBehavior? = null,
    surfaceColor: Color = MiuixTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    val progressive = LocalTopBarBlurSettings.current.progressiveEnabled
    val blurActive = blurEnabled && backdrop != null
    Box(
        modifier = if (blurActive && !progressive) {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = 25f,
                colors = barBlurColors(surfaceColor = surfaceColor),
            )
        } else {
            Modifier
        },
    ) {
        if (blurActive && progressive) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        gradient = ProgressiveBlur.Top.copy(
                            startFraction = 0.2f,
                            endFraction = 1f,
                            curve = 3f,
                        ),
                        blurRadius = 12f,
                        colors = barBlurColors(progressive = true, surfaceColor = surfaceColor),
                    ),
            )
        }
        content()
    }
}

@Composable
internal fun GaussianBlurredBar(
    backdrop: LayerBackdrop?,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = if (backdrop != null) {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = 25f,
                colors = barBlurColors(),
            )
        } else {
            Modifier
        },
    ) {
        content()
    }
}

@Composable
private fun barBlurColors(
    progressive: Boolean = false,
    surfaceColor: Color = MiuixTheme.colorScheme.surface,
): BlurColors = BlurDefaults.blurColors(
    blendColors = listOf(
        BlendColorEntry(color = surfaceColor.copy(if (progressive) 0.3f else 0.8f)),
    ),
)
