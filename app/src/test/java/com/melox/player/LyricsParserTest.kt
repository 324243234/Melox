package com.melox.player

import com.melox.player.data.lyrics.LyricsParser
import com.melox.player.model.LyricsFormat
import com.melox.player.model.LyricsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParserTest {
    @Test
    fun splAcceptsUpToSixFractionalDigits() {
        val line = LyricsParser.parse(
            "[1:02.123456]First[1:03.654321]",
            LyricsSource.SIDECAR,
        )!!.lines.single()

        assertEquals(62_123L, line.startTimeMs)
        assertEquals(63_654L, line.endTimeMs)
    }

    @Test
    fun splTimestampOnlyLineExplicitlyClosesThePreviousPlainLine() {
        val document = LyricsParser.parse(
            "[00:01.000]First line\n[00:02.250]\n[00:04.000]Next line",
            LyricsSource.SIDECAR,
        )!!

        assertEquals(2_250L, document.lines.first().endTimeMs)
        assertEquals(-1, document.currentLineIndex(3_000L))
    }

    @Test
    fun splCollectsAdjacentUntimedLinesAsMultilineTranslation() {
        val document = LyricsParser.parse(
            "[00:01.000]主歌词\nFirst translation\n第二行翻译\n[00:03.000]下一句",
            LyricsSource.SIDECAR,
        )!!

        assertEquals("First translation\n第二行翻译", document.lines.first().translation)
    }

    @Test
    fun splSupportsDelayedFirstWordAndMixedInlineDelimiters() {
        val line = LyricsParser.parse(
            "[05:20.22]<05:21.22>你好<05:23.22>椒盐音乐[05:24.22]",
            LyricsSource.SIDECAR,
        )!!.lines.single()

        assertEquals("你好椒盐音乐", line.displayText)
        assertEquals(listOf(321_220L, 323_220L), line.words.map { it.startTimeMs })
        assertEquals(324_220L, line.endTimeMs)
    }

    @Test
    fun splIgnoresInvalidInlineTimingWithoutDroppingText() {
        val line = LyricsParser.parse(
            "[00:10.000]A<00:09.000>B<00:11.000>C<00:10.500>D[00:12.000]",
            LyricsSource.SIDECAR,
        )!!.lines.single()

        assertEquals("ABCD", line.displayText)
        assertEquals(listOf("AB", "CD"), line.words.map { it.text })
        assertEquals(listOf(10_000L, 11_000L), line.words.map { it.startTimeMs })
        assertEquals(12_000L, line.endTimeMs)
    }

    @Test
    fun splIgnoresInlineTimingOutsideTheExplicitLineEnd() {
        val line = LyricsParser.parse(
            "[00:10.000]A<00:13.000>B[00:12.000]",
            LyricsSource.SIDECAR,
        )!!.lines.single()

        assertEquals("AB", line.displayText)
        assertEquals(listOf("AB"), line.words.map { it.text })
        assertEquals(10_000L, line.words.single().startTimeMs)
        assertEquals(12_000L, line.words.single().endTimeMs)
    }

    @Test
    fun splExpandsRepeatedTimedLinesIndependently() {
        val document = LyricsParser.parse(
            "[00:10.000][00:20.000]A[00:21.000]B[00:22.000]",
            LyricsSource.SIDECAR,
        )!!

        assertEquals(listOf(10_000L, 20_000L), document.lines.map { it.startTimeMs })
        assertEquals(listOf("AB", "AB"), document.lines.map { it.displayText })
        assertEquals(2, document.lines.first().words.size)
        assertEquals(2, document.lines.last().words.size)
    }

    @Test
    fun squareWordTimingAcceptsADuplicateOrDelayedFirstWordStamp() {
        for (prefix in listOf("[00:01.00][00:01.00]", "[00:00.00] [00:01.00]")) {
            val line = LyricsParser.parse(
                prefix + "Hello [00:02.00]world[00:03.00]",
                LyricsSource.SIDECAR,
            )!!.lines.single()
            assertEquals("Hello world", line.displayText)
            assertEquals(listOf(1_000L, 2_000L), line.words.map { it.startTimeMs })
            assertEquals(3_000L, line.words.last().endTimeMs)
        }
    }

    @Test
    fun squareBracketWordTimesKeepContentTranslationsAndTerminalEnd() {
        val document = LyricsParser.parse(
            "[00:01.000]Hello [00:02.000]world[00:05.000]\n" +
                "[00:01.000]你好世界[00:04.990]\n" +
                "[00:08.000]Last [00:09.000]word[00:12.000]",
            LyricsSource.EMBEDDED,
            durationMs = 20_000L,
        )!!
        assertEquals(2, document.lines.size)
        val first = document.lines.first()
        assertEquals(1_000L, first.startTimeMs)
        assertEquals("Hello world", first.displayText)
        assertEquals("你好世界", first.translation)
        assertEquals(listOf("Hello", "world"), first.words.map { it.text })
        assertEquals(listOf(1_000L, 2_000L), first.words.map { it.startTimeMs })
        assertTrue(first.words.first().hasTrailingSpace)
        assertEquals(8_000L, first.words.last().endTimeMs)
        assertEquals(12_000L, document.lines.last().words.last().endTimeMs)
    }

    @Test
    fun squareBracketWordTimesWithoutATerminalMarkerRetainTheEarlierWords() {
        val line = LyricsParser.parse(
            "[00:01.000]Hel[00:02.000]lo [00:03.000]world",
            LyricsSource.SIDECAR,
            durationMs = 5_000L,
        )!!.lines.single()
        assertEquals("Hello world", line.displayText)
        assertEquals(listOf("Hel", "lo", "world"), line.words.map { it.text })
        assertEquals(listOf(false, true, false), line.words.map { it.hasTrailingSpace })
        assertEquals(5_000L, line.endTimeMs)
    }

    @Test
    fun adjacentLeadingTimestampsStillRepeatTheSamePlainLine() {
        val document = LyricsParser.parse(
            "[00:01.00][00:05.00]Repeated line",
            LyricsSource.SIDECAR,
        )!!
        assertEquals(listOf(1_000L, 5_000L), document.lines.map { it.startTimeMs })
        assertTrue(document.lines.all { it.displayText == "Repeated line" && it.words.isEmpty() })
    }

    @Test
    fun squareBracketWordTimesApplyOffsetToStartsAndTerminalEnd() {
        val line = LyricsParser.parse(
            "[00:01.00]Hello[00:02.00] [00:03.00]world[00:06.00]\n[offset:250]",
            LyricsSource.SIDECAR,
        )!!.lines.single()
        assertEquals("Hello world", line.displayText)
        assertEquals(listOf(1_250L, 3_250L), line.words.map { it.startTimeMs })
        assertEquals(6_250L, line.words.last().endTimeMs)
    }

    @Test
    fun squareAngleAndPlainLrcCanCoexistInOneDocument() {
        val document = LyricsParser.parse(
            "[00:01.00]First [00:02.00]row[00:03.00]\n" +
                "[00:04.00]<00:04.00>Second <00:05.00>row<00:06.00>\n" +
                "[00:07.00]Plain row",
            LyricsSource.SIDECAR,
        )!!
        assertEquals(listOf("First row", "Second row", "Plain row"), document.lines.map { it.displayText })
        assertEquals(listOf(2, 2, 0), document.lines.map { it.words.size })
    }

    @Test
    fun finalSongLineRetainsTheTerminalTimestampWithoutAFollowingLine() {
        val line = LyricsParser.parse(
            "[00:01.00]<00:01.00>Hello <00:02.00>world<00:05.00>",
            LyricsSource.SIDECAR,
            durationMs = 10_000L,
        )!!.lines.single()
        assertEquals(5_000L, line.words.last().endTimeMs)
        assertEquals(5_000L, line.endTimeMs)
    }

    @Test
    fun ttmlTailAlsoContinuesToTheFollowingLine() {
        val doc = LyricsParser.parse(
            "<tt><body><p begin='1s' end='4s'><span begin='1s' end='2s'>First </span><span begin='2s' end='4s'>tail</span></p><p begin='7s' end='8s'>Next</p></body></tt>",
            LyricsSource.SIDECAR,
        )!!
        assertEquals(2_000L, doc.lines.first().words.first().endTimeMs)
        assertEquals(7_000L, doc.lines.first().words.last().endTimeMs)
        assertEquals(7_000L, doc.lines.first().endTimeMs)
    }

    @Test
    fun enhancedLrcFinalWordContinuesToTheNextLineEvenWithATerminalMarker() {
        val doc = LyricsParser.parse(
            "[offset:250]\n[00:01.00]<00:01.00>Hello <00:02.00>world<00:05.00>\n[00:08.00]Next",
            LyricsSource.SIDECAR,
        )!!
        val lastWord = doc.lines.first().words.last()
        assertEquals(2_250L, lastWord.startTimeMs)
        assertEquals(8_250L, lastWord.endTimeMs)
        assertEquals(8_250L, doc.lines.first().endTimeMs)
        assertEquals(0, doc.currentLineIndex(6_000L))
    }

    @Test
    fun missingTerminalTimestampUsesTheNextLineOrTrackEnd() {
        val raw = "[00:01.00]<00:01.00>Hello <00:02.00>world"
        val nextLine = LyricsParser.parse(raw + "\n[00:05.00]Next", LyricsSource.SIDECAR)!!
        assertEquals(5_000L, nextLine.lines.first().words.last().endTimeMs)
        val trackEnd = LyricsParser.parse(raw, LyricsSource.SIDECAR, durationMs = 8_000L)!!
        assertEquals(8_000L, trackEnd.lines.last().words.last().endTimeMs)
        assertTrue(trackEnd.lines.last().revealProgress(3_000L, false) < 0.75f)
    }

    @Test
    fun ttmlFinalWordUsesItsExplicitEndAndMissingEndUsesParagraphEnd() {
        for ((ending, expected) in listOf("end='5s'" to 5_000L, "" to 8_000L)) {
            val doc = LyricsParser.parse(
                "<tt><body><p begin='1s' end='8s'><span begin='1s' end='2s'>Hello </span><span begin='2s' $ending>world</span></p></body></tt>",
                LyricsSource.SIDECAR,
            )!!
            assertEquals(expected, doc.lines.single().words.last().endTimeMs)
        }
    }

    @Test
    fun ttmlKeepsSpacesInsideTimedSpansOnEveryLine() {
        val document = LyricsParser.parse(
            raw = """
                <tt><body><div>
                  <p begin="1s" end="3s"><span begin="1s" end="2s">Hello </span><span begin="2s" end="3s">world</span></p>
                  <p begin="4s" end="6s"><span begin="4s" end="5s">Hello</span><span begin="5s" end="6s"> world</span></p>
                </div></body></tt>
            """.trimIndent(),
            source = LyricsSource.SIDECAR,
        )!!
        assertEquals(listOf("Hello world", "Hello world"), document.lines.map { it.displayText })
        assertTrue(document.lines.all { it.words.first().hasTrailingSpace })
    }

    @Test
    fun enhancedLrcKeepsLeadingWhitespaceInTheTimedDrawingPath() {
        for (payload in listOf(
            "<00:01.00>Hello<00:02.00> world",
            "<00:01.00>Hello <00:02.00>world",
            "<00:01.00>Hello<00:01.50> <00:02.00>world",
        )) {
            val line = LyricsParser.parse("[00:01.00]$payload", LyricsSource.SIDECAR)!!.lines.single()
            assertEquals("Hello world", line.words.joinToString("") {
                it.text + if (it.hasTrailingSpace) " " else ""
            })
            assertEquals(listOf(1_000L, 2_000L), line.words.map { it.startTimeMs })
        }
    }

    @Test
    fun adjacentTimedSyllablesNeverGainInventedSpaces() {
        for (parts in listOf("Hel" to "lo", "你" to "好")) {
            val line = LyricsParser.parse(
                "<tt><body><p begin='1s' end='3s'><span begin='1s' end='2s'>${parts.first}</span><span begin='2s' end='3s'>${parts.second}</span></p></body></tt>",
                LyricsSource.SIDECAR,
            )!!.lines.single()
            assertEquals(parts.first + parts.second, line.displayText)
            assertFalse(line.words.first().hasTrailingSpace)
        }
    }

    @Test
    fun enhancedLrcRetainsWordTimingSpacingAndTranslation() {
        val document = LyricsParser.parse(
            raw = """
                [00:10.00]<00:10.00>Hello <00:10.50>world
                [00:10.00]你好世界
                [00:12.00]Next line
            """.trimIndent(),
            source = LyricsSource.SIDECAR,
            durationMs = 15_000L,
        )

        assertNotNull(document)
        assertEquals(LyricsFormat.LRC, document?.format)
        assertEquals(2, document?.lines?.size)
        val first = document!!.lines.first()
        assertEquals("Hello world", first.displayText)
        assertEquals("你好世界", first.translation)
        assertEquals(2, first.words.size)
        assertTrue(first.words.first().hasTrailingSpace)
        assertEquals(10_500L, first.words.first().endTimeMs)
        assertEquals(12_000L, first.words.last().endTimeMs)
    }

    @Test
    fun lrcOffsetAppliesToLinesBeforeItsMetadataTag() {
        val document = LyricsParser.parse(
            raw = """
                [00:01.00]First
                [offset:+250]
                [00:02.00]Second
            """.trimIndent(),
            source = LyricsSource.SIDECAR,
        )!!

        assertEquals(1_250L, document.lines[0].startTimeMs)
        assertEquals(2_250L, document.lines[1].startTimeMs)
    }

    @Test
    fun ttmlRetainsAgentTranslationAndTimedSpans() {
        val document = LyricsParser.parse(
            raw = """
                <?xml version="1.0" encoding="utf-8"?>
                <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
                  <body><div>
                    <p begin="00:00:10.000" end="00:00:12.000" ttm:agent="v1">
                      <span begin="00:00:10.000" end="00:00:10.500">Hello</span>
                      <span begin="00:00:10.500" end="00:00:12.000">world</span>
                      <span ttm:role="x-translation">你好世界</span>
                    </p>
                  </div></body>
                </tt>
            """.trimIndent(),
            source = LyricsSource.EMBEDDED,
        )

        val line = document!!.lines.single()
        assertEquals(LyricsFormat.TTML, document.format)
        assertEquals("v1", line.agent)
        assertEquals("Hello world", line.displayText)
        assertEquals("你好世界", line.translation)
        assertEquals(2, line.words.size)
    }

    @Test
    fun ttmlRetainsExplicitWordEndBeforeTheNextWordStarts() {
        val document = LyricsParser.parse(
            raw = """
                <tt xmlns="http://www.w3.org/ns/ttml">
                  <body><div>
                    <p begin="10s" end="12s">
                      <span begin="10s" end="10.2s">Short</span>
                      <span begin="11s" end="12s">gap</span>
                    </p>
                  </div></body>
                </tt>
            """.trimIndent(),
            source = LyricsSource.EMBEDDED,
        )!!

        assertEquals(10_200L, document.lines.single().words.first().endTimeMs)
        assertEquals(11_000L, document.lines.single().words.last().startTimeMs)
    }

    @Test
    fun ttmlUsesDeclaredFrameRateMultiplierAndTickRate() {
        val document = LyricsParser.parse(
            raw = """
                <tt xmlns="http://www.w3.org/ns/ttml"
                    xmlns:ttp="http://www.w3.org/ns/ttml#parameter"
                    ttp:frameRate="25"
                    ttp:frameRateMultiplier="1000 1001"
                    ttp:tickRate="50">
                  <body><div>
                    <p begin="00:00:01:12" end="125t">Timed</p>
                  </div></body>
                </tt>
            """.trimIndent(),
            source = LyricsSource.EMBEDDED,
        )!!.lines.single()

        assertEquals(1_480L, document.startTimeMs)
        assertEquals(2_500L, document.endTimeMs)
    }

    @Test
    fun forcedRevealUsesLineDurationButPlainModeRevealsImmediately() {
        val line = LyricsParser.parse(
            raw = "[00:10.00]Hello world\n[00:12.00]Next",
            source = LyricsSource.SIDECAR,
        )!!.lines.first()

        assertEquals(0.5f, line.revealProgress(11_000L, forceWordByWord = true), 0.001f)
        assertEquals(1f, line.revealProgress(10_001L, forceWordByWord = false), 0.001f)
    }

    @Test
    fun currentLineRespectsTimedGaps() {
        val document = LyricsParser.parse(
            raw = """
                <tt xmlns="http://www.w3.org/ns/ttml">
                  <body><div>
                    <p begin="1s" end="2s">First</p>
                    <p begin="4s" end="5s">Second</p>
                  </div></body>
                </tt>
            """.trimIndent(),
            source = LyricsSource.EMBEDDED,
        )!!

        assertEquals(0, document.currentLineIndex(1_500L))
        assertEquals(-1, document.currentLineIndex(3_000L))
        assertEquals(1, document.currentLineIndex(4_500L))
        assertEquals(0, document.visualLineIndex(3_000L))
        assertEquals(0, document.visualFocusLineIndex(3_000L))
        assertEquals(1, document.visualLineIndex(4_000L))
        assertEquals(1, document.visualFocusLineIndex(4_000L))
        assertEquals(0, document.focusLineIndex(0L))
        assertEquals(1, document.focusLineIndex(3_000L))
        assertEquals(1, document.focusLineIndex(9_000L))
    }

    @Test
    fun denseLrcLinesRemainIndividuallyCurrent() {
        val document = LyricsParser.parse(
            raw = """
                [00:11.100]Guitar
                [00:11.640]Guitar:
                [00:11.850]Bass:
                [00:12.270]Drums:
                [00:12.510]Lyrics
            """.trimIndent(),
            source = LyricsSource.SIDECAR,
            durationMs = 20_000L,
        )!!

        assertEquals(0, document.currentLineIndex(11_100L))
        assertEquals(1, document.currentLineIndex(11_640L))
        assertEquals(2, document.currentLineIndex(11_850L))
        assertEquals(3, document.currentLineIndex(12_270L))
        assertEquals(4, document.currentLineIndex(12_510L))
    }

    @Test
    fun enhancedLrcTailRemainsCurrentUntilTheNextLine() {
        val document = LyricsParser.parse(
            raw = """
                [00:01.00]<00:01.00>First<00:01.50>
                [00:06.50]Second
            """.trimIndent(),
            source = LyricsSource.SIDECAR,
        )!!

        assertEquals(0, document.currentLineIndex(4_000L))
        assertEquals(0, document.focusLineIndex(4_000L))
        assertEquals(0, document.visualLineIndex(4_000L))
        assertEquals(0, document.visualFocusLineIndex(4_000L))
    }

    @Test
    fun untimedOrMalformedLyricsAreIgnored() {
        assertNull(
            LyricsParser.parse(
                raw = "plain lyrics without timestamps",
                source = LyricsSource.EMBEDDED,
            ),
        )
        assertFalse(
            LyricsParser.parse(
                raw = "[00:01.00]Timed",
                source = LyricsSource.SIDECAR,
            )!!.lines.isEmpty(),
        )
    }
}
