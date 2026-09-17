package com.melox.player.data.lyrics

import com.melox.player.model.LyricLine
import com.melox.player.model.LyricWord
import com.melox.player.model.LyricsDocument
import com.melox.player.model.LyricsFormat
import com.melox.player.model.LyricsSource
import java.io.StringReader
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

internal object LyricsParser {
    private data class RawWord(
        val startTimeMs: Long,
        val explicitEndTimeMs: Long?,
        val text: String,
        val hasTrailingSpace: Boolean,
    )

    private data class RawLine(
        val agent: String,
        val startTimeMs: Long,
        val explicitEndTimeMs: Long?,
        val text: String?,
        val words: List<RawWord>,
        val translation: String?,
    )

    private data class TtmlTimingContext(
        val frameRate: Double,
        val tickRate: Double,
    )

    private val lrcTimestamp = Regex(
        """\[(\d{1,3}):([0-5]?\d)(?:[.:](\d{1,6}))?]""",
    )
    private val lrcOffset = Regex(
        """(?i)^\s*\[offset:\s*([+-]?\d+)\s*]\s*$""",
    )
    private val enhancedWordTimestamp = Regex(
        """<(\d{1,3}):([0-5]?\d)(?:[.:](\d{1,6}))?>""",
    )
    private val splInlineTimestamp = Regex(
        """(?:${lrcTimestamp.pattern}|${enhancedWordTimestamp.pattern})""",
    )
    private val lrcMetadata = Regex("""^\s*\[[^\d\]]+:[^\]]*]\s*$""")
    private val timestampValue = Regex(
        """^(\d{1,3}):([0-5]?\d)(?:[.:](\d{1,6}))?$""",
    )

    fun parse(
        raw: String,
        source: LyricsSource,
        preferredFormat: LyricsFormat? = null,
        durationMs: Long = 0L,
    ): LyricsDocument? {
        val bounded = raw
            .replace("\u0000", "")
            .take(MAX_LYRICS_CHARS)
            .trim()
        if (bounded.isEmpty()) return null
        val looksLikeTtml = preferredFormat == LyricsFormat.TTML ||
            bounded.startsWith("<?xml", ignoreCase = true) ||
            Regex("""(?is)<(?:\w+:)?tt(?:\s|>)""").containsMatchIn(bounded)
        return if (looksLikeTtml) {
            parseTtml(bounded, source, durationMs) ?: parseLrc(bounded, source, durationMs)
        } else {
            parseLrc(bounded, source, durationMs) ?: parseTtml(bounded, source, durationMs)
        }
    }

