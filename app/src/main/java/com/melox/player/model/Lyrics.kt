package com.melox.player.model

import java.util.Locale

enum class LyricsFormat {
    LRC,
    TTML,
}

enum class LyricsSource {
    EMBEDDED,
    SIDECAR,
}

data class LyricRuby(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
)

data class LyricWord(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String,
    val hasTrailingSpace: Boolean,
    val ruby: List<LyricRuby> = emptyList(),
)

data class LyricAuxiliaryText(
    val text: String,
    val language: String? = null,
    val words: List<LyricWord> = emptyList(),
)

data class LyricAgent(
    val id: String,
    val type: String?,
    val name: String?,
)

enum class LyricAlignment {
    START,
    END,
    CENTER,
}

data class LyricPlaybackFrame(
    val activeLineIndices: List<Int>,
    val anchorIndex: Int,
    val anchorLineIndices: List<Int> = if (anchorIndex >= 0) listOf(anchorIndex) else emptyList(),
)

data class LyricLine(
    val agent: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String?,
    val words: List<LyricWord>,
    val translation: String?,
    val translations: List<LyricAuxiliaryText> = emptyList(),
    val romanizations: List<LyricAuxiliaryText> = emptyList(),
    val isBackground: Boolean = false,
    val key: String? = null,
    val songPart: String? = null,
    val language: String? = null,
) {
    val displayText: String
        get() = text ?: words.joinToString(separator = "") { word ->
            word.text + if (word.hasTrailingSpace) " " else ""
        }.trimEnd()

    val romanization: String? = romanizations.map { it.text }.filter(String::isNotBlank).distinct()
        .joinToString("\n").takeIf(String::isNotEmpty)

    /** Select captions for presentation without changing the parsed auxiliary tracks. */
    fun translationFor(preference: LyricsTranslationLanguage, appLanguageTag: String): String? {
        val captions = translations.mapNotNull { caption ->
            val text = caption.text.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val language = caption.language.orEmpty().trim().replace('_', '-').lowercase(Locale.ROOT)
                .takeUnless { it == "und" }.orEmpty()
            language to text
        }.distinct()
        if (captions.isEmpty()) return translation?.takeIf(String::isNotBlank)
        if (preference == LyricsTranslationLanguage.ALL) {
            return captions.map { it.second }.distinct().joinToString("\n")
        }
        // Legacy LRC captions have no language labels; retain their intentional hard breaks.
        if (captions.all { it.first.isEmpty() }) return captions.map { it.second }.joinToString("\n")
        val appTag = appLanguageTag.replace('_', '-').lowercase(Locale.ROOT)
        val preferred = when (preference) {
            LyricsTranslationLanguage.AUTO -> appTag.substringBefore('-')
            LyricsTranslationLanguage.CHINESE -> "zh"
            LyricsTranslationLanguage.ENGLISH -> "en"
            LyricsTranslationLanguage.ALL -> error("Handled above")
        }
        val languages = captions.map { it.first }.distinct()
        fun matchingLanguage(language: String): String? = languages
            .filter { it.substringBefore('-') == language }
            .minWithOrNull(compareBy<String> {
                when {
                    it == appTag && preferred == language -> 0
                    it == language -> 1
                    it == "zh-hans" || it == "zh-cn" -> 2
                    else -> 3
                }
            }.thenBy { it })
        val selected = matchingLanguage(preferred) ?: matchingLanguage("en")
            ?: "".takeIf { it in languages } ?: languages.sorted().first()
        return captions.filter { it.first == selected }.map { it.second }.joinToString("\n")
    }

    fun animationWords(): List<LyricWord> = words.ifEmpty {
        listOf(LyricWord(startTimeMs, endTimeMs, displayText, hasTrailingSpace = false))
    }

    fun revealProgress(positionMs: Long, forceWordByWord: Boolean): Float {
        if (positionMs < startTimeMs) return 0f
        if (words.isEmpty()) {
            if (!forceWordByWord) return if (positionMs >= startTimeMs) 1f else 0f
            return intervalProgress(positionMs, startTimeMs, endTimeMs)
        }

        val totalCharacters = words.sumOf { word ->
            word.text.length + if (word.hasTrailingSpace) 1 else 0
        }.coerceAtLeast(1)
        var revealedCharacters = 0f
        for (word in words) {
            val wordCharacters = word.text.length + if (word.hasTrailingSpace) 1 else 0
            when {
                positionMs >= word.endTimeMs -> revealedCharacters += wordCharacters
                positionMs > word.startTimeMs -> {
                    revealedCharacters += wordCharacters * intervalProgress(
                        positionMs = positionMs,
                        startTimeMs = word.startTimeMs,
                        endTimeMs = word.endTimeMs,
                    )
                    break
                }
                else -> break
            }
        }
        return (revealedCharacters / totalCharacters).coerceIn(0f, 1f)
    }
}

