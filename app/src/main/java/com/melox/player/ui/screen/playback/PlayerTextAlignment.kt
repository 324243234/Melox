package com.melox.player.ui.screen.playback

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult

internal const val PLAYER_TEXT_ALIGNMENT_DURATION_MS = 240

internal fun playerTextAlignmentSpec() = tween<Float>(
    durationMillis = PLAYER_TEXT_ALIGNMENT_DURATION_MS,
    easing = EaseInOut,
)

internal fun playerTextAlignmentOffset(
    lineLeft: Float,
    lineRight: Float,
    containerWidth: Float,
    progress: Float,
): Float {
    if (progress == 0f) return 0f
    val lineWidth = lineRight - lineLeft
    return ((containerWidth - lineWidth) / 2f - lineLeft) * progress
}

internal fun playerMarqueeAlignmentWidth(viewportWidth: Float, lineWidth: Float): Float =
    maxOf(viewportWidth, lineWidth)

internal fun Modifier.animatedPlayerTextAlignment(
    layout: TextLayoutResult?,
    progress: State<Float>,
    viewportInsetPx: Float = 0f,
): Modifier = drawWithContent {
    if (layout == null || layout.lineCount == 0) {
        drawContent()
    } else {
        repeat(layout.lineCount) { lineIndex ->
            val lineLeft = layout.getLineLeft(lineIndex)
            val lineRight = layout.getLineRight(lineIndex)
            val lineWidth = lineRight - lineLeft
            val offset = playerTextAlignmentOffset(
                lineLeft = lineLeft,
                lineRight = lineRight,
                containerWidth = if (viewportInsetPx > 0f) {
                    playerMarqueeAlignmentWidth(size.width - viewportInsetPx, lineWidth)
                } else {
                    size.width
                },
                progress = progress.value,
            )
            withTransform({ translate(left = offset) }) {
                clipRect(
                    left = -size.width,
                    top = layout.getLineTop(lineIndex),
                    right = size.width * 2f,
                    bottom = layout.getLineBottom(lineIndex),
                ) {
                    this@drawWithContent.drawContent()
                }
            }
        }
    }
}
