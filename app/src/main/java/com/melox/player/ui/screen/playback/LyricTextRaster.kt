package com.melox.player.ui.screen.playback

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A shaped paragraph drawn once; fractional translation filters its fixed glyph coverage. */
internal class LyricTextRaster(
    val image: ImageBitmap,
    val padding: Int,
) {
    val byteCount: Long get() = image.width.toLong() * image.height * 4L

    fun draw(scope: DrawScope, position: Offset, left: Float, right: Float, alpha: Float = 1f) {
        draw(scope, position, Rect(left, -padding.toFloat(), right, image.height - padding.toFloat()), alpha)
    }

    fun draw(scope: DrawScope, position: Offset, bounds: Rect, alpha: Float = 1f) {
        val start = (bounds.left + padding).roundToInt().coerceIn(0, image.width)
        val end = (bounds.right + padding).roundToInt().coerceIn(start, image.width)
        val top = (bounds.top + padding).roundToInt().coerceIn(0, image.height)
        val bottom = (bounds.bottom + padding).roundToInt().coerceIn(top, image.height)
        if (end <= start || bottom <= top) return
        with(scope) {
            translate(position.x + start - padding, position.y + top - padding) {
                drawImage(
                    image = image,
                    srcOffset = IntOffset(start, top),
                    srcSize = IntSize(end - start, bottom - top),
                    dstSize = IntSize(end - start, bottom - top),
                    alpha = alpha,
                    filterQuality = FilterQuality.Low,
                )
            }
        }
    }
}

internal fun prepareLyricTextRaster(
    layout: TextLayoutResult,
    color: Color,
    density: Density,
    layoutDirection: LayoutDirection,
    maxBytes: Long,
): LyricTextRaster? {
    // The foreground needs ink overhang only; glow allocates its own expanded coverage.
    val lineHeight = (0 until layout.lineCount).maxOfOrNull {
        layout.getLineBottom(it) - layout.getLineTop(it)
    } ?: layout.size.height.toFloat()
    val overhang = (0 until layout.lineCount).maxOfOrNull {
        maxOf(-layout.getLineLeft(it), layout.getLineRight(it) - layout.size.width, 0f)
    } ?: 0f
    val padding = ceil(lineHeight * 0.12f + 2f + overhang).toInt()
    val width = layout.size.width + padding * 2
    val height = layout.size.height + padding * 2
    if (width <= 0 || height <= 0 || width > 4096 || height > 4096 ||
        width.toLong() * height * 4L > maxBytes
    ) return null
    val image = ImageBitmap(width, height)
    CanvasDrawScope().draw(density, layoutDirection, Canvas(image), Size(width.toFloat(), height.toFloat())) {
        drawText(layout, color = color, topLeft = Offset(padding.toFloat(), padding.toFloat()), shadow = Shadow.None)
    }
    return LyricTextRaster(image, padding)
}

internal fun DrawScope.drawLyricRasterOrText(
    layout: TextLayoutResult,
    raster: LyricTextRaster?,
    color: Color,
    position: Offset,
    bounds: Rect,
    alpha: Float = 1f,
) {
    if (raster != null) {
        raster.draw(this, position, bounds, alpha)
    } else {
        clipRect(position.x + bounds.left, position.y + bounds.top,
            position.x + bounds.right, position.y + bounds.bottom) {
            drawLyricForeground(layout, color.copy(alpha = color.alpha * alpha), position)
        }
    }
}

internal class LyricGlyphGlow(val image: ImageBitmap, private val offset: Offset, private val color: Color) {
    val byteCount: Long get() = image.width.toLong() * image.height * 4L
    val bounds = Rect(offset, Size(image.width.toFloat(), image.height.toFloat()))

    fun translated(delta: Offset): LyricGlyphGlow = LyricGlyphGlow(image, offset + delta, color)

    fun draw(scope: DrawScope, position: Offset, alpha: Float) {
        with(scope) {
            translate(position.x + offset.x, position.y + offset.y) {
                drawImage(image, alpha = alpha, colorFilter = ColorFilter.tint(color), filterQuality = FilterQuality.Low)
            }
        }
    }
}

/** Blur the isolated shaped coverage; this also works on API 28-30 without RenderEffect. */
internal fun prepareLyricGlyphGlow(
    raster: LyricTextRaster,
    left: Float,
    right: Float,
    radiusPx: Float,
    color: Color,
    maxBytes: Long,
): LyricGlyphGlow? {
    return prepareLyricGlyphGlow(raster,
        Rect(left, -raster.padding.toFloat(), right, raster.image.height - raster.padding.toFloat()),
        radiusPx, color, maxBytes)
}

internal fun prepareLyricGlyphGlow(
    raster: LyricTextRaster,
    bounds: Rect,
    radiusPx: Float,
    color: Color,
    maxBytes: Long,
): LyricGlyphGlow? {
    if (radiusPx <= 0f) return null
    val start = (bounds.left + raster.padding).roundToInt().coerceIn(0, raster.image.width)
    val end = (bounds.right + raster.padding).roundToInt().coerceIn(start, raster.image.width)
    val top = (bounds.top + raster.padding).roundToInt().coerceIn(0, raster.image.height)
    val bottom = (bounds.bottom + raster.padding).roundToInt().coerceIn(top, raster.image.height)
    val blurOutset = ceil(radiusPx * 3f).toInt()
    if (end <= start || bottom <= top || (end - start + blurOutset * 2L) *
        (bottom - top + blurOutset * 2L) * 4L > maxBytes
    ) return null
    val original = raster.image.asAndroidBitmap()
    val slice = Bitmap.createBitmap(original, start, top, end - start, bottom - top)
    val offset = IntArray(2)
    val coverage = slice.extractAlpha(android.graphics.Paint().apply {
        maskFilter = BlurMaskFilter(radiusPx, BlurMaskFilter.Blur.NORMAL)
    }, offset)
    if (slice !== original) slice.recycle()
    return LyricGlyphGlow(coverage.asImageBitmap(),
        Offset((start - raster.padding + offset[0]).toFloat(), (top - raster.padding + offset[1]).toFloat()), color)
}
