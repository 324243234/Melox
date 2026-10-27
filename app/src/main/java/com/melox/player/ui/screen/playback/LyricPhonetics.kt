package com.melox.player.ui.screen.playback

import com.melox.player.model.LyricLine
import com.melox.player.model.LyricWord
import com.melox.player.model.LyricAuxiliaryText

internal data class LyricPhonetics(
    val wordAnnotations: List<List<LyricAuxiliaryText>>,
    val captionTracks: List<LyricAuxiliaryText>,
) {
    val captions: List<String> get() = captionTracks.map { it.text }
    val wordTracks = wordAnnotations.map { tracks -> tracks.map { it.text } }
    val words: List<String?> = wordTracks.map {
        it.filter(String::isNotBlank).distinct().joinToString("\n").takeIf(String::isNotEmpty)
    }
    val hasAnnotations: Boolean get() = wordTracks.any { tracks -> tracks.any(String::isNotBlank) }
    val standaloneRomanization: String? get() = captions.filter(String::isNotBlank)
        .distinct().joinToString("\n").takeIf(String::isNotEmpty)
    val hasText: Boolean get() = hasAnnotations || captionTracks.isNotEmpty()

    /** Reflow each track independently before stacking it on the same original row. */
    fun wordAnnotationRows(index: Int, originalRows: List<String>): List<List<LyricAuxiliaryText>> {
        val tracks = wordAnnotations.getOrNull(index).orEmpty().map { lyricPhoneticTrackRows(it, originalRows) }
        return originalRows.indices.map { row -> tracks.map { it[row] } }
    }

    fun wordTrackRows(index: Int, originalRows: List<String>): List<List<String>> =
        wordAnnotationRows(index, originalRows).map { row -> row.map { it.text } }

    fun wordRows(index: Int, originalRows: List<String>): List<String> =
        wordTrackRows(index, originalRows).map { row ->
            row.filter(String::isNotBlank).distinct().joinToString("\n")
        }
}

/** Only explicit attachment or unambiguous timing may establish word correspondence. */
internal fun lyricPhonetics(line: LyricLine): LyricPhonetics {
    val annotations = List(line.words.size) { mutableListOf<LyricAuxiliaryText>() }
    val captions = mutableListOf<LyricAuxiliaryText>()
    val tracks = line.romanizations.filter { it.text.isNotBlank() }.groupBy { it.text }
    if (tracks.isNotEmpty()) {
        fun attachedOwners(track: LyricAuxiliaryText): List<Int>? {
            val matchingText = track.words.joinToString("") { it.spacedText() }.trim()
            if (matchingText.filterNot(Char::isWhitespace) != track.text.filterNot(Char::isWhitespace)) return null
            val owners = track.words.map { pronunciation ->
                line.words.indices.singleOrNull { index ->
                    val original = line.words[index]
                    pronunciation.endTimeMs > pronunciation.startTimeMs &&
                        pronunciation.startTimeMs >= original.startTimeMs &&
                        pronunciation.endTimeMs <= original.endTimeMs
                } ?: return null
            }
            return owners.takeIf { it.isNotEmpty() && it.zipWithNext().none { (a, b) -> a > b } }
        }
        for ((slot, entry) in tracks.entries.withIndex()) {
            val (_, candidates) = entry
            val attached = candidates.firstNotNullOfOrNull { track ->
                attachedOwners(track)?.let { track to it }
            }
            if (attached == null) {
                captions.add(candidates.first())
                continue
            }
            val (track, owners) = attached
            for (index in line.words.indices) {
                val words = track.words.filterIndexed { pronunciation, _ -> owners[pronunciation] == index }
                while (annotations[index].size < tracks.size) annotations[index].add(LyricAuxiliaryText(""))
                annotations[index][slot] = LyricAuxiliaryText(words.joinToString("") { it.spacedText() }.trim(),
                    track.language, words)
            }
        }
    }
    if (annotations.all { row -> row.none { it.text.isNotBlank() } } &&
        line.words.any { word -> word.ruby.any { it.text.isNotBlank() } }) {
        line.words.forEachIndexed { index, word ->
            annotations[index].clear()
            annotations[index].add(LyricAuxiliaryText(word.ruby.joinToString("") { it.text },
                words = word.ruby.map { LyricWord(it.startTimeMs, it.endTimeMs, it.text, false) }))
        }
    }
    return LyricPhonetics(
        annotations.map { it.toList() },
        captions,
    )
}

private fun LyricWord.spacedText(): String = text + if (hasTrailingSpace) " " else ""

/** Timing tags inside one shaped cluster must join corresponding pronunciation tracks. */
internal fun lyricMergePhoneticTracks(
    first: List<LyricAuxiliaryText>, second: List<LyricAuxiliaryText>,
): List<LyricAuxiliaryText> =
    List(maxOf(first.size, second.size)) { index ->
        val tracks = listOfNotNull(first.getOrNull(index), second.getOrNull(index))
        LyricAuxiliaryText(tracks.map { it.text }.filter(String::isNotBlank).joinToString(" "),
            tracks.firstOrNull()?.language, tracks.flatMap { it.words })
    }

/** Display whitespace may change, but every timed pronunciation keeps its visible offsets. */
internal fun lyricPhoneticTimings(
    track: LyricAuxiliaryText,
    advance: (Int) -> Float,
): List<LyricTimedDistance> {
    val offsets = track.text.indices.filter { !track.text[it].isWhitespace() }
    var cursor = 0
    return track.words.mapNotNull { word ->
        val length = word.text.count { !it.isWhitespace() }
        if (length == 0) return@mapNotNull null
        val start = offsets.getOrNull(cursor)
        val end = offsets.getOrNull(cursor + length - 1)?.plus(1)
        cursor += length
        if (start == null || end == null || word.endTimeMs <= word.startTimeMs) null else
            LyricTimedDistance(word.startTimeMs, word.endTimeMs, advance(start), advance(end))
    }
}

/** A forced source wrap cannot create new pronunciation boundaries or timestamps. */
internal fun lyricPhoneticTrackRows(track: LyricAuxiliaryText, originalRows: List<String>): List<LyricAuxiliaryText> =
    lyricPhoneticRows(track.text, originalRows).mapIndexed { row, text ->
        track.copy(text = text, words = if (row == 0) track.words else emptyList())
    }

/** Keep an unprojectable caption whole; visual row lengths cannot prove pronunciation alignment. */
internal fun lyricPhoneticRows(caption: String, originalRows: List<String>): List<String> {
    if (originalRows.isEmpty()) return emptyList()
    val text = caption.replace("\r\n", "\n").replace('\r', '\n').trim()
    return originalRows.indices.map { row -> if (row == 0) text else "" }
}