    private fun parseLrc(
        raw: String,
        source: LyricsSource,
        durationMs: Long,
    ): LyricsDocument? {
        val offsetMs = raw.lineSequence()
            .mapNotNull { line ->
                lrcOffset.matchEntire(line)?.groupValues?.get(1)?.toLongOrNull()
                    ?.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
                    ?: lrcOffset.matchEntire(line)?.let { 0L }
            }
            .lastOrNull()
            ?: 0L
        val entries = mutableListOf<RawLine>()
        var lastPhysicalEntryIndices = emptyList<Int>()
        var acceptsUntimedTranslation = false
        raw.lineSequence()
            .take(MAX_LYRIC_LINES)
            .forEach { line ->
                if (lrcOffset.matches(line)) return@forEach
                if (line.isBlank()) {
                    acceptsUntimedTranslation = false
                    return@forEach
                }
                val timestamps = lrcTimestamp.findAll(line).toList()
                if (timestamps.isEmpty()) {
                    if (acceptsUntimedTranslation && !lrcMetadata.matches(line)) {
                        val translation = line.trim().takeIf(String::isNotEmpty) ?: return@forEach
                        lastPhysicalEntryIndices.forEach { entryIndex ->
                            val entry = entries[entryIndex]
                            entries[entryIndex] = entry.copy(
                                translation = listOfNotNull(entry.translation, translation)
                                    .joinToString("\n"),
                            )
                        }
                    } else {
                        acceptsUntimedTranslation = false
                    }
                    return@forEach
                }
                val firstTimestamp = timestamps.first()
                if (line.substring(0, firstTimestamp.range.first).isNotBlank()) {
                    acceptsUntimedTranslation = false
                    return@forEach
                }
                var leadingTimestampCount = 1
                while (leadingTimestampCount < timestamps.size) {
                    val previous = timestamps[leadingTimestampCount - 1]
                    val next = timestamps[leadingTimestampCount]
                    if (!line.substring(previous.range.last + 1, next.range.first).isBlank()) break
                    leadingTimestampCount++
                }
                val firstInlineSquareTimestamp = timestamps.getOrNull(leadingTimestampCount)
                val delayedStartSeparator = if (leadingTimestampCount > 1) {
                    line.substring(
                        timestamps[leadingTimestampCount - 2].range.last + 1,
                        timestamps[leadingTimestampCount - 1].range.first,
                    )
                } else {
                    ""
                }
                val lastLeadingIsDelayedWordStart = firstInlineSquareTimestamp != null &&
                    leadingTimestampCount > 1 &&
                    (
                        timestamps[leadingTimestampCount - 1].toTimeMs() ==
                            timestamps.first().toTimeMs() ||
                            delayedStartSeparator.isNotEmpty()
                        )
                val lineTimestamps = timestamps.take(
                    if (lastLeadingIsDelayedWordStart) 1 else leadingTimestampCount,
                )
                val payload = line.substring(lineTimestamps.last().range.last + 1)
                if (payload.isBlank()) {
                    val explicitEndTimeMs = lineTimestamps.first().toTimeMs()
                        ?.let { (it + offsetMs).coerceAtLeast(0L) }
                        ?: return@forEach
                    lastPhysicalEntryIndices.forEach { entryIndex ->
                        val entry = entries[entryIndex]
                        val words = entry.words.toMutableList().apply {
                            if (isNotEmpty() && explicitEndTimeMs > last().startTimeMs) {
                                this[lastIndex] = last().copy(explicitEndTimeMs = explicitEndTimeMs)
                            }
                        }
                        entries[entryIndex] = entry.copy(
                            explicitEndTimeMs = explicitEndTimeMs,
                            words = words,
                        )
                    }
                    acceptsUntimedTranslation = false
                    return@forEach
                }
                val addedIndices = mutableListOf<Int>()
                lineTimestamps.forEach { timestamp ->
                    val startTimeMs = timestamp.toTimeMs() ?: return@forEach
                    val words = parseTimedWords(payload, startTimeMs, splInlineTimestamp)
                    val text = payload
                        .replace(splInlineTimestamp, "")
                        .trim()
                        .takeIf(String::isNotEmpty)
                        ?: return@forEach
                    addedIndices += entries.size
                    entries += RawLine(
                        agent = DEFAULT_AGENT,
                        startTimeMs = (startTimeMs + offsetMs).coerceAtLeast(0L),
                        explicitEndTimeMs = null,
                        text = text,
                        words = words.map { word ->
                            word.copy(
                                startTimeMs = (word.startTimeMs + offsetMs).coerceAtLeast(0L),
                                explicitEndTimeMs = word.explicitEndTimeMs
                                    ?.let { (it + offsetMs).coerceAtLeast(0L) },
                            )
                        },
                        translation = null,
                    )
                }
                lastPhysicalEntryIndices = addedIndices
                acceptsUntimedTranslation = addedIndices.isNotEmpty()
            }
        if (entries.isEmpty()) return null

        val grouped = entries
            .sortedBy(RawLine::startTimeMs)
            .groupBy(RawLine::startTimeMs)
            .map { (_, sameTimeLines) ->
                val primary = sameTimeLines.first()
                primary.copy(
                    translation = buildList {
                        primary.translation?.lineSequence()?.forEach(::add)
                        sameTimeLines.drop(1).mapNotNullTo(this, RawLine::text)
                    }
                        .distinct()
                        .joinToString("\n")
                        .takeIf(String::isNotBlank),
                )
            }
        return buildDocument(grouped, LyricsFormat.LRC, source, durationMs)
    }