data class LyricsDocument(
    val lines: List<LyricLine>,
    val format: LyricsFormat,
    val source: LyricsSource,
    val metadata: Map<String, List<String>> = emptyMap(),
    val agents: List<LyricAgent> = emptyList(),
    val songwriters: List<String> = emptyList(),
    val language: String? = null,
) {
    private val agentTypes = agents.associate { it.id to it.type }
    private val singingAgents = lines.asSequence().filterNot { it.isBackground }
        .map { it.agent }.filter { id ->
            id.isNotBlank() && !isGroupAgent(id) && (id != "main" || id in agentTypes)
        }.distinct().toList().let { ids ->
            (agents.map { it.id }.filter { it in ids } + ids.filter { id -> agents.none { it.id == id } }).distinct()
        }
    val isDuet: Boolean = format == LyricsFormat.TTML && singingAgents.size > 1
    private val playbackTimeline by lazy { LyricPlaybackTimeline(this) }

    fun lineAlignment(index: Int): LyricAlignment {
        val line = lines.getOrNull(index) ?: return LyricAlignment.START
        if (!isDuet) return LyricAlignment.START
        val singerIndex = singingAgents.indexOf(line.agent)
        return when {
            isGroupAgent(line.agent) || singerIndex < 0 -> LyricAlignment.CENTER
            singerIndex % 2 == 0 -> LyricAlignment.START
            else -> LyricAlignment.END
        }
    }

    fun playbackFrame(positionMs: Long): LyricPlaybackFrame = playbackTimeline.frameAt(positionMs)

    private fun isGroupAgent(id: String): Boolean =
        agentTypes[id].equals("group", ignoreCase = true) || id == "v1000" && id !in agentTypes

    private val prefixEndTimes = LongArray(lines.size).also { ends ->
        lines.forEachIndexed { index, line ->
            ends[index] = maxOf(line.endTimeMs, ends.getOrElse(index - 1) { Long.MIN_VALUE })
        }
    }

    fun currentLineIndex(positionMs: Long): Int {
        if (lines.isEmpty() || positionMs < lines.first().startTimeMs) return -1
        val candidate = lastLineIndexAtOrBefore(positionMs)
        if (candidate < 0) return -1
        var index = candidate
        while (index >= 0) {
            val line = lines[index]
            if (positionMs < line.endTimeMs ||
                (index == lines.lastIndex && line.endTimeMs <= line.startTimeMs)
            ) return index
            if (format != LyricsFormat.TTML || prefixEndTimes[index] <= positionMs) break
            index--
        }
        return -1
    }

    /** Keeps a completed line visually focused until the next line starts. */
    fun visualLineIndex(positionMs: Long): Int {
        currentLineIndex(positionMs).takeIf { it >= 0 }?.let { return it }
        val previousIndex = lastLineIndexAtOrBefore(positionMs)
        if (previousIndex < 0 || previousIndex >= lines.lastIndex) return -1
        val nextLineStartTimeMs = lines[previousIndex + 1].startTimeMs
        return previousIndex.takeIf { positionMs < nextLineStartTimeMs } ?: -1
    }

    /** Resolves the line that owns visual centering. */
    fun visualFocusLineIndex(positionMs: Long): Int =
        visualLineIndex(positionMs).takeIf { it >= 0 } ?: focusLineIndex(positionMs)

    fun focusLineIndex(positionMs: Long): Int {
        currentLineIndex(positionMs).takeIf { it >= 0 }?.let { return it }
        if (lines.isEmpty()) return -1

        return (lastLineIndexAtOrBefore(positionMs) + 1).coerceAtMost(lines.lastIndex)
    }

    private fun lastLineIndexAtOrBefore(positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var candidate = -1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            if (lines[middle].startTimeMs <= positionMs) {
                candidate = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return candidate
    }
}

/** Resolve all active vocals once per boundary; display frames only perform a binary search. */
private class LyricPlaybackTimeline(document: LyricsDocument) {
    private class Boundary {
        val starts = mutableListOf<Int>()
        val ends = mutableListOf<Int>()
    }

    private val initial = LyricPlaybackFrame(
        emptyList(),
        document.lines.indexOfFirst { !it.isBackground }.takeIf { it >= 0 }
            ?: if (document.lines.isEmpty()) -1 else 0,
    )
    private val times: LongArray
    private val frames: List<LyricPlaybackFrame>

    init {
        val boundaries = java.util.TreeMap<Long, Boundary>()
        document.lines.forEachIndexed { index, line ->
            if (line.endTimeMs > line.startTimeMs || index == document.lines.lastIndex) {
                boundaries.getOrPut(line.startTimeMs, ::Boundary).starts += index
                if (line.endTimeMs > line.startTimeMs) {
                    boundaries.getOrPut(line.endTimeMs, ::Boundary).ends += index
                }
            }
        }
        val active = sortedSetOf<Int>()
        val hasMainVocals = document.lines.any { !it.isBackground }
        var anchor = initial.anchorIndex
        var anchorLines = initial.anchorLineIndices
        times = boundaries.keys.toLongArray()
        frames = boundaries.map { (time, boundary) ->
            active.removeAll(boundary.ends.toSet())
            active.addAll(boundary.starts)
            if (document.format == LyricsFormat.TTML) {
                // An ending short part must never pull scrolling back to an older long part.
                boundary.starts.firstOrNull { !hasMainVocals || !document.lines[it].isBackground }
                    ?.let {
                        anchor = maxOf(anchor, it)
                        anchorLines = active.filter { index -> !hasMainVocals || !document.lines[index].isBackground }
                    }
            } else {
                anchor = document.focusLineIndex(time)
                anchorLines = listOf(anchor)
            }
            LyricPlaybackFrame(active.toList(), anchor, anchorLines)
        }
    }

    fun frameAt(positionMs: Long): LyricPlaybackFrame {
        val found = times.binarySearch(positionMs)
        val index = if (found >= 0) found else -found - 2
        return frames.getOrElse(index) { initial }
    }
}

sealed interface LyricsUiState {
    data object Loading : LyricsUiState

    data object Unavailable : LyricsUiState

    data class Available(
        val document: LyricsDocument,
        val mediaId: String? = null,
    ) : LyricsUiState
}

fun LyricsUiState.isDuetForMedia(mediaId: String?): Boolean =
    mediaId != null && this is LyricsUiState.Available && this.mediaId == mediaId && document.isDuet

private fun intervalProgress(
    positionMs: Long,
    startTimeMs: Long,
    endTimeMs: Long,
): Float {
    val durationMs = endTimeMs - startTimeMs
    if (durationMs <= 0L) return if (positionMs >= startTimeMs) 1f else 0f
    return ((positionMs - startTimeMs).toFloat() / durationMs).coerceIn(0f, 1f)
}
