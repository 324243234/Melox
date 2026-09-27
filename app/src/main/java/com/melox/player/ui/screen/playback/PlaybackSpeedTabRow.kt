package com.melox.player.ui.screen.playback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.melox.player.ui.component.bottomSheetCardColor
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PlaybackSpeedTabRow(
    tabs: List<String>,
    selectedTabIndex: Int,
    enabled: Boolean,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val indicatorIndex = remember { Animatable(selectedTabIndex.toFloat()) }
    LaunchedEffect(selectedTabIndex) {
        indicatorIndex.animateTo(selectedTabIndex.toFloat(), tween(200, easing = LinearEasing))
    }
    val textLayer = rememberGraphicsLayer()
    val neutralText = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary
        else MiuixTheme.colorScheme.disabledOnSurface
    val selectedText = if (enabled) MiuixTheme.colorScheme.onPrimary
        else MiuixTheme.colorScheme.disabledOnPrimary
    val indicatorColor = if (enabled) MiuixTheme.colorScheme.primary
        else MiuixTheme.colorScheme.disabledPrimary
    val neutralPaint = remember(neutralText) {
        Paint().apply { colorFilter = ColorFilter.tint(neutralText) }
    }
    val selectedPaint = remember(selectedText) {
        Paint().apply { colorFilter = ColorFilter.tint(selectedText) }
    }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val padding = 5.dp
    val paddingPx = with(density) { padding.roundToPx() }
    val itemWidth by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0 }
    }
    val indicatorX = {
        val layout = listState.layoutInfo
        val first = layout.visibleItemsInfo.firstOrNull()
        if (first == null) {
            paddingPx
        } else {
            // Use actual measured pixels, including scroll and item spacing.
            val logicalX = first.offset + (indicatorIndex.value - first.index) *
                (first.size + layout.mainAxisItemSpacing)
            val x = if (layoutDirection == LayoutDirection.Rtl) {
                layout.viewportSize.width - logicalX - first.size
            } else {
                logicalX
            }
            paddingPx + x.roundToInt()
        }
    }
    val cornerRadius = TabRowDefaults.TabRowWithContourCornerRadius
    Card(
        modifier = modifier,
        cornerRadius = cornerRadius + padding,
        colors = CardDefaults.defaultColors(color = bottomSheetCardColor()),
    ) {
        Box {
            TabRowWithContour(
                tabs = tabs,
                selectedTabIndex = selectedTabIndex,
                onTabSelected = { if (enabled) onTabSelected(it) },
                listState = listState,
                colors = TabRowDefaults.tabRowColors(
                    backgroundColor = Color.Transparent,
                    selectedBackgroundColor = Color.Transparent,
                    contentColor = neutralText,
                    selectedContentColor = selectedText,
                ),
                modifier = Modifier.drawWithContent {
                    // Both color passes reuse the exact same glyphs and placement.
                    textLayer.record { this@drawWithContent.drawContent() }
                    val canvas = drawContext.canvas
                    canvas.saveLayer(Rect(Offset.Zero, size), neutralPaint)
                    drawLayer(textLayer)
                    canvas.restore()
                },
            )
            if (itemWidth > 0) {
                Box(
                    modifier = Modifier
                        .absoluteOffset { IntOffset(indicatorX(), paddingPx) }
                        .width(with(density) { itemWidth.toDp() })
                        .height(TabRowDefaults.TabRowWithContourHeight - padding * 2)
                        .squircleSurface(indicatorColor, cornerRadius)
                        .drawWithContent {
                            val canvas = drawContext.canvas
                            canvas.saveLayer(Rect(Offset.Zero, size), selectedPaint)
                            translate(-indicatorX().toFloat(), -paddingPx.toFloat()) {
                                drawLayer(textLayer)
                            }
                            canvas.restore()
                        },
                )
            }
            if (!enabled) {
                Box(
                    Modifier.matchParentSize()
                        .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                        .semantics { disabled() },
                )
            }
        }
    }
}