    private fun parseTimedWords(
        payload: String,
        lineStartTimeMs: Long,
        timestampPattern: Regex,
    ): List<RawWord> {
        val timestamps = timestampPattern.findAll(payload).toList()
        if (timestamps.isEmpty()) return emptyList()
        val terminalEndTimeMs = timestamps.lastOrNull()
            ?.takeIf { payload.substring(it.range.last + 1).isBlank() }
            ?.toTimeMs()
            ?.takeIf { it >= lineStartTimeMs }
        val words = mutableListOf<RawWord>()
        val pendingText = StringBuilder()
        var wordStartTimeMs = lineStartTimeMs
        var acceptedTimestamp = false
        var textStart = 0
        timestamps.forEach { timestamp ->
            pendingText.append(payload, textStart, timestamp.range.first)
            textStart = timestamp.range.last + 1
            val timestampMs = timestamp.toTimeMs() ?: return@forEach
            val isValid = timestampMs >= lineStartTimeMs &&
                (terminalEndTimeMs == null || timestampMs <= terminalEndTimeMs) &&
                (!acceptedTimestamp || timestampMs > wordStartTimeMs)
            if (!isValid) return@forEach

            val rawText = pendingText.toString()
            if (rawText.isNotBlank()) {
                words.appendWord(rawText, wordStartTimeMs, timestampMs)
            } else if (rawText.isNotEmpty() && words.isNotEmpty()) {
                words.appendWord(rawText, wordStartTimeMs)
            }
            pendingText.clear()
            wordStartTimeMs = timestampMs
            acceptedTimestamp = true
        }
        pendingText.append(payload, textStart, payload.length)
        if (pendingText.isNotBlank()) {
            words.appendWord(pendingText.toString(), wordStartTimeMs)
        } else if (pendingText.isNotEmpty() && words.isNotEmpty()) {
            words.appendWord(pendingText.toString(), wordStartTimeMs)
        }
        return words.takeIf { acceptedTimestamp } ?: emptyList()
    }

    private fun MutableList<RawWord>.appendWord(
        rawText: String,
        startTimeMs: Long,
        endTimeMs: Long? = null,
        trailingSpaceOutside: Boolean = false,
    ) {
        if (rawText.firstOrNull()?.isWhitespace() == true && isNotEmpty()) {
            this[lastIndex] = last().copy(hasTrailingSpace = true)
        }
        val visible = rawText.trim()
        if (visible.isEmpty()) return
        add(
            RawWord(
                startTimeMs = startTimeMs,
                explicitEndTimeMs = endTimeMs,
                text = visible,
                hasTrailingSpace = rawText.lastOrNull()?.isWhitespace() == true ||
                    trailingSpaceOutside,
            ),
        )
    }

