package com.melox.player.ui.screen.playback

import android.os.Build
import java.text.BreakIterator
import java.util.Locale

private val cjkScripts = setOf(Character.UnicodeScript.HAN,
    Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)

internal data class LyricLayoutText(
    val text: String,
    val sourceOffsets: IntArray,
    val layoutOffsets: IntArray,
)

/** Insert display-only breaks while keeping every original timing offset addressable. */
internal fun lyricLayoutText(text: String, breaks: IntArray = intArrayOf()): LyricLayoutText {
    val selected = breaks.toSet()
    val output = StringBuilder(text.length + breaks.size)
    val sources = ArrayList<Int>(text.length + breaks.size + 1)
    val layouts = IntArray(text.length + 1)
    for (offset in 0..text.length) {
        if (offset in selected && offset > 0 && offset < text.length &&
            text[offset - 1] !in "\r\n" && text[offset] !in "\r\n"
        ) {
            sources.add(offset)
            output.append('\n')
        }
        layouts[offset] = output.length
        sources.add(offset)
        if (offset < text.length) output.append(text[offset])
    }
    return LyricLayoutText(output.toString(), sources.toIntArray(), layouts)
}

/** Wrap at safe Unicode boundaries, keeping fitting pronunciation groups indivisible. */
internal fun lyricAnnotationLineBreaks(
    text: String,
    availableWidthPx: Float,
    locale: Locale = Locale.getDefault(),
    safeOffsets: Set<Int>? = null,
    protectedRanges: List<IntRange> = emptyList(),
    measureWidth: (Int, Int) -> Float,
): IntArray? {
    if (text.isEmpty()) return intArrayOf(0)
    val legal = textBoundaries(text, BreakIterator.getLineInstance(locale)).toSet()
    val words = lyricWordBoundaries(text, locale).toSet()
    val wordRanges = lyricCjkWordRanges(text, words)
    val graphemes = lyricGraphemeRanges(text).map { it.last + 1 }
    var graphemeIndex = 0
    fun measuredWidth(start: Int, end: Int): Float {
        var visibleEnd = end
        while (visibleEnd > start && text[visibleEnd - 1].isWhitespace()) visibleEnd--
        return if (visibleEnd > start) measureWidth(start, visibleEnd) else 0f
    }
    fun paragraphBreaks(paragraphStart: Int, paragraphEnd: Int): List<Int>? {
        if (measuredWidth(paragraphStart, paragraphEnd) <= availableWidthPx) return listOf(paragraphEnd)
        val protected = (protectedRanges + wordRanges).filter {
            it.first >= paragraphStart && it.last < paragraphEnd &&
                measuredWidth(it.first, it.last + 1) <= availableWidthPx
        }
        while (graphemeIndex < graphemes.size && graphemes[graphemeIndex] <= paragraphStart) graphemeIndex++
        val offsets = buildList {
            add(paragraphStart)
            while (graphemeIndex < graphemes.size && graphemes[graphemeIndex] < paragraphEnd) {
                val offset = graphemes[graphemeIndex++]
                if ((safeOffsets == null || offset in safeOffsets) &&
                    protected.none { offset > it.first && offset <= it.last }
                ) add(offset)
            }
            add(paragraphEnd)
        }
        val naturalBreaks = BooleanArray(offsets.size) { index ->
            val end = offsets[index]
            val insideCjkWord = end < text.length && end > 0 && end !in words &&
                Character.UnicodeScript.of(text.codePointBefore(end)) in cjkScripts &&
                Character.UnicodeScript.of(text.codePointAt(end)) in cjkScripts
            end == paragraphEnd || end in legal && !insideCjkWord && protectedRanges.none {
                end > it.first && end <= it.last
            }
        }
        val result = mutableListOf<Int>()
        var startIndex = 0
        while (startIndex < offsets.lastIndex) {
            var naturalEnd = -1
            var fittingEnd = -1
            for (endIndex in startIndex + 1..offsets.lastIndex) {
                if (measuredWidth(offsets[startIndex], offsets[endIndex]) > availableWidthPx) break
                fittingEnd = endIndex
                if (naturalBreaks[endIndex]) naturalEnd = endIndex
            }
            val endIndex = if (naturalEnd > startIndex) naturalEnd else fittingEnd
            if (endIndex <= startIndex) return null
            result.add(offsets[endIndex])
            startIndex = endIndex
        }
        return result
    }
    val selected = mutableListOf<Int>()
    var paragraphStart = 0
    while (paragraphStart < text.length) {
        var paragraphEnd = paragraphStart
        while (paragraphEnd < text.length && text[paragraphEnd] !in "\r\n") paragraphEnd++
        if (paragraphEnd > paragraphStart) {
            selected.addAll(paragraphBreaks(paragraphStart, paragraphEnd) ?: return null)
        }
        if (paragraphEnd >= text.length) break
        paragraphStart = paragraphEnd + if (text[paragraphEnd] == '\r' &&
            text.getOrNull(paragraphEnd + 1) == '\n') 2 else 1
    }
    return selected.toIntArray()
}

