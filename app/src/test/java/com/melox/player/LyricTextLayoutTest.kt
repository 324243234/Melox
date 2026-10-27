package com.melox.player

import com.melox.player.data.lyrics.LyricsParser
import com.melox.player.model.LyricsSource
import com.melox.player.model.LyricAuxiliaryText
import com.melox.player.model.LyricLine
import com.melox.player.model.LyricRuby
import com.melox.player.model.LyricWord
import com.melox.player.ui.screen.playback.lyricPhonetics
import com.melox.player.ui.screen.playback.lyricPhoneticRows
import com.melox.player.ui.screen.playback.lyricParagraphReflowWidth
import com.melox.player.ui.screen.playback.lyricTextChunks
import com.melox.player.ui.screen.playback.lyricWrapRanges
import com.melox.player.ui.screen.playback.lyricAnnotationLineBreaks
import com.melox.player.ui.screen.playback.lyricLayoutText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricTextLayoutTest {
    private fun graphemeCount(text: String) =
        com.melox.player.ui.screen.playback.lyricGraphemeRanges(text).size

    @Test
    fun mixedSuppliedTracksSeparateMappedAnnotationsFromStandaloneRomanization() {
        val originals = listOf(LyricWord(0, 1_000, "君", false), LyricWord(1_000, 2_000, "へ", false))
        val mapped = LyricAuxiliaryText("kimi e", "ja-Latn", listOf(
            originals[0].copy(text = "kimi", hasTrailingSpace = true), originals[1].copy(text = "e")))
        val standalone = LyricAuxiliaryText("ki mi e", "ja-Latn")
        val line = phoneticLine(originals, mapped, standalone).copy(translation = "向着你")
        val prepared = lyricPhonetics(line)
        assertEquals(listOf("kimi", "e"), prepared.words)
        assertTrue(prepared.hasAnnotations)
        assertEquals("ki mi e", prepared.standaloneRomanization)
        assertEquals(listOf(standalone), prepared.captionTracks)
        assertEquals(listOf(mapped, standalone), line.romanizations)
        assertEquals("向着你", line.translation)
        assertEquals(originals, line.words)
    }

    @Test
    fun unadaptedRomanizationNeverBecomesAnAnnotationForAnyOriginalTimingShape() {
        for (originals in listOf(emptyList(), listOf(LyricWord(0, 2_000, "君へ", false)),
            listOf(LyricWord(0, 1_000, "君", false), LyricWord(1_000, 2_000, "へ", false)))) {
            val track = LyricAuxiliaryText("ki mi e")
            val line = phoneticLine(originals, track).copy(text = "君へ")
            val prepared = lyricPhonetics(line)
            assertTrue(prepared.wordTracks.flatten().all(String::isBlank))
            assertTrue(!prepared.hasAnnotations)
            assertEquals(track.text, prepared.standaloneRomanization)
            assertEquals(listOf(track), prepared.captionTracks)
        }
    }

    @Test
    fun fittingDictionaryWordsStayTogetherInAnnotatedRows() {
        val text = "撕裂、瓦解、支离、重塑、愈合"
        val groups = com.melox.player.ui.screen.playback.lyricCjkWordRanges(text,
            setOf(0, 2, 3, 5, 6, 8, 9, 11, 12, 14))
        assertEquals(listOf("撕裂、", "瓦解、", "支离、", "重塑、", "愈合"), groups.map { text.substring(it) })
        val breaks = lyricAnnotationLineBreaks(text, 7f, protectedRanges = groups) { from, to ->
            (to - from).toFloat()
        }!!
        assertTrue(breaks.size >= 3)
        assertTrue(breaks.none { it == 7 })
        assertTrue(groups.all { group -> breaks.none { it > group.first && it <= group.last } })
        assertEquals(text, lyricLayoutText(text, breaks).text.replace("\n", ""))
        val narrow = lyricAnnotationLineBreaks(text, 1f, protectedRanges = groups) { from, to ->
            (to - from).toFloat()
        }!!
        assertEquals(text, lyricLayoutText(text, narrow).text.replace("\n", ""))
    }

    @Test
    fun pronunciationReflowEscapesDiscreteWidthPlateausWithoutCollapsingToOnePixel() {
        val widths = mutableListOf<Int>()
        val result = lyricParagraphReflowWidth(300, minimumWidthPx = 24) { width ->
            widths += width
            if (width >= 220) 301f else 200f
        }
        assertTrue("Reflow collapsed after exhausting proportional attempts: $widths", result in 100..219)
        assertTrue(widths.all { it >= 24 })

        val indivisible = lyricParagraphReflowWidth(300, minimumWidthPx = 48) { 301f }
        assertEquals(48, indivisible)
    }

    @Test
    fun suppliedEarthMealVerseKeepsItsOriginalPronunciationAndTiming() {
        val document = LyricsParser.parse(javaClass.getResource("/lyrics/earth-meal-romanized.lrc")!!.readText(),
            LyricsSource.SIDECAR)!!
        val line = document.lines.single { it.startTimeMs == 37_775L }
        assertEquals("皆移住に夢中 俺未だ地球", line.displayText)
        assertEquals("mi n na i ju u ni mu chu u o re i ma da chi kyu u", line.romanization)
        assertEquals(37_775L, line.words.first().startTimeMs)
        assertEquals(40_429L, line.words.last().startTimeMs)
        assertEquals(line.endTimeMs, line.words.last().endTimeMs)
        assertTrue(lyricPhonetics(line).hasText)
    }

    @Test
    fun fittingPronunciationGroupsStayIntactInNaturalWrapping() {
        val text = "abcdefgh"
        val breaks = lyricAnnotationLineBreaks(text, 5f, protectedRanges = listOf(2..5)) { start, end -> (end - start).toFloat() }!!
        assertTrue("Fitting pronunciation group was split: ${breaks.toList()}",
            breaks.none { it in 3..5 })
        assertTrue((listOf(0) + breaks.toList()).zipWithNext().all { (start, end) -> end - start <= 5 })
        assertEquals(text, lyricLayoutText(text, breaks).text.replace("\n", ""))

        val oversized = lyricAnnotationLineBreaks(text, 3f, protectedRanges = listOf(text.indices)) { start, end -> (end - start).toFloat() }!!
        assertTrue(oversized.size > 1)
        assertEquals(text, lyricLayoutText(text, oversized).text.replace("\n", ""))
    }

    @Test
    fun naturalWrappingFillsRowsAndRetainsAuthoredParagraphs() {
        val text = "one two three four\r\n\nHello world"
        val breaks = lyricAnnotationLineBreaks(text, 13f) { start, end ->
            (end - start).toFloat()
        }!!
        assertEquals("one two three \nfour\r\n\nHello world", lyricLayoutText(text, breaks).text)
        val shaped = lyricAnnotationLineBreaks("office", 3f, safeOffsets = setOf(0, 1, 4, 5, 6)) { start, end -> (end - start).toFloat() }!!
        assertEquals(listOf(1, 4, 6), shaped.toList())
    }

    @Test
    fun fittingParagraphSkipsCandidatePairMeasurements() {
        val text = "甲".repeat(2_048)
        var measurements = 0
        val breaks = lyricAnnotationLineBreaks(text, 3_000f) { start, end ->
            measurements++
            (end - start).toFloat()
        }!!
        assertEquals(listOf(text.length), breaks.toList())
        assertEquals(1, measurements)
    }

    @Test
    fun regionalIndicatorPairingResetsAtOrdinaryText() {
        val flags = "🇨🇳".repeat(256) + "🇨x🇨🇳"
        assertEquals(List(256) { "🇨🇳" } + listOf("🇨", "x", "🇨🇳"),
            com.melox.player.ui.screen.playback.lyricGraphemeRanges(flags).map { flags.substring(it) })
    }

    @Test
    fun annotationBreaksKeepUnicodeWordsHardBreaksAndShapedUnitsIntact() {
        val text = "Hello world\r\n\ne\u0301👩‍❤️‍💋‍👩你好世界"
        val boundaries = lyricAnnotationLineBreaks(text, 8f) { start, end ->
            graphemeCount(text.substring(start, end)).toFloat()
        }!!
        val safe = com.melox.player.ui.screen.playback.lyricGraphemeRanges(text).map { it.last + 1 }.toSet()
        assertTrue(boundaries.all { it in safe })
        val display = lyricLayoutText(text, boundaries)
        assertTrue(display.text.contains("\r\n\n"))
        for (offset in text.indices) {
            assertEquals(text[offset], display.text[display.layoutOffsets[offset]])
            assertEquals(offset, display.sourceOffsets[display.layoutOffsets[offset]])
        }
        val shaped = lyricAnnotationLineBreaks("office", 3f,
            safeOffsets = setOf(0, 1, 4, 5, 6)) { start, end -> (end - start).toFloat() }!!
        assertEquals(listOf(1, 4, 6), shaped.toList())
    }

    @Test
    fun annotationDisplayBreaksNeverBecomeOriginalWordTimingOffsets() {
        val doc = LyricsParser.parse(javaClass.getResource("/lyrics/hiirogekka-anniversary.ttml")!!.readText(),
            LyricsSource.SIDECAR)!!
        for (line in doc.lines) {
            val original = line.animationWords().joinToString("") { it.text + if (it.hasTrailingSpace) " " else "" }
            val ends = lyricAnnotationLineBreaks(original, 9f) { start, end ->
                graphemeCount(original.substring(start, end)).toFloat()
            } ?: intArrayOf()
            val display = lyricLayoutText(original, ends)
            var offset = 0
            for (word in line.animationWords()) {
                for (index in word.text.indices) {
                    assertEquals(word.text[index], display.text[display.layoutOffsets[offset + index]])
                }
                offset += word.text.length + if (word.hasTrailingSpace) 1 else 0
                assertEquals(offset, display.sourceOffsets[display.layoutOffsets[offset]])
            }
        }
    }

    @Test
    fun unalignedCaptionsStayWholeWhileOriginalRowsReflowAtNarrowWidths() {
        for (original in listOf("你好世界你好世界", "e\u0301👩‍❤️‍💋‍👩かな歌詞の続き")) {
            for (available in listOf(72, 144, 240)) {
                val rows = lyricTextChunks(original, available.toFloat()) { graphemeCount(it) * 24f }
                    .map { original.substring(it) }
                assertEquals(original, rows.joinToString(""))
                assertTrue(rows.none { it.firstOrNull()?.isLowSurrogate() == true || it.startsWith("\u0301") })
                for (caption in listOf("ni hao shi jie ni hao shi jie", "ka na ka shi no tsu du ki")) {
                    val captions = lyricPhoneticRows(caption, rows)
                    assertEquals(rows.size, captions.size)
                    assertEquals(caption, captions.first())
                    assertTrue(captions.drop(1).all(String::isEmpty))
                }
            }
        }
    }

    @Test
    fun timedPronunciationGroupsMultipleSyllablesWithoutChangingOriginalTiming() {
        val words = listOf(
            LyricWord(0, 1_000, "君", false),
            LyricWord(1_000, 1_500, "を", false),
            LyricWord(1_500, 3_000, "想う", false),
        )
        val line = phoneticLine(words, LyricAuxiliaryText("kimi wo omou", words = listOf(
            LyricWord(0, 400, "ki", false),
            LyricWord(400, 1_000, "mi", true),
            LyricWord(1_000, 1_500, "wo", true),
            LyricWord(1_500, 3_000, "omou", false),
        )))
        val phonetics = lyricPhonetics(line)
        assertEquals(listOf("kimi", "wo", "omou"), phonetics.words)
        assertTrue(phonetics.captions.isEmpty())
        assertEquals(words, line.words)
        assertEquals("君を想う", line.displayText)

        // A wider pronunciation occupies its original's group instead of overlapping its neighbour.
        val rows = lyricWrapRanges(listOf("君", "を", "想う"), listOf(40f, 30f, 70f), 80f)
        assertEquals(listOf(0..1, 2..2), rows)
        assertEquals(listOf(listOf("kimi", "wo"), listOf("omou")),
            rows.map { phonetics.words.slice(it) })
    }

    @Test
    fun ambiguousIncompleteOrInheritedTimingKeepsTheEntireCaption() {
        val originals = listOf(LyricWord(0, 1_000, "君", false), LyricWord(1_000, 2_000, "へ", false))
        for (track in listOf(
            LyricAuxiliaryText("kimi e"),
            LyricAuxiliaryText("kimi e", words = listOf(LyricWord(0, 2_000, "kimi e", false))),
            LyricAuxiliaryText("kimi e", words = listOf(LyricWord(0, 1_000, "kimi", false))),
            LyricAuxiliaryText("kimi e", words = listOf(
                LyricWord(1_000, 2_000, "kimi", true), LyricWord(0, 1_000, "e", false))),
        )) {
            val phonetics = lyricPhonetics(phoneticLine(originals, track))
            assertEquals(listOf(null, null), phonetics.words)
            assertEquals(listOf("kimi e"), phonetics.captions)
            assertEquals(track.words, phonetics.captionTracks.single().words)
            assertEquals(listOf("kimi e", ""), lyricPhoneticRows(phonetics.captions.single(), listOf("君", "へ")))
        }
        val overlapping = listOf(LyricWord(0, 2_000, "君", false), LyricWord(0, 2_000, "へ", false))
        val ambiguous = lyricPhonetics(phoneticLine(overlapping,
            LyricAuxiliaryText("kimi e", words = listOf(LyricWord(0, 2_000, "kimi e", false)))))
        assertEquals(listOf("kimi e"), ambiguous.captions)
    }

    @Test
    fun explicitRubyStaysAboveWhileUnmappedRomanizationRemainsStandalone() {
        val words = listOf(
            LyricWord(0, 1_000, "今日", false, listOf(LyricRuby("きょ", 0, 500), LyricRuby("う", 500, 1_000))),
            LyricWord(1_000, 2_000, "も", false),
        )
        val line = phoneticLine(words)
        assertEquals(listOf("きょう", null), lyricPhonetics(line).words)
        val romanized = lyricPhonetics(line.copy(romanizations = listOf(LyricAuxiliaryText("kyou mo"))))
        assertEquals(listOf("きょう", null), romanized.words)
        assertEquals(listOf("kyou mo"), romanized.captions)
        assertEquals("kyou mo", romanized.standaloneRomanization)
        assertTrue(romanized.hasAnnotations)
    }

    @Test
    fun rowCaptionsPreserveAllTracksHardBreaksAndUnicodeWithoutInventingWordAlignment() {
        assertEquals(listOf("kimi wo omou", ""), lyricPhoneticRows("kimi wo omou", listOf("君を", "想")))
        assertEquals(listOf("first\nsecond", ""), lyricPhoneticRows("first\r\nsecond", listOf("甲", "乙")))
        for (caption in listOf("ka na shi mi", "👩‍❤️‍💋‍👩e\u0301かな", "kyou", "a")) {
            val rows = lyricPhoneticRows(caption, listOf("原", "文", "三", "行"))
            assertEquals(4, rows.size)
            assertEquals(caption.filterNot(Char::isWhitespace), rows.joinToString("").filterNot(Char::isWhitespace))
            assertTrue(rows.none { it.firstOrNull()?.isLowSurrogate() == true || it.startsWith("\u0301") })
        }
        val document = LyricsParser.parse(javaClass.getResource("/lyrics/uchiage-hanabi.lrc")!!.readText(),
            LyricsSource.SIDECAR)!!
        for (line in document.lines.filter { it.romanization != null }) {
            val phonetics = lyricPhonetics(line)
            assertTrue(phonetics.words.all { it == null })
            val rows = lyricTextChunks(line.displayText, 6f) { it.length.toFloat() }
                .map { line.displayText.substring(it) }
            for (caption in phonetics.captions) {
                assertEquals(caption.filterNot(Char::isWhitespace),
                    lyricPhoneticRows(caption, rows).joinToString("").filterNot(Char::isWhitespace))
            }
        }
    }

    private fun phoneticLine(words: List<LyricWord>, vararg captions: LyricAuxiliaryText) = LyricLine(
        agent = "main", startTimeMs = 0, endTimeMs = 3_000, text = null,
        words = words, translation = null, romanizations = captions.toList(),
    )

    @Test
    fun explicitLineBreaksRemainSeparateWhenTheWholeSpanFits() {
        val text = "Swimming\nthrough a\nholy soil"
        val chunks = lyricTextChunks(text, 1_000f) { it.length.toFloat() }
            .map { text.substring(it) }
        assertEquals(listOf("Swimming", "\n", "through a", "\n", "holy soil"), chunks)
        assertEquals(text, chunks.joinToString(""))
        assertEquals(listOf(0..1, 2..3, 4..4), lyricWrapRanges(chunks, List(chunks.size) { 10f }, 1_000f))
    }

    @Test
    fun repeatedHardBreaksPreserveBlankRowsAndGraphemeChunks() {
        val text = "e\u0301😀\n\nfgjpqy"
        val chunks = lyricTextChunks(text, 20f) { it.codePointCount(0, it.length) * 10f }
            .map { text.substring(it) }
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.none { it.first().isLowSurrogate() || it.first() == '\u0301' })
        val ranges = lyricWrapRanges(chunks, List(chunks.size) { 10f }, 1_000f)
        assertEquals(listOf("e\u0301😀\n", "\n", "fgjpqy"), ranges.map { chunks.slice(it).joinToString("") })
    }

    @Test
    fun wrappingFillsTheCurrentLineInsteadOfBalancingTheParagraph() {
        val parts = listOf("one ", "two ", "three ", "four ")
        assertEquals(listOf(0..2, 3..3), lyricWrapRanges(parts, List(4) { 30f }, 100f))
    }

    @Test
    fun spareWidthAcceptsAnySpacedSymbolBeforeWrappingTheFollowingWord() {
        for (symbol in listOf("-", "—", "/", "+", "&")) {
            val parts = listOf("prefix ", "word ", "$symbol ", "tail")
            assertEquals(
                "Symbol $symbol must not pull an already fitting word onto the next row",
                listOf(0..2, 3..3),
                lyricWrapRanges(parts, listOf(40f, 40f, 20f, 60f), 100f),
            )
        }
    }

    @Test
    fun trailingWhitespaceDoesNotRejectAWordWhoseInkStillFits() {
        assertEquals(
            listOf(0..2, 3..3),
            lyricWrapRanges(
                contents = listOf("one ", "two ", "three ", "four"),
                widths = listOf(36f, 36f, 36f, 30f),
                availableWidthPx = 102f,
                lineEndWidths = listOf(30f, 30f, 30f, 30f),
            ),
        )
    }

    @Test
    fun princeLyricKeepsTimedWordsAndAllowsBreaksAroundTheSpacedHyphen() {
        val raw = "[00:00.000] <00:00.000>I<00:01.262> <00:02.524>Wanna<00:03.786> <00:05.048>Be<00:06.310> <00:07.572>Your<00:08.834> <00:10.096>Lover<00:11.358> <00:12.620>-<00:13.882> <00:15.144>Prince<00:16.406>"
        val line = LyricsParser.parse(raw, LyricsSource.SIDECAR)!!.lines.single()
        assertEquals(listOf("I", "Wanna", "Be", "Your", "Lover", "-", "Prince"), line.words.map { it.text })
        assertEquals(listOf(0L, 2_524L, 5_048L, 7_572L, 10_096L, 12_620L, 15_144L), line.words.map { it.startTimeMs })
        val contents = line.words.takeLast(3).map { it.text + if (it.hasTrailingSpace) " " else "" }
        assertEquals(listOf("Lover ", "- ", "Prince"), contents)
        assertEquals(listOf(0..0, 1..1, 2..2), lyricWrapRanges(contents, listOf(60f, 20f, 60f), 70f))
        assertEquals(listOf(0..1, 2..2), lyricWrapRanges(contents, listOf(60f, 20f, 60f), 100f))
    }

    @Test
    fun longChineseLineWrapsWithoutSpaces() {
        val parts = "这是一句没有空格的中文歌词".map(Char::toString)
        val lines = lyricWrapRanges(parts, List(parts.size) { 30f }, 100f)
        assertTrue(lines.size > 1)
        assertTrue(lines.all { it.count() * 30 <= 100 })
        assertEquals(parts.indices.toList(), lines.flatMap { it.toList() })
    }

    @Test
    fun ordinaryEnglishSyllablesStayTogetherWhenTheWordFits() {
        val parts = listOf("Hel", "lo ", "world")
        val lines = lyricWrapRanges(parts, listOf(30f, 30f, 50f), 70f)
        assertEquals(listOf(0..1, 2..2), lines)
    }

    @Test
    fun oversizedWordBreaksBetweenTimedSyllables() {
        val lines = lyricWrapRanges(List(10) { "a" }, List(10) { 30f }, 100f)
        assertTrue(lines.size > 1)
        assertTrue(lines.all { it.count() * 30 <= 100 })
        assertEquals((0..9).toList(), lines.flatMap { it.toList() })
    }

    @Test
    fun oversizedSingleSpanSplitsWithoutLosingTextOrGraphemes() {
        for (text in listOf("averylongenglishword", "一整句很长的中文歌词", "e\u0301e\u0301e\u0301", "😀😀😀😀")) {
            val chunks = lyricTextChunks(text, 30f) { it.codePointCount(0, it.length) * 10f }
                .map { text.substring(it) }
            assertEquals(text, chunks.joinToString(""))
            assertTrue(chunks.all { it.codePointCount(0, it.length) * 10 <= 30 })
            assertTrue(chunks.none { it.first().isLowSurrogate() || it.first() == '\u0301' })
            assertTrue(chunks.none { it.last().isHighSurrogate() })
        }
    }

    @Test
    fun oversizedPhrasePrefersWordBoundaries() {
        val text = "Hello world again"
        val chunks = lyricTextChunks(text, 8f) { it.length.toFloat() }.map { text.substring(it) }
        assertEquals(listOf("Hello ", "world ", "again"), chunks)
    }

    @Test
    fun widthBoundaryWhitespaceDoesNotCreateBlankRows() {
        val text = "abc def ghi"
        val chunks = lyricTextChunks(text, 3f) { it.length.toFloat() }.map { text.substring(it) }
        assertEquals(listOf("abc ", "def ", "ghi"), chunks)
        assertTrue(chunks.all { it.trimEnd().length <= 3 })
        assertTrue(chunks.none(String::isBlank))
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun emptyOrTinyViewportStillTerminatesWithoutDroppingText() {
        assertEquals(emptyList<IntRange>(), lyricWrapRanges(emptyList(), emptyList(), 0f))
        assertEquals(listOf(0..0, 1..1), lyricTextChunks("ab", 0f) { it.length * 20f })
        assertEquals(listOf(0..0, 1..1), lyricWrapRanges(listOf("a", "b"), listOf(20f, 20f), 0f))
    }
}