    private fun parseTtml(
        raw: String,
        source: LyricsSource,
        durationMs: Long,
    ): LyricsDocument? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            setFeatureSafely("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureSafely("http://xml.org/sax/features/external-general-entities", false)
            setFeatureSafely("http://xml.org/sax/features/external-parameter-entities", false)
            setAttributeSafely("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttributeSafely("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        val document = runCatching {
            factory.newDocumentBuilder().parse(InputSource(StringReader(raw)))
        }.getOrNull() ?: return null
        val timingContext = document.documentElement.toTtmlTimingContext()
        val paragraphs = document.getElementsByTagNameNS("*", "p")
        val entries = buildList {
            for (index in 0 until minOf(paragraphs.length, MAX_LYRIC_LINES)) {
                val paragraph = paragraphs.item(index) as? Element ?: continue
                parseTtmlParagraph(paragraph, timingContext)?.let(::add)
            }
        }.sortedBy(RawLine::startTimeMs)
        return buildDocument(entries, LyricsFormat.TTML, source, durationMs)
    }

    private fun parseTtmlParagraph(
        paragraph: Element,
        timingContext: TtmlTimingContext,
    ): RawLine? {
        val startTimeMs = paragraph.attributeValue("begin")
            ?.let { parseTtmlTimeMs(it, timingContext) }
            ?: return null
        val endTimeMs = paragraph.attributeValue("end")
            ?.let { parseTtmlTimeMs(it, timingContext) }
            ?: paragraph.attributeValue("dur")
                ?.let { parseTtmlTimeMs(it, timingContext) }
                ?.let(startTimeMs::plus)
        val agent = paragraph.attributeValue("agent")
            ?.takeIf(String::isNotBlank)
            ?: DEFAULT_AGENT
        val spans = paragraph.getElementsByTagNameNS("*", "span")
        var translation: String? = null
        val rawWords = buildList {
            for (index in 0 until spans.length) {
                val span = spans.item(index) as? Element ?: continue
                val role = span.attributeValue("role")?.lowercase(Locale.ROOT)
                when (role) {
                    "x-translation" -> {
                        translation = span.textContent.normalizeVisibleText()
                            .takeIf(String::isNotEmpty)
                    }
                    "x-bg", "x-roman" -> Unit
                    else -> {
                        val wordStart = span.attributeValue("begin")
                            ?.let { parseTtmlTimeMs(it, timingContext) }
                            ?: continue
                        val wordEnd = span.attributeValue("end")
                            ?.let { parseTtmlTimeMs(it, timingContext) }
                            ?: span.attributeValue("dur")
                                ?.let { parseTtmlTimeMs(it, timingContext) }
                                ?.let(wordStart::plus)
                        appendWord(
                            rawText = span.textContent.replace(Regex("""\s+"""), " "),
                            startTimeMs = wordStart,
                            endTimeMs = wordEnd,
                            trailingSpaceOutside = span.nextSibling
                                ?.takeIf { it.nodeType == Node.TEXT_NODE }
                                ?.nodeValue
                                ?.any(Char::isWhitespace) == true,
                        )
                    }
                }
            }
        }
        val text = if (rawWords.isEmpty()) {
            paragraph.textContent
                .normalizeVisibleText()
                .removeSuffix(translation.orEmpty())
                .trim()
                .takeIf(String::isNotEmpty)
        } else {
            null
        }
        if (text == null && rawWords.isEmpty()) return null
        return RawLine(
            agent = agent,
            startTimeMs = startTimeMs.coerceAtLeast(0L),
            explicitEndTimeMs = endTimeMs?.coerceAtLeast(startTimeMs),
            text = text,
            words = rawWords,
            translation = translation,
        )
    }

    private fun buildDocument(
        rawLines: List<RawLine>,
        format: LyricsFormat,
        source: LyricsSource,
        durationMs: Long,
    ): LyricsDocument? {
        if (rawLines.isEmpty()) return null
        val sorted = rawLines.sortedBy(RawLine::startTimeMs)
        val lines = sorted.mapIndexed { index, rawLine ->
            val nextStartTimeMs = sorted.getOrNull(index + 1)?.startTimeMs
            val lastWord = rawLine.words.lastOrNull()
            val explicitLastWordEnd = lastWord?.explicitEndTimeMs
                ?.takeIf { it > lastWord.startTimeMs }
            val fallbackEndTimeMs = when {
                explicitLastWordEnd != null -> explicitLastWordEnd
                nextStartTimeMs != null && nextStartTimeMs > rawLine.startTimeMs -> nextStartTimeMs
                durationMs > (lastWord?.startTimeMs ?: rawLine.startTimeMs) -> durationMs
                lastWord != null -> lastWord.startTimeMs + DEFAULT_WORD_DURATION_MS
                else -> rawLine.startTimeMs + DEFAULT_LINE_DURATION_MS
            }
            val nextLineTailEnd = nextStartTimeMs?.takeIf {
                lastWord != null && it > lastWord.startTimeMs
            }
            val endTimeMs = (nextLineTailEnd ?: rawLine.explicitEndTimeMs
                ?.takeIf { it > rawLine.startTimeMs }
                ?: fallbackEndTimeMs)
                .coerceAtMost(nextStartTimeMs ?: Long.MAX_VALUE)
            val words = rawLine.words.mapIndexed { wordIndex, word ->
                val nextWordStart = rawLine.words.getOrNull(wordIndex + 1)?.startTimeMs
                val fallbackWordEndTimeMs = (nextWordStart ?: endTimeMs)
                    .coerceAtMost(endTimeMs)
                    .coerceAtLeast(word.startTimeMs + MIN_WORD_DURATION_MS)
                val wordEndTimeMs = if (wordIndex == rawLine.words.lastIndex && nextLineTailEnd != null) {
                    nextLineTailEnd
                } else word.explicitEndTimeMs
                    ?.coerceAtMost(nextWordStart ?: endTimeMs)
                    ?.coerceAtMost(endTimeMs)
                    ?.takeIf { it > word.startTimeMs }
                    ?: fallbackWordEndTimeMs
                LyricWord(
                    startTimeMs = word.startTimeMs.coerceAtLeast(rawLine.startTimeMs),
                    endTimeMs = wordEndTimeMs,
                    text = word.text,
                    hasTrailingSpace = word.hasTrailingSpace,
                )
            }
            LyricLine(
                agent = rawLine.agent,
                startTimeMs = rawLine.startTimeMs,
                endTimeMs = endTimeMs,
                text = rawLine.text,
                words = words,
                translation = rawLine.translation,
            )
        }.filter { line -> line.displayText.isNotBlank() }
        if (lines.isEmpty()) return null
        return LyricsDocument(
            lines = lines,
            format = format,
            source = source,
        )
    }

    private fun MatchResult.toTimeMs(): Long? {
        val match = timestampValue.matchEntire(value.substring(1, value.lastIndex)) ?: return null
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        val fraction = fractionToMilliseconds(match.groupValues[3])
        return minutes * 60_000L + seconds * 1_000L + fraction
    }

    private fun fractionToMilliseconds(value: String): Long = when (value.length) {
        0 -> 0L
        1 -> value.toLongOrNull()?.times(100L) ?: 0L
        2 -> value.toLongOrNull()?.times(10L) ?: 0L
        else -> value.take(3).toLongOrNull() ?: 0L
    }

    private fun parseTtmlTimeMs(
        raw: String,
        timingContext: TtmlTimingContext,
    ): Long? {
        val value = raw.trim().lowercase(Locale.ROOT)
        Regex("""^(\d+(?:\.\d+)?)(ms|s|m|h|f|t)$""")
            .matchEntire(value)
            ?.let { match ->
                val amount = match.groupValues[1].toDoubleOrNull() ?: return null
                val multiplier = when (match.groupValues[2]) {
                    "ms" -> 1.0
                    "s" -> 1_000.0
                    "m" -> 60_000.0
                    "h" -> 3_600_000.0
                    "f" -> 1_000.0 / timingContext.frameRate
                    "t" -> 1_000.0 / timingContext.tickRate
                    else -> return null
                }
                return (amount * multiplier).toLong().coerceAtLeast(0L)
            }
        val parts = value.split(':')
        if (parts.size !in 2..4) return null
        val hours = if (parts.size >= 3) parts[0].toLongOrNull() ?: return null else 0L
        val minutesIndex = if (parts.size >= 3) 1 else 0
        val minutes = parts[minutesIndex].toLongOrNull() ?: return null
        val secondsIndex = minutesIndex + 1
        val seconds = parts[secondsIndex].toDoubleOrNull() ?: return null
        val frames = parts.getOrNull(secondsIndex + 1)?.toLongOrNull() ?: 0L
        if (minutes < 0L || seconds < 0.0 || frames < 0L) return null
        return (
            hours * 3_600_000L +
                minutes * 60_000L +
                seconds * 1_000.0 +
                frames * (1_000.0 / timingContext.frameRate)
            ).toLong().coerceAtLeast(0L)
    }

    private fun Element.toTtmlTimingContext(): TtmlTimingContext {
        val frameRate = attributeValue("frameRate")
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: DEFAULT_FRAME_RATE
        val frameRateMultiplier = attributeValue("frameRateMultiplier")
            ?.trim()
            ?.split(Regex("""\s+"""))
            ?.takeIf { it.size == 2 }
            ?.let { values ->
                val numerator = values[0].toDoubleOrNull()
                val denominator = values[1].toDoubleOrNull()
                if (
                    numerator != null && denominator != null &&
                    numerator.isFinite() && denominator.isFinite() &&
                    denominator > 0.0
                ) {
                    numerator / denominator
                } else {
                    null
                }
            }
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: 1.0
        val tickRate = attributeValue("tickRate")
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: DEFAULT_TICK_RATE
        return TtmlTimingContext(
            frameRate = frameRate * frameRateMultiplier,
            tickRate = tickRate,
        )
    }

    private fun Element.attributeValue(localName: String): String? {
        val attributes = attributes ?: return null
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            val name = attribute.localName ?: attribute.nodeName.substringAfter(':')
            if (name.equals(localName, ignoreCase = true)) return attribute.nodeValue
        }
        return null
    }

    private fun String.normalizeVisibleText(): String = replace(Regex("""\s+"""), " ").trim()

    private fun DocumentBuilderFactory.setFeatureSafely(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    private fun DocumentBuilderFactory.setAttributeSafely(name: String, value: String) {
        runCatching { setAttribute(name, value) }
    }

    private const val DEFAULT_AGENT = "main"
    private const val MAX_LYRICS_CHARS = 2 * 1024 * 1024
    private const val MAX_LYRIC_LINES = 10_000
    private const val MAX_OFFSET_MS = 10 * 60 * 1_000L
    private const val DEFAULT_LINE_DURATION_MS = 5_000L
    private const val DEFAULT_WORD_DURATION_MS = 500L
    private const val MIN_WORD_DURATION_MS = 50L
    private const val DEFAULT_FRAME_RATE = 30.0
    private const val DEFAULT_TICK_RATE = 1.0
}
