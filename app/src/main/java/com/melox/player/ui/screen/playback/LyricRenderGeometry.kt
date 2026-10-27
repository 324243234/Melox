package com.melox.player.ui.screen.playback

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.cos

/** Inactive rows use stable endpoints without changing state when they acquire focus. */
internal fun lyricStableRenderPositionMs(live: Boolean, completed: Boolean, positionMs: Long): Long =
    if (live) positionMs else if (completed) Long.MAX_VALUE else Long.MIN_VALUE

internal fun lyricLayerOutsets(bounds: Rect, width: Float, height: Float, density: Density): LayerOutsets =
    with(density) {
        LayerOutsets(
            // Dp orders -0 below +0; negating an exact zero must not reach LayerOutsets.
            left = if (bounds.left < 0f) (-bounds.left).toDp() else 0.dp,
            right = if (bounds.right > width) (bounds.right - width).toDp() else 0.dp,
            top = if (bounds.top < 0f) (-bounds.top).toDp() else 0.dp,
            bottom = if (bounds.bottom > height) (bounds.bottom - height).toDp() else 0.dp,
        )
    }

internal fun lyricForegroundFadeWidthPx(fontSizePx: Float): Float = fontSizePx.coerceIn(1f, 36f)

// Preserve the previous 4 px lift at the common 24 sp / 3x density while scaling typography.
internal fun lyricEffectGeometryScale(fontSizePx: Float): Float = (fontSizePx / 72f).coerceAtLeast(0f)

/** Shaping/eligibility count graphemes; split timestamps retain the original UTF-16 timing units. */
internal fun lyricChunkTimeMs(startMs: Long, endMs: Long, sourceLength: Int, offset: Int): Long =
    if (sourceLength <= 0) startMs else startMs + (endMs - startMs) * offset / sourceLength

internal data class LyricFadeBounds(val startX: Float, val endX: Float)

internal data class LyricTimedDistance(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val start: Float,
    val end: Float,
)

/** One clock follows the original timing tags through the shaped paragraph's advances. */
internal class LyricParagraphClock(private val spans: List<LyricTimedDistance>) {
    private val cursor = LyricTimingCursor(
        spans.map { it.startTimeMs }.toLongArray(), spans.map { it.endTimeMs }.toLongArray(),
    )

    fun distanceAt(positionMs: Long): Float {
        val span = spans.getOrNull(cursor.indexAt(positionMs)) ?: return spans.lastOrNull()?.end ?: 0f
        return span.start + (span.end - span.start) *
            lyricIntervalProgress(positionMs, span.startTimeMs, span.endTimeMs)
    }

    fun progressBetween(positionMs: Long, start: Float, end: Float): Float {
        val distance = distanceAt(positionMs)
        return if (end > start) ((distance - start) / (end - start)).coerceIn(0f, 1f)
        else if (distance >= end) 1f else 0f
    }
}

/** Visual direction is independent of logical playback order, including mixed-direction rows. */
internal data class LyricRevealPiece(
    val startX: Float,
    val endX: Float,
    val startDistance: Float,
    val endDistance: Float,
)

/** Map logical timing distance to the shaped run, without assuming left-to-right text. */
internal fun lyricRevealPosition(pieces: List<LyricRevealPiece>, distance: Float): Float {
    val piece = pieces.firstOrNull { distance < it.endDistance && it.endDistance > it.startDistance }
        ?: return pieces.lastOrNull()?.endX ?: 0f
    val progress = ((distance - piece.startDistance) / (piece.endDistance - piece.startDistance))
        .coerceIn(0f, 1f)
    return piece.startX + (piece.endX - piece.startX) * progress
}

/** Reveal logical pronunciation order within each visual row, including mixed directions. */
internal fun lyricRevealClipRects(
    pieces: List<LyricRevealPiece>,
    progress: Float,
    top: Float,
    bottom: Float,
): List<Rect> {
    if (pieces.isEmpty() || progress <= 0f) return emptyList()
    val start = pieces.first().startDistance
    val end = pieces.last().endDistance
    val distance = start + (end - start) * progress.coerceAtMost(1f)
    return pieces.mapNotNull { piece ->
        val length = piece.endDistance - piece.startDistance
        if (length <= 0f || distance <= piece.startDistance) return@mapNotNull null
        val fraction = ((distance - piece.startDistance) / length).coerceAtMost(1f)
        val edge = piece.startX + (piece.endX - piece.startX) * fraction
        Rect(minOf(piece.startX, edge), top, maxOf(piece.startX, edge), bottom)
    }
}

internal fun lyricRevealMaskStops(
    pieces: List<LyricRevealPiece>,
    fade: LyricFadeBounds,
    activeAlpha: Float,
    inactiveAlpha: Float,
): List<Pair<Float, Float>> = pieces.sortedBy { minOf(it.startX, it.endX) }.flatMap { piece ->
    val length = piece.endDistance - piece.startDistance
    if (length <= 0f || piece.startX == piece.endX) return@flatMap emptyList()
    val distances = listOf(piece.startDistance, fade.startX, fade.endX, piece.endDistance)
        .filter { it >= piece.startDistance && it <= piece.endDistance }.distinct()
    distances.map { distance ->
        val fraction = (distance - piece.startDistance) / length
        val x = piece.startX + (piece.endX - piece.startX) * fraction
        val progress = if (fade.endX > fade.startX) {
            ((distance - fade.startX) / (fade.endX - fade.startX)).coerceIn(0f, 1f)
        } else if (distance <= fade.startX) 0f else 1f
        val alpha = when {
            progress <= 0f -> activeAlpha
            progress >= 1f -> inactiveAlpha
            else -> activeAlpha + (inactiveAlpha - activeAlpha) * progress
        }
        x to alpha
    }.sortedBy { it.first }
}

