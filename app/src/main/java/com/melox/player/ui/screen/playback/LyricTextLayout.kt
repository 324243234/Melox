package com.melox.player.ui.screen.playback

import java.text.BreakIterator
import java.util.Locale

/** Splits only oversized spans, preserving natural line breaks and grapheme boundaries. */
internal fun lyricTextChunks(
    text: String,
    availableWidthPx: Float,
    measureWidth: (String) -> Float,
): List<IntRange> {
    if (text.isEmpty()) return emptyList()
    val width = availableWidthPx.coerceAtLeast(1f)
    if (measureWidth(text) <= width) return listOf(text.indices)
    val characters = textBoundaries(text, BreakIterator.getCharacterInstance(Locale.ROOT))
    val lineBreaks = textBoundaries(text, BreakIterator.getLineInstance(Locale.ROOT)).toSet()
    val result = mutableListOf<IntRange>()
    var startIndex = 0
    while (startIndex < characters.lastIndex) {
        var endIndex = startIndex + 1
        var naturalEndIndex = -1
        while (endIndex < characters.lastIndex) {
            if (characters[endIndex] in lineBreaks) naturalEndIndex = endIndex
            if (measureWidth(text.substring(characters[startIndex], characters[endIndex + 1])) > width) {
                break
            }
            endIndex++
        }
        if (endIndex < characters.lastIndex && naturalEndIndex > startIndex) {
            endIndex = naturalEndIndex
        }
        // Trailing whitespace has no ink and is trimmed at the visual line end.
        // Keep it with the preceding text rather than creating an empty wrapped row.
        while (
            endIndex < characters.lastIndex &&
                text.substring(characters[endIndex], characters[endIndex + 1]).isBlank()
        ) {
            endIndex++
        }
        result += characters[startIndex] until characters[endIndex]
        startIndex = endIndex
    }
    return result
}

/** Fills each row before wrapping; overlong groups can break at measured span boundaries. */
internal fun lyricWrapRanges(
    contents: List<String>,
    widths: List<Float>,
    availableWidthPx: Float,
    lineEndWidths: List<Float> = widths,
): List<IntRange> {
    require(contents.size == widths.size && contents.size == lineEndWidths.size)
    if (contents.isEmpty()) return emptyList()
    val width = availableWidthPx.coerceAtLeast(1f)
    val boundaries = textBoundaries(
        contents.joinToString(""),
        BreakIterator.getLineInstance(Locale.ROOT),
    ).toSet()
    val allowed = BooleanArray(contents.size + 1)
    allowed[0] = true
    allowed[contents.size] = true
    var offset = 0
    contents.forEachIndexed { index, content ->
        offset += content.length
        if (offset in boundaries || content.lastOrNull()?.isWhitespace() == true) {
            allowed[index + 1] = true
        }
    }
    var groupStart = 0
    var groupWidth = 0f
    widths.forEachIndexed { index, itemWidth ->
        groupWidth += itemWidth
        if (allowed[index + 1]) {
            if (groupWidth - itemWidth + lineEndWidths[index] > width) {
                for (boundary in groupStart + 1..index) allowed[boundary] = true
            }
            groupStart = index + 1
            groupWidth = 0f
        }
    }
    val result = mutableListOf<IntRange>()
    var start = 0
    while (start < contents.size) {
        var advanceWidth = 0f
        var fittingEnd = start
        for (end in start + 1..contents.size) {
            val index = end - 1
            val visibleWidth = advanceWidth + lineEndWidths[index]
            if (visibleWidth > width) break
            advanceWidth += widths[index]
            if (allowed[end]) fittingEnd = end
        }
        // A single oversized grapheme still occupies one row and makes progress.
        if (fittingEnd == start) fittingEnd = start + 1
        result += start until fittingEnd
        start = fittingEnd
    }
    return result
}

private fun textBoundaries(text: String, iterator: BreakIterator): List<Int> {
    iterator.setText(text)
    return buildList {
        var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) {
            add(boundary)
            boundary = iterator.next()
        }
    }
}
