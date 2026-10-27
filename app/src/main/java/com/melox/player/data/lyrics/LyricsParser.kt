package com.melox.player.data.lyrics

import com.melox.player.model.LyricLine
import com.melox.player.model.LyricAgent
import com.melox.player.model.LyricAuxiliaryText
import com.melox.player.model.LyricRuby
import com.melox.player.model.LyricWord
import com.melox.player.model.LyricsDocument
import com.melox.player.model.LyricsFormat
import com.melox.player.model.LyricsSource
import java.io.StringReader
import java.math.BigDecimal
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
        val ruby: List<LyricRuby> = emptyList(),
    )

    private data class RawLine(
        val agent: String,
        val startTimeMs: Long,
        val explicitEndTimeMs: Long?,
        val text: String?,
        val words: List<RawWord>,
        val translation: String?,
        val translations: List<LyricAuxiliaryText> = emptyList(),
        val romanizations: List<LyricAuxiliaryText> = emptyList(),
        val isBackground: Boolean = false,
        val key: String? = null,
        val songPart: String? = null,
        val language: String? = null,
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
            .removePrefix("\uFEFF")
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
                    val rawText = payload
                        .replace(splInlineTimestamp, "")
                        .trim()
                        .takeIf(String::isNotEmpty)
                        ?: return@forEach
                    // 同一行内用窄间隔 U+2009 分隔原文/译文（不靠重复时间戳表达翻译）。
                    val (text, inlineTranslation) = splitInlineTranslation(rawText)
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
                        translation = inlineTranslation,
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
                val auxiliaryTexts = buildList {
                    sameTimeLines.forEachIndexed { index, entry ->
                        if (index > 0) entry.text?.let(::add)
                        entry.translation?.lineSequence()?.forEach(::add)
                    }
                }.distinct()
                val (romanizations, translations) = auxiliaryTexts.partition { auxiliary ->
                    isJapaneseRomanization(primary.text.orEmpty(), auxiliary)
                }
                primary.copy(
                    translation = translations.joinToString("\n")
                        .takeIf(String::isNotBlank),
                    romanizations = romanizations.map { LyricAuxiliaryText(it, language = "ja-Latn") },
                )
            }
        return buildDocument(grouped, LyricsFormat.LRC, source, durationMs)
    }

    /** LRC has no track roles; only recognize kana-backed, syllabic Japanese captions. */
    private fun isJapaneseRomanization(primary: String, auxiliary: String): Boolean {
        if (primary.none { it in '\u3041'..'\u3096' || it in '\u30A1'..'\u30FA' }) return false
        if (auxiliary.any { it.isDigit() || it.isLetter() && it !in JAPANESE_ROMAN_LETTERS }) return false
        val tokens = JAPANESE_ROMAN_TOKEN.findAll(auxiliary).map { it.value }.toList()
        return tokens.size >= 3 && tokens.all { token ->
            token.length <= 64 && JAPANESE_ROMAN_SYLLABLES.matches(token)
        }
    }

    /** 单行内翻译的最小分隔符：窄间隔 U+2009（thin space）。 */
    private const val INLINE_TRANSLATION_SEPARATOR = '\u2009'

    /**
     * 单行歌词内的翻译拆分：只认窄间隔 U+2009 作为原文/译文分界，
     * 左侧原文、右侧译文；半角/全角空格是行内停顿，不拆。
     */
    private fun splitInlineTranslation(text: String): Pair<String, String?> {
        val index = text.indexOf(INLINE_TRANSLATION_SEPARATOR)
        if (index <= 0 || index >= text.length - 1) return text to null
        val main = text.substring(0, index).trim()
        val sub = text.substring(index + 1).trim()
        if (main.isEmpty() || sub.isEmpty()) return text to null
        return main to sub
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
        ruby: List<LyricRuby> = emptyList(),
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
                ruby = ruby,
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
        val root = document.documentElement
        val head = root.descendants("head").firstOrNull()
        val tracks = buildMap<String, MutableList<TtmlHeadTrack>> {
            for (container in head?.descendants("translation").orEmpty() +
                head?.descendants("transliteration").orEmpty()
            ) {
                for (text in container.descendants("text")) {
                    val key = text.attributeValue("for") ?: continue
                    getOrPut(key) { mutableListOf() }.add(
                        TtmlHeadTrack(container.localTag() == "translation", container.language(), text),
                    )
                }
            }
        }
        val entries = root.descendants("body").flatMap { body ->
            body.descendants("p").take(MAX_LYRIC_LINES).flatMap { paragraph ->
                parseTtmlParagraph(paragraph, timingContext, tracks)
            }
        }.take(MAX_LYRIC_LINES)
        val metadata = buildMap<String, MutableList<String>> {
            head?.descendants("meta").orEmpty().forEach { meta ->
                val key = meta.attributeValue("key") ?: return@forEach
                val value = meta.attributeValue("value") ?: return@forEach
                getOrPut(key) { mutableListOf() }.add(value)
            }
            head?.descendants("title")?.firstOrNull()?.textContent
                ?.normalizeVisibleText()?.takeIf(String::isNotEmpty)?.let { title ->
                    if (!containsKey("musicName")) put("musicName", mutableListOf(title))
                }
        }
        return buildDocument(entries, LyricsFormat.TTML, source, durationMs)?.copy(
            metadata = metadata,
            agents = head?.descendants("agent").orEmpty().mapNotNull { agent ->
                val id = agent.attributeValue("id") ?: return@mapNotNull null
                LyricAgent(id, agent.attributeValue("type"), agent.descendants("name")
                    .firstOrNull { it.attributeValue("type") == "full" }?.textContent
                    ?.normalizeVisibleText())
            },
            songwriters = head?.descendants("songwriter").orEmpty()
                .map { it.textContent.normalizeVisibleText() }.filter(String::isNotEmpty),
            language = root.language(),
        )
    }

    private data class TtmlHeadTrack(
        val translation: Boolean,
        val language: String?,
        val content: Element,
    )

    private fun parseTtmlParagraph(
        paragraph: Element,
        timingContext: TtmlTimingContext,
        headTracks: Map<String, List<TtmlHeadTrack>>,
    ): List<RawLine> {
        val start = paragraph.time("begin", timingContext) ?: return emptyList()
        val end = paragraph.endTime(start, timingContext)
        val key = paragraph.attributeValue("key")
        val tracks = headTracks[key].orEmpty()
        val backgrounds = paragraph.backgroundSpans()
        fun readLine(content: Element, backgroundIndex: Int? = null): RawLine? {
            val contentStart = content.time("begin", timingContext) ?: start
            val contentEnd = content.endTime(contentStart, timingContext) ?: end
            val words = content.timedWords(timingContext, contentStart, contentEnd)
            val lineStart = if (backgroundIndex == null) start else
                content.time("begin", timingContext) ?: words.minOfOrNull { it.startTimeMs } ?: start
            val lineEnd = if (backgroundIndex == null) end else
                content.endTime(lineStart, timingContext)
                    ?: words.mapNotNull { it.explicitEndTimeMs }.maxOrNull() ?: end
            val translations = content.auxiliarySpans("x-translation")
                .map { it.auxiliaryText(timingContext, lineStart, lineEnd) }.toMutableList()
            val romanizations = content.auxiliarySpans("x-roman")
                .map { it.auxiliaryText(timingContext, lineStart, lineEnd) }.toMutableList()
            tracks.forEach { track ->
                val trackContent = if (backgroundIndex == null) track.content else
                    track.content.backgroundSpans().getOrNull(backgroundIndex)
                if (trackContent != null) {
                    val auxiliary = trackContent.auxiliaryText(timingContext, lineStart, lineEnd)
                        .copy(language = track.language)
                    if (auxiliary.text.isNotBlank()) {
                        if (track.translation) translations.add(auxiliary) else romanizations.add(auxiliary)
                    }
                }
            }
            val text = content.visibleTtmlText().trim().takeIf(String::isNotEmpty)
            if (text == null && words.isEmpty()) return null
            return RawLine(
                agent = content.attributeValue("agent") ?: paragraph.attributeValue("agent") ?: DEFAULT_AGENT,
                startTimeMs = lineStart,
                explicitEndTimeMs = lineEnd,
                text = text.takeIf { words.isEmpty() },
                words = words,
                translation = translations.map { it.text }.filter(String::isNotBlank)
                    .joinToString("\n").takeIf(String::isNotEmpty),
                translations = translations.filter { it.text.isNotBlank() },
                romanizations = romanizations.filter { it.text.isNotBlank() },
                isBackground = backgroundIndex != null,
                key = key,
                songPart = generateSequence(paragraph) { it.parentNode as? Element }
                    .firstNotNullOfOrNull { it.attributeValue("songPart") ?: it.attributeValue("song-part") },
                language = content.language(),
            )
        }
        return listOfNotNull(readLine(paragraph)) + backgrounds.mapIndexedNotNull { index, background ->
            readLine(background, index)
        }
    }

    private fun Element.auxiliaryText(
        context: TtmlTimingContext,
        start: Long,
        end: Long?,
    ): LyricAuxiliaryText = LyricAuxiliaryText(
        text = visibleTtmlText().trim(),
        language = language(),
        words = buildTtmlWords(timedWords(context, start, end), end ?: start + DEFAULT_LINE_DURATION_MS),
    )

    private fun Element.timedWords(
        context: TtmlTimingContext,
        start: Long,
        end: Long?,
    ): List<RawWord> {
        fun hasTiming(element: Element): Boolean = element.childElements().any { child ->
            child.attributeValue("role") !in AUXILIARY_ROLES &&
                (child.attributeValue("begin") != null ||
                    child.attributeValue("ruby") == "container" || hasTiming(child))
        }
        if (!hasTiming(this)) return emptyList()
        val words = mutableListOf<RawWord>()
        fun visit(node: Node, inheritedStart: Long, inheritedEnd: Long?) {
            if (node is Element) {
                if (node.attributeValue("role") in AUXILIARY_ROLES) return
                val wordStart = node.time("begin", context) ?: inheritedStart
                val wordEnd = node.endTime(wordStart, context) ?: inheritedEnd
                if (node.attributeValue("ruby") == "container") {
                    val annotations = node.descendants("span")
                        .filter { it.attributeValue("ruby") == "text" }.map { annotation ->
                            val rubyStart = annotation.time("begin", context) ?: wordStart
                            LyricRuby(annotation.textContent.normalizeVisibleText(), rubyStart,
                                annotation.endTime(rubyStart, context) ?: wordEnd ?: rubyStart)
                        }
                    words.appendWord(node.visibleTtmlText(),
                        annotations.minOfOrNull { it.startTimeMs } ?: wordStart,
                        annotations.maxOfOrNull { it.endTimeMs } ?: wordEnd,
                        ruby = annotations)
                } else if (node.localTag() == "span" && !hasTiming(node)) {
                    words.appendWord(node.visibleTtmlText(), wordStart, wordEnd)
                } else {
                    node.childNodesList().forEach { visit(it, wordStart, wordEnd) }
                }
            } else if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                val text = node.ttmlText()
                if (text.isBlank()) {
                    if (text.isNotEmpty() && words.isNotEmpty()) {
                        words[words.lastIndex] = words.last().copy(hasTrailingSpace = true)
                    }
                } else words.appendWord(text, inheritedStart, inheritedEnd)
            }
        }
        childNodesList().forEach { visit(it, start, end) }
        return words
    }

    private fun Element.time(name: String, context: TtmlTimingContext): Long? =
        attributeValue(name)?.let { parseTtmlTimeMs(it, context) }

    private fun Element.endTime(start: Long, context: TtmlTimingContext): Long? =
        time("end", context) ?: time("dur", context)?.let { duration ->
            runCatching { Math.addExact(start, duration) }.getOrNull()
        }

    private fun Element.language(): String? = generateSequence(this) { it.parentNode as? Element }
        .firstNotNullOfOrNull { it.attributeValue("lang") }

    private fun Element.localTag(): String = localName ?: tagName.substringAfter(':')

    private fun Node.childNodesList(): List<Node> = (0 until childNodes.length).map(childNodes::item)
    private fun Element.childElements(): List<Element> = childNodesList().filterIsInstance<Element>()
    private fun Element.descendants(name: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", name)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun Element.backgroundSpans(): List<Element> = buildList {
        fun visit(element: Element) {
            element.childElements().forEach { child ->
                val role = child.attributeValue("role")
                if (role == "x-bg") add(child)
                else if (role !in AUXILIARY_ROLES) visit(child)
            }
        }
        visit(this@backgroundSpans)
    }

    private fun Element.auxiliarySpans(role: String): List<Element> = buildList {
        fun visit(element: Element) {
            element.childElements().forEach { child ->
                val childRole = child.attributeValue("role")
                if (childRole == role) add(child)
                else if (childRole !in AUXILIARY_ROLES) visit(child)
            }
        }
        visit(this@auxiliarySpans)
    }

    private fun Element.visibleTtmlText(): String = childNodesList().joinToString("") { child ->
        when {
            child is Element && (child.attributeValue("role") in AUXILIARY_ROLES ||
                child.attributeValue("ruby") in setOf("text", "textContainer")) -> ""
            child is Element -> child.visibleTtmlText()
            child.nodeType == Node.TEXT_NODE || child.nodeType == Node.CDATA_SECTION_NODE -> child.ttmlText()
            else -> ""
        }
    }

    private fun Node.ttmlText(): String {
        val text = nodeValue.orEmpty()
        if (text.isBlank() && ('\n' in text || '\r' in text)) return ""
        return text.replace(Regex("""\s+"""), " ")
    }

    private fun buildTtmlWords(words: List<RawWord>, lineEnd: Long): List<LyricWord> =
        words.mapIndexed { index, word ->
            val nextStart = words.getOrNull(index + 1)?.startTimeMs?.takeIf { it > word.startTimeMs }
            val end = word.explicitEndTimeMs?.takeIf { it > word.startTimeMs }
                ?: nextStart ?: lineEnd.takeIf { it > word.startTimeMs }
                ?: (word.startTimeMs + DEFAULT_WORD_DURATION_MS)
            LyricWord(word.startTimeMs, end, word.text, word.hasTrailingSpace, word.ruby)
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
                format == LyricsFormat.LRC && lastWord != null && it > lastWord.startTimeMs
            }
            val unclampedEnd = nextLineTailEnd ?: rawLine.explicitEndTimeMs
                ?.takeIf { it > rawLine.startTimeMs } ?: fallbackEndTimeMs
            val endTimeMs = if (format == LyricsFormat.TTML) unclampedEnd else
                unclampedEnd.coerceAtMost(nextStartTimeMs ?: Long.MAX_VALUE)
            val words = if (format == LyricsFormat.TTML) {
                buildTtmlWords(rawLine.words, endTimeMs)
            } else {
                rawLine.words.mapIndexed { wordIndex, word ->
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
            }
            LyricLine(
                agent = rawLine.agent,
                startTimeMs = rawLine.startTimeMs,
                endTimeMs = endTimeMs,
                text = rawLine.text,
                words = words,
                translation = rawLine.translation,
                translations = rawLine.translations,
                romanizations = rawLine.romanizations,
                isBackground = rawLine.isBackground,
                key = rawLine.key,
                songPart = rawLine.songPart,
                language = rawLine.language,
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
        if (ttmlNumber.matches(value)) return value.toBigDecimalOrNull()
            ?.multiply(BigDecimal(1_000))?.millisecondsOrNull()
        ttmlOffset.matchEntire(value)?.let { match ->
            val amount = match.groupValues[1].toBigDecimalOrNull() ?: return null
            val multiplier = when (match.groupValues[2]) {
                "ms" -> BigDecimal.ONE
                "s" -> BigDecimal(1_000)
                "m" -> BigDecimal(60_000)
                "h" -> BigDecimal(3_600_000)
                "f" -> BigDecimal(1_000).divide(BigDecimal.valueOf(timingContext.frameRate),
                    java.math.MathContext.DECIMAL64)
                "t" -> BigDecimal(1_000).divide(BigDecimal.valueOf(timingContext.tickRate),
                    java.math.MathContext.DECIMAL64)
                else -> return null
            }
            return amount.multiply(multiplier).millisecondsOrNull()
        }
        val parts = value.split(':')
        if (parts.size !in 2..4) return null
        val minutesIndex = if (parts.size >= 3) 1 else 0
        val hours = if (parts.size >= 3) parts[0].toLongOrNull()?.takeIf { it >= 0L }
            ?: return null else 0L
        val minutes = parts[minutesIndex].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val secondsText = parts[minutesIndex + 1]
        if (!ttmlNumber.matches(secondsText)) return null
        val seconds = secondsText.toBigDecimalOrNull() ?: return null
        val frames = if (parts.size == 4) parts[3].toLongOrNull()?.takeIf { it >= 0L }
            ?: return null else 0L
        return BigDecimal.valueOf(hours).multiply(BigDecimal(3_600_000))
            .add(BigDecimal.valueOf(minutes).multiply(BigDecimal(60_000)))
            .add(seconds.multiply(BigDecimal(1_000)))
            .add(BigDecimal.valueOf(frames).multiply(BigDecimal(1_000))
                .divide(BigDecimal.valueOf(timingContext.frameRate), java.math.MathContext.DECIMAL64))
            .millisecondsOrNull()
    }

    private fun BigDecimal.millisecondsOrNull(): Long? = runCatching {
        setScale(0, java.math.RoundingMode.DOWN).longValueExact().takeIf { it >= 0L }
    }.getOrNull()

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
            frameRate = (frameRate * frameRateMultiplier)
                .takeIf { it.isFinite() && it > 0.0 } ?: DEFAULT_FRAME_RATE,
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

    private val ttmlNumber = Regex("""\d+(?:\.\d+)?""")
    private val ttmlOffset = Regex("""^(\d+(?:\.\d+)?)(ms|s|m|h|f|t)$""")
    private val AUXILIARY_ROLES = setOf("x-translation", "x-roman", "x-bg")
    private const val JAPANESE_ROMAN_LETTERS = "abcdefghijklmnopqrstuvwxyzāīūēōâîûêô"
    private val JAPANESE_ROMAN_TOKEN = Regex("""'?[a-zāīūēōâîûêô]+""")
    private val JAPANESE_ROMAN_SYLLABLES =
        Regex("""(?:(?:sh|ch|ts|[bcdfghjklmpqrstvwxyz]y?)?[aeiouāīūēōâîûêô]|n|'[bcdfghjklmnpqrstvwxyz])+""")

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