internal fun Rect.lyricUnion(other: Rect): Rect = Rect(
    minOf(left, other.left), minOf(top, other.top),
    maxOf(right, other.right), maxOf(bottom, other.bottom),
)

/** A unit finishes rising exactly when the opaque edge has passed its shaped bounds. */
internal fun lyricGlyphRevealProgress(left: Float, right: Float, fade: LyricFadeBounds): Float {
    val width = (right - left).coerceAtLeast(0f)
    val fadeWidth = (fade.endX - fade.startX).coerceAtLeast(0f)
    if (width + fadeWidth <= 0f) return if (fade.startX >= right) 1f else 0f
    return ((fade.endX - left) / (width + fadeWidth)).coerceIn(0f, 1f)
}

internal fun lyricRevealFloatOffset(progress: Float): Float =
    -LYRIC_FLOAT_MAX_OFFSET_PX * (1f - cos(progress.coerceIn(0f, 1f) * Math.PI).toFloat()) / 2f

/** Include rest and maximum transformed coverage, independently of list layout spacing. */
internal fun lyricGlyphVisualBounds(bounds: Rect, pivot: Offset, maxScale: Float, liftPx: Float): Rect {
    val scale = maxScale.coerceAtLeast(1f)
    return Rect(
        minOf(bounds.left, pivot.x + (bounds.left - pivot.x) * scale),
        minOf(bounds.top, pivot.y + (bounds.top - pivot.y) * scale) - liftPx.coerceAtLeast(0f),
        maxOf(bounds.right, pivot.x + (bounds.right - pivot.x) * scale),
        maxOf(bounds.bottom, pivot.y + (bounds.bottom - pivot.y) * scale),
    )
}

/** Match the original clipped stops, including the shrinking fade at the row's first/last pixels. */
internal fun lyricFadeBounds(minX: Float, maxX: Float, pixelPosition: Float, fadeWidthPx: Float): LyricFadeBounds {
    val width = maxX - minX
    if (width <= 0f) return LyricFadeBounds(minX, minX)
    val fadeWidth = fadeWidthPx.coerceIn(0f, width)
    val progress = ((pixelPosition - minX) / width).coerceIn(0f, 1f)
    val end = (width + fadeWidth) * progress
    return LyricFadeBounds(minX + (end - fadeWidth).coerceIn(0f, width),
        minX + end.coerceIn(0f, width))
}

internal data class LyricWordEffects(val glow: Boolean, val scale: Boolean)

internal fun lyricWordEffects(
    content: String,
    durationMs: Long,
    hasNativeWordTiming: Boolean = true,
): LyricWordEffects {
    if (!hasNativeWordTiming) return LyricWordEffects(false, false)
    val visible = lyricGraphemeRanges(content).map { content.substring(it) }.filter { grapheme ->
        grapheme.codePoints().anyMatch { code ->
            !Character.isWhitespace(code) && Character.getType(code) !in punctuationTypes
        }
    }
    if (visible.isEmpty() || durationMs < 1_000L) return LyricWordEffects(false, false)
    val cjk = visible.all { grapheme ->
        grapheme.codePoints().allMatch { code ->
            code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF ||
                code in 0xF900..0xFAFF || code in 0x20000..0x323AF ||
                Character.getType(code) == Character.NON_SPACING_MARK.toInt()
        }
    }
    val eligible = cjk || durationMs.toDouble() / visible.size > 200.0
    return LyricWordEffects(glow = eligible, scale = eligible)
}

internal fun lyricUsesJoinedGlyphs(content: String): Boolean = content.codePoints().anyMatch { code ->
        val script = Character.UnicodeScript.of(code)
        // ADLAM's enum field is absent before API 34; the name remains safe on every runtime.
        script in joinedScripts || script.name == "ADLAM"
}

private val joinedScripts = setOf(
    Character.UnicodeScript.ARABIC, Character.UnicodeScript.SYRIAC,
    Character.UnicodeScript.NKO, Character.UnicodeScript.MANDAIC,
    Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.BENGALI,
    Character.UnicodeScript.GURMUKHI, Character.UnicodeScript.GUJARATI,
    Character.UnicodeScript.ORIYA, Character.UnicodeScript.TAMIL,
    Character.UnicodeScript.TELUGU, Character.UnicodeScript.KANNADA,
    Character.UnicodeScript.MALAYALAM, Character.UnicodeScript.SINHALA,
    Character.UnicodeScript.THAI, Character.UnicodeScript.LAO,
    Character.UnicodeScript.MYANMAR, Character.UnicodeScript.KHMER,
    Character.UnicodeScript.TIBETAN,
)

private val punctuationTypes = setOf(
    Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt(),
)

/** Forward playback advances once per word; backward seeks find the first possible live interval. */
internal class LyricTimingCursor(private val starts: LongArray, private val ends: LongArray) {
    private val prefixEnds = LongArray(ends.size)
    private var index = 0
    private var previousPosition = Long.MIN_VALUE

    init {
        require(starts.size == ends.size)
        ends.indices.forEach { prefixEnds[it] = maxOf(ends[it], prefixEnds.getOrElse(it - 1) { Long.MIN_VALUE }) }
    }

    fun indexAt(positionMs: Long): Int {
        if (positionMs < previousPosition) {
            var low = 0
            var high = ends.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (prefixEnds[middle] <= positionMs) low = middle + 1 else high = middle
            }
            index = low
        }
        while (index < ends.size && prefixEnds[index] <= positionMs) index++
        previousPosition = positionMs
        var active = index
        while (active < ends.size && starts[active] <= positionMs) {
            if (positionMs < ends[active]) return active
            active++
        }
        return index.coerceAtMost(ends.size)
    }
}