/** Word grouping is independent of lyric timing tags and preserves trailing punctuation. */
internal fun lyricCjkWordRanges(text: String, boundaries: Set<Int>): List<IntRange> =
    (boundaries + setOf(0, text.length)).sorted().zipWithNext().mapNotNull { (start, end) ->
        if (start >= end) return@mapNotNull null
        val content = text.substring(start, end)
        if (!content.codePoints().anyMatch { Character.UnicodeScript.of(it) in cjkScripts } ||
            !content.codePoints().allMatch { code ->
                val type = Character.getType(code)
                Character.UnicodeScript.of(code) in cjkScripts || type == Character.NON_SPACING_MARK.toInt() ||
                    type == Character.COMBINING_SPACING_MARK.toInt()
            }
        ) return@mapNotNull null
        var tail = end
        while (tail < text.length && text[tail] !in "\r\n" &&
            (text[tail].isWhitespace() || Character.getType(text[tail]) in cjkTrailingPunctuation)
        ) tail++
        start until tail
    }

private val cjkTrailingPunctuation = setOf(
    Character.END_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt(),
)

internal fun lyricWordBoundaries(text: String, locale: Locale): List<Int> {
    if (Build.VERSION.SDK_INT < 24) return textBoundaries(text, BreakIterator.getWordInstance(locale))
    val iterator = android.icu.text.BreakIterator.getWordInstance(locale)
    iterator.setText(text)
    return buildList {
        var boundary = iterator.first()
        while (boundary != android.icu.text.BreakIterator.DONE) {
            add(boundary)
            boundary = iterator.next()
        }
    }
}

/** Reflow the original paragraph until both original and pronunciation fit their shared row. */
internal fun lyricParagraphReflowWidth(
    availableWidthPx: Int,
    minimumWidthPx: Int = 1,
    requiredWidth: (Int) -> Float,
): Int {
    val available = availableWidthPx.coerceAtLeast(1)
    val minimum = minimumWidthPx.coerceIn(1, available)
    var width = available
    var reflows = 0
    while (true) {
        val required = requiredWidth(width)
        if (required <= available + 0.5f || width <= minimum) return width
        reflows++
        // Caption widths change in steps at line breaks. Escape a plateau gradually;
        // exhausting the proportional attempts must never collapse the paragraph.
        width = if (reflows % 8 == 0) (width / 2).coerceAtLeast(minimum) else
            minOf(width - 1, (width * available.toFloat() / required).toInt()).coerceAtLeast(minimum)
    }
}

/** Splits only oversized spans, preserving natural line breaks and grapheme boundaries. */
internal fun lyricTextChunks(
    text: String,
    availableWidthPx: Float,
    measureWidth: (String) -> Float,
): List<IntRange> {
    if (text.isEmpty()) return emptyList()
    if ('\n' in text) {
        val result = mutableListOf<IntRange>()
        var start = 0
        text.forEachIndexed { index, character ->
            if (character == '\n') {
                result += lyricTextChunks(text.substring(start, index), availableWidthPx, measureWidth)
                    .map { (it.first + start)..(it.last + start) }
                result += index..index
                start = index + 1
            }
        }
        result += lyricTextChunks(text.substring(start), availableWidthPx, measureWidth)
            .map { (it.first + start)..(it.last + start) }
        return result
    }
    val width = availableWidthPx.coerceAtLeast(1f)
    if (measureWidth(text) <= width) return listOf(text.indices)
    val characters = listOf(0) + lyricGraphemeRanges(text).map { it.last + 1 }
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
            if ('\n' in contents[index]) {
                fittingEnd = end
                break
            }
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

/** Extend platform character boundaries for emoji sequences on older Android/JVM runtimes. */
internal fun lyricGraphemeRanges(text: String): List<IntRange> {
    if (text.isEmpty()) return emptyList()
    val boundaries = textBoundaries(text, BreakIterator.getCharacterInstance(Locale.ROOT))
    val retained = mutableListOf(0)
    var scannedOffset = 0
    var regionalCount = 0
    for (boundary in boundaries.drop(1).dropLast(1)) {
        while (scannedOffset < boundary) {
            val codePoint = text.codePointAt(scannedOffset)
            regionalCount = if (codePoint in 0x1F1E6..0x1F1FF) regionalCount + 1 else 0
            scannedOffset += Character.charCount(codePoint)
        }
        val previous = text.codePointBefore(boundary)
        val next = text.codePointAt(boundary)
        val nextType = Character.getType(next)
        val joins = previous == 0x200D || next == 0x200D ||
            next in 0xFE00..0xFE0F || next in 0xE0100..0xE01EF ||
            next in 0x1F3FB..0x1F3FF || next in 0xE0020..0xE007F ||
            nextType == Character.NON_SPACING_MARK.toInt() ||
            nextType == Character.COMBINING_SPACING_MARK.toInt() ||
            (previous in 0x1F1E6..0x1F1FF && next in 0x1F1E6..0x1F1FF && regionalCount % 2 == 1)
        if (!joins) retained += boundary
    }
    retained += text.length
    return retained.zipWithNext { start, end -> start until end }
}
