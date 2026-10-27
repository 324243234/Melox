package com.melox.player

import com.melox.player.data.lyrics.LyricsParser
import com.melox.player.data.repository.parseEmbeddedLyrics
import com.melox.player.model.LyricsFormat
import com.melox.player.model.LyricsSource
import com.melox.player.model.LyricsTranslationLanguage
import com.melox.player.model.LyricAuxiliaryText
import com.melox.player.model.LyricAlignment
import com.melox.player.model.LyricLine
import com.melox.player.model.LyricWord
import com.melox.player.model.LyricsUiState
import com.melox.player.model.isDuetForMedia
import com.melox.player.ui.screen.playback.lyricPhonetics
import com.melox.player.ui.screen.playback.lyricMergePhoneticTracks
import com.melox.player.ui.screen.playback.lyricPhoneticTimings
import com.melox.player.ui.screen.playback.LyricParagraphClock
import com.melox.player.ui.screen.playback.lyricStableRenderPositionMs
import org.junit.Assert.*
import org.junit.Test

class TtmlCompatibilityTest {
    @Test
    fun pronunciationRetainsItsOwnGapsEndpointsAndRowTimesAcrossFocusAndReverseSeeks() {
        val original = LyricWord(0, 4_000, "今日", false)
        val readings = listOf(LyricWord(1_000, 1_500, "to", true), LyricWord(3_000, 3_500, "day", false))
        val line = LyricLine("main", 0, 4_000, null, listOf(original), null,
            romanizations = listOf(LyricAuxiliaryText("to day", words = readings)))
        val phonetics = lyricPhonetics(line)
        val track = phonetics.wordAnnotations.single().single()
        val clock = LyricParagraphClock(lyricPhoneticTimings(track) { it * 10f })
        for ((time, distance) in listOf(500L to 0f, 1_250L to 10f, 2_000L to 30f,
            2_750L to 30f, 3_250L to 45f, 4_000L to 60f, 1_250L to 10f)) {
            assertEquals(distance, clock.distanceAt(time), 0.0001f)
        }
        assertEquals(clock.distanceAt(lyricStableRenderPositionMs(false, false, 0)),
            clock.distanceAt(lyricStableRenderPositionMs(true, false, 0)), 0f)
        assertEquals(60f, clock.distanceAt(lyricStableRenderPositionMs(false, true, 0)), 0f)
        val rows = phonetics.wordAnnotationRows(0, listOf("今", "日"))
        assertEquals(listOf("to day", ""), rows.map { it.single().text })
        assertEquals(readings.map { it.startTimeMs to it.endTimeMs }, rows.flatMap { it.single().words }.map { it.startTimeMs to it.endTimeMs })
        assertEquals(listOf(original), line.words)
        assertEquals(readings, line.romanizations.single().words)

        val splitTrack = LyricAuxiliaryText("kyou", words = listOf(LyricWord(1_000, 3_000, "kyou", false)))
        val split = lyricPhonetics(line.copy(romanizations = listOf(splitTrack)))
            .wordAnnotationRows(0, listOf("今", "日", "本")).map { it.single() }
        assertEquals("kyou", split.joinToString("") { it.text })
        val splitWords = split.flatMap { it.words }
        assertEquals(1_000L, splitWords.first().startTimeMs)
        assertEquals(3_000L, splitWords.last().endTimeMs)
        assertTrue(splitWords.zipWithNext().all { (first, second) -> first.endTimeMs == second.startTimeMs })

        val rubyWord = original.copy(ruby = listOf(
            com.melox.player.model.LyricRuby("ky", 200, 400), com.melox.player.model.LyricRuby("ou", 600, 900)))
        val ruby = lyricPhonetics(line.copy(words = listOf(rubyWord), romanizations = emptyList()))
        assertEquals(listOf(200L to 400L, 600L to 900L), ruby.wordAnnotations.single().single().words.map {
            it.startTimeMs to it.endTimeMs
        })
    }

    @Test
    fun multipleTimedPronunciationTracksRemainStackedOnEveryWrappedOriginalRow() {
        val original = LyricWord(0, 2_000, "今日", false)
        val line = LyricLine("main", 0, 2_000, null, listOf(original), null,
            romanizations = listOf(
                LyricAuxiliaryText("to day", words = listOf(original.copy(text = "to day"))),
                LyricAuxiliaryText("kyou ima", words = listOf(original.copy(text = "kyou ima"))),
            ))
        val phonetics = lyricPhonetics(line)
        assertEquals(listOf(listOf("to day", "kyou ima")), phonetics.wordTracks)
        assertEquals(listOf("to day\nkyou ima", ""), phonetics.wordRows(0, listOf("今", "日")))
        assertEquals(listOf("to day\nkyou ima"), phonetics.wordRows(0, listOf("今日")))
        assertEquals(listOf(original), line.words)
    }

    @Test
    fun duplicatePronunciationPrefersReliableWordTimingRegardlessOfTrackOrder() {
        val originals = listOf(LyricWord(0, 1_000, "君", false), LyricWord(1_000, 2_000, "を", false))
        val untimed = LyricAuxiliaryText("kimi wo")
        val timed = untimed.copy(words = listOf(
            originals[0].copy(text = "kimi", hasTrailingSpace = true), originals[1].copy(text = "wo")))
        for (tracks in listOf(listOf(untimed, timed), listOf(timed, untimed))) {
            val line = LyricLine("main", 0, 2_000, null, originals, null, romanizations = tracks)
            val phonetics = lyricPhonetics(line)
            assertEquals(listOf("kimi", "wo"), phonetics.words)
            assertTrue(phonetics.captions.isEmpty())
            assertEquals(tracks, line.romanizations)
        }
    }

    @Test
    fun sparsePronunciationTracksKeepTheirSlotsAcrossSharedShapingClusters() {
        val originals = listOf(LyricWord(0, 1_000, "f", false), LyricWord(1_000, 2_000, "i", false))
        val line = LyricLine("main", 0, 2_000, null, originals, null,
            romanizations = listOf(
                LyricAuxiliaryText("eff eye", words = listOf(
                    originals[0].copy(text = "eff", hasTrailingSpace = true), originals[1].copy(text = "eye"))),
                LyricAuxiliaryText("ee", words = listOf(originals[1].copy(text = "ee"))),
            ))
        val phonetics = lyricPhonetics(line)
        assertEquals(listOf(listOf("eff", ""), listOf("eye", "ee")), phonetics.wordTracks)
        assertEquals(listOf(listOf("eff", "")), phonetics.wordTrackRows(0, listOf("f")))
        assertEquals(listOf(listOf("eye", "ee")), phonetics.wordTrackRows(1, listOf("i")))
        assertEquals(listOf("eff eye", "ee"),
            lyricMergePhoneticTracks(phonetics.wordAnnotations[0], phonetics.wordAnnotations[1]).map { it.text })
        val merged = lyricMergePhoneticTracks(phonetics.wordAnnotations[0], phonetics.wordAnnotations[1])
        assertEquals(listOf(0L to 1_000L, 1_000L to 2_000L), merged[0].words.map { it.startTimeMs to it.endTimeMs })
        assertEquals(listOf(1_000L to 2_000L), merged[1].words.map { it.startTimeMs to it.endTimeMs })
        assertEquals(listOf("eff", "eye\nee"), phonetics.words)
        assertEquals(listOf("eff"), phonetics.wordRows(0, listOf("f")))
    }

    @Test
    fun suppliedHiirogekkaBuildsPronunciationForEveryVoiceAndNarrowRow() {
        val raw = requireNotNull(javaClass.getResource("/lyrics/hiirogekka-anniversary.ttml")).readText()
        val doc = requireNotNull(LyricsParser.parse(raw, LyricsSource.SIDECAR))
        for (line in doc.lines) {
            val phonetics = com.melox.player.ui.screen.playback.lyricPhonetics(line)
            assertTrue(phonetics.hasText)
            assertEquals(line.words.size, phonetics.words.size)
            for (caption in phonetics.captions) {
                for (length in listOf(1, 5, 12)) {
                    val rows = com.melox.player.ui.screen.playback.lyricGraphemeRanges(line.displayText)
                        .chunked(length).map { ranges -> line.displayText.substring(ranges.first().first, ranges.last().last + 1) }
                    val projected = com.melox.player.ui.screen.playback.lyricPhoneticRows(caption, rows)
                    assertEquals(caption.filterNot(Char::isWhitespace), projected.joinToString("").filterNot(Char::isWhitespace))
                }
            }
        }
    }

    @Test
    fun suppliedHiirogekkaPreservesCompleteTracksWordTimesAndSingerAlignment() {
        val raw = requireNotNull(javaClass.getResource("/lyrics/hiirogekka-anniversary.ttml")).readText()
        val doc = requireNotNull(LyricsParser.parse(raw, LyricsSource.SIDECAR))
        val primary = doc.lines.filterNot { it.isBackground }
        val backgrounds = doc.lines.filter { it.isBackground }
        assertEquals(LyricsFormat.TTML, doc.format)
        assertEquals("ja", doc.language)
        assertEquals(177, primary.size)
        assertEquals(19, backgrounds.size)
        assertEquals(1_318, doc.lines.sumOf { it.words.size })
        assertEquals(listOf("v1", "v2"), doc.agents.map { it.id })
        assertTrue(doc.isDuet)
        assertEquals(100, primary.count { it.agent == "v1" })
        assertEquals(77, primary.count { it.agent == "v2" })
        assertEquals("緋色月下、狂咲ノ絶　-1st Anniversary Remix-", doc.metadata["musicName"]?.first())
        assertEquals("Sacred Factor", doc.metadata["album"]?.single())
        assertEquals((1..177).map { "L$it" }.toSet(), primary.map { it.key }.toSet())
        doc.lines.forEachIndexed { index, line ->
            assertTrue(line.endTimeMs > line.startTimeMs)
            assertEquals(listOf("en", "zh-Hans"), line.translations.map { it.language })
            assertEquals("ja-Latn", line.romanizations.single().language)
            assertTrue(line.romanizations.single().words.isNotEmpty())
            assertEquals(line.translations[1].text,
                line.translationFor(LyricsTranslationLanguage.AUTO, "zh-CN"))
            assertEquals(line.translations[0].text,
                line.translationFor(LyricsTranslationLanguage.ENGLISH, "zh-CN"))
            assertEquals(if (line.agent == "v1") LyricAlignment.START else LyricAlignment.END,
                doc.lineAlignment(index))
        }
        val first = primary.first()
        assertEquals("私の中の", first.displayText)
        assertEquals(1_600L, first.startTimeMs)
        assertEquals(2_540L, first.endTimeMs)
        assertEquals(listOf(1_600L, 1_970L, 2_070L, 2_340L), first.words.map { it.startTimeMs })
        assertEquals(listOf(1_970L, 2_070L, 2_340L, 2_540L), first.words.map { it.endTimeMs })
        assertEquals("wa ta shi no na ka no", first.romanization)
        assertEquals("Inside of me", first.translationFor(LyricsTranslationLanguage.ENGLISH, "zh"))
        assertEquals("我身体中的", first.translationFor(LyricsTranslationLanguage.AUTO, "zh"))
        assertEquals(334_480L, primary.last().endTimeMs)
        val embedded = requireNotNull(parseEmbeddedLyrics(listOf(raw), 340_000L))
        assertEquals(doc.lines, embedded.lines)
    }

    @Test
    fun suppliedHiirogekkaKeepsOverlappingVoicesAndBackgroundAuxiliariesIndependent() {
        val raw = requireNotNull(javaClass.getResource("/lyrics/hiirogekka-anniversary.ttml")).readText()
        val doc = requireNotNull(LyricsParser.parse(raw, LyricsSource.SIDECAR))
        val overlap = doc.playbackFrame(2_400L)
        assertEquals(listOf("L1", "L2"), overlap.activeLineIndices.map { doc.lines[it].key })
        val anchor = overlap.anchorIndex
        assertEquals(listOf("L2"), doc.playbackFrame(2_540L).activeLineIndices.map { doc.lines[it].key })
        assertEquals(anchor, doc.playbackFrame(2_540L).anchorIndex)
        assertTrue(doc.lines[0].revealProgress(2_400L, false) > 0f)
        assertTrue(doc.lines[1].revealProgress(2_400L, false) < 1f)
        assertEquals(overlap, doc.playbackFrame(2_400L))
        assertEquals(emptyList<Int>(), doc.playbackFrame(0).activeLineIndices)
        val main = doc.lines.single { it.key == "L74" && !it.isBackground }
        val bg = doc.lines.single { it.key == "L74" && it.isBackground }
        assertEquals(97_570L, main.startTimeMs)
        assertEquals(103_180L, bg.startTimeMs)
        assertEquals(103_890L, bg.endTimeMs)
        assertFalse(main.displayText.contains("("))
        assertFalse(main.translations[0].text.contains("With you"))
        assertFalse(main.romanization.orEmpty().contains("("))
        assertEquals("(With you)", bg.translationFor(LyricsTranslationLanguage.ENGLISH, "zh"))
        assertEquals("(剜心)", bg.translationFor(LyricsTranslationLanguage.AUTO, "zh"))
        assertEquals("(e gu ru)", bg.romanization)
        val sustained = doc.lines.single { it.key == "L99" && !it.isBackground }
        assertEquals(235_145L, sustained.endTimeMs)
        assertEquals(232_790L, sustained.words.last().endTimeMs)
        val backgroundFrame = doc.playbackFrame(234_000L)
        assertTrue(backgroundFrame.activeLineIndices.any { doc.lines[it].key == "L99" && doc.lines[it].isBackground })
        assertTrue(backgroundFrame.activeLineIndices.any { doc.lines[it].key == "L99" && !doc.lines[it].isBackground })
    }

    @Test
    fun translationLanguageSelectsOneTaggedTrackWithoutChangingOriginalOrRomanization() {
        val line = document("""<p begin="1" end="5"><span begin="1" end="5">君の名は</span>
            <span ttm:role="x-roman" xml:lang="ja-Latn">ki mi no na wa</span>
            <span ttm:role="x-translation" xml:lang="en-US">Your name</span>
            <span ttm:role="x-translation" xml:lang="zh-Hans">你的名字</span></p>""").lines.single()
        val original = line.copy()
        assertEquals("你的名字", line.translationFor(LyricsTranslationLanguage.AUTO, "zh-CN"))
        assertEquals("Your name", line.translationFor(LyricsTranslationLanguage.AUTO, "en-GB"))
        assertEquals("Your name", line.translationFor(LyricsTranslationLanguage.ENGLISH, "zh-CN"))
        assertEquals("你的名字", line.translationFor(LyricsTranslationLanguage.CHINESE, "en-US"))
        assertEquals("Your name\n你的名字", line.translationFor(LyricsTranslationLanguage.ALL, "zh-CN"))
        assertEquals(original, line)
        assertEquals("ki mi no na wa", line.romanization)
        assertEquals(1_000L, line.words.single().startTimeMs)
    }

    @Test
    fun chineseAutoSelectionDoesNotShowAnEnglishTrackThatWasNotRecognizedAsRomanization() {
        val line = document("""<p begin="1" end="5">だけ</p>""").lines.single().copy(
            translation = "da ke\n仅此而已",
            translations = listOf(LyricAuxiliaryText("da ke", "en"),
                LyricAuxiliaryText("仅此而已", "zh-Hans")),
        )
        assertEquals("仅此而已", line.translationFor(LyricsTranslationLanguage.AUTO, "zh-CN"))
        assertEquals("da ke", line.translationFor(LyricsTranslationLanguage.AUTO, "en-US"))
        assertEquals("da ke\n仅此而已", line.translationFor(LyricsTranslationLanguage.ALL, "zh-CN"))
    }

    @Test
    fun translationLanguageFallbackAndVariantsAreIndependentOfTrackOrder() {
        val line = document("""<p begin="1" end="5">Main</p>""").lines.single().copy(
            translations = listOf(LyricAuxiliaryText("Traditional", "zh-Hant"),
                LyricAuxiliaryText("English", "en"), LyricAuxiliaryText("Simplified", "zh-Hans")),
        )
        for (tracks in listOf(line.translations, line.translations.reversed())) {
            val reordered = line.copy(translations = tracks)
            assertEquals("Simplified", reordered.translationFor(LyricsTranslationLanguage.AUTO, "zh-CN"))
            assertEquals("Traditional", reordered.translationFor(LyricsTranslationLanguage.AUTO, "zh-Hant"))
            assertEquals("English", reordered.translationFor(LyricsTranslationLanguage.AUTO, "fr-FR"))
            assertEquals("English", reordered.copy(translations = tracks.filterNot {
                it.language.orEmpty().startsWith("zh")
            }).translationFor(LyricsTranslationLanguage.CHINESE, "zh-CN"))
        }
    }

    @Test
    fun translationSelectionDeduplicatesNormalizedTagsAndPreservesUntypedMultilineLyrics() {
        val line = document("""<p begin="1" end="5">Main</p>""").lines.single()
        val tagged = line.copy(translations = listOf(
            LyricAuxiliaryText("First\nSecond", "ZH_hans"),
            LyricAuxiliaryText(" First\nSecond ", "zh-Hans"),
            LyricAuxiliaryText("English", "en"), LyricAuxiliaryText(" ", "zh"),
        ))
        assertEquals("First\nSecond", tagged.translationFor(LyricsTranslationLanguage.AUTO, "zh-CN"))
        assertEquals("First\nSecond\nEnglish", tagged.translationFor(LyricsTranslationLanguage.ALL, "zh"))
        val untagged = line.copy(translations = listOf(LyricAuxiliaryText("First\nSecond"),
            LyricAuxiliaryText("Third")))
        assertNull(untagged.translationFor(LyricsTranslationLanguage.AUTO, "zh-CN"))
        assertEquals("First\nSecond", untagged.translationFor(LyricsTranslationLanguage.AUTO, "en-US"))
        assertEquals("First\nSecond", untagged.translationFor(LyricsTranslationLanguage.ENGLISH, "zh-CN"))
        assertNull(untagged.translationFor(LyricsTranslationLanguage.CHINESE, "zh-CN"))
        assertEquals("First\nSecond\nThird", untagged.translationFor(LyricsTranslationLanguage.ALL, "zh-CN"))
        for (preference in LyricsTranslationLanguage.entries) {
            assertEquals("Legacy\ntext", line.copy(translation = "Legacy\ntext")
                .translationFor(preference, "zh-CN"))
            assertNull(line.translationFor(preference, "zh-CN"))
        }
        assertEquals("Untyped", line.copy(translations = listOf(LyricAuxiliaryText("French", "fr"),
            LyricAuxiliaryText("Untyped"))).translationFor(LyricsTranslationLanguage.AUTO, "zh"))
        val chineseAndUnclassifiedReading = line.copy(translations = listOf(
            LyricAuxiliaryText("da ke"), LyricAuxiliaryText("仅此而已")))
        assertEquals("仅此而已", chineseAndUnclassifiedReading.translationFor(
            LyricsTranslationLanguage.AUTO, "zh-CN"))
    }

    @Test
    fun duetKeepsSingerSidesAcrossOrderChangesAndCentersGroupVocals() {
        val doc = document("""<p begin="1" end="4" ttm:agent="v2">Second</p>
            <p begin="2" end="8" ttm:agent="v1">First
              <span ttm:role="x-bg" begin="3" end="5">Echo</span></p>
            <p begin="6" end="7" ttm:agent="all">Together</p>
            <p begin="9" end="10" ttm:agent="v2">Second again</p>""",
            head = """<ttm:agent xml:id="v1" type="person"/>
                <ttm:agent xml:id="v2" type="person"/><ttm:agent xml:id="all" type="group"/>""")

        assertTrue(doc.isDuet)
        assertEquals(listOf(LyricAlignment.END, LyricAlignment.START, LyricAlignment.START,
            LyricAlignment.CENTER, LyricAlignment.END), doc.lines.indices.map(doc::lineAlignment))
        assertEquals(listOf(0, 1, 2), doc.playbackFrame(3_500L).activeLineIndices)
        assertEquals(1, doc.playbackFrame(3_500L).anchorIndex)
    }

    @Test
    fun backgroundsGroupsAndDuplicateAgentDeclarationsDoNotCreateADuet() {
        val head = """<ttm:agent xml:id="v1" type="person"/><ttm:agent xml:id="v1" type="person"/>
            <ttm:agent xml:id="all" type="group"/>"""
        for (body in listOf(
            """<p begin="1" end="8" ttm:agent="v1">Main
                <span ttm:role="x-bg" ttm:agent="v2" begin="3" end="4">Echo</span></p>""",
            """<p begin="1" end="4" ttm:agent="v1">Main</p>
                <p begin="4" end="8" ttm:agent="all">Together</p>""",
            """<p begin="1" end="4">Unknown</p><p begin="4" end="8" ttm:agent="v1">Main</p>""",
        )) assertFalse(document(body, head).isDuet)
    }

    @Test
    fun overlappingVoicesHighlightIndependentlyWithoutBackwardScrollingWhenShortPartEnds() {
        val doc = document("""<p begin="1" end="8" ttm:agent="v1"><span begin="1" end="8">Long</span></p>
            <p begin="3" end="4" ttm:agent="v2"><span begin="3" end="4">Short</span></p>
            <p begin="9" end="10" ttm:agent="v2">Next</p>""")
        val overlap = doc.playbackFrame(3_500L)
        val afterShort = doc.playbackFrame(4_000L)
        assertEquals(listOf(0, 1), overlap.activeLineIndices)
        assertEquals(listOf(0), afterShort.activeLineIndices)
        assertEquals(1, overlap.anchorIndex)
        assertEquals(overlap.anchorIndex, afterShort.anchorIndex)
        assertEquals(listOf(0, 1), afterShort.anchorLineIndices)
        assertEquals(0.5f, doc.lines[1].revealProgress(3_500L, false), 0.001f)
        assertEquals(2.5f / 7f, doc.lines[0].revealProgress(3_500L, false), 0.001f)
        for ((position, expected) in listOf(9_500L to listOf(2), 2_000L to listOf(0),
            3_500L to listOf(0, 1), 0L to emptyList(), 10_000L to emptyList())) {
            assertEquals(expected, doc.playbackFrame(position).activeLineIndices)
        }
    }

    @Test
    fun simultaneousStartsAndSameSingerOverlapsRetainEveryActiveLine() {
        val doc = document("""<p begin="1" end="8" ttm:agent="v1">One</p>
            <p begin="1" end="4" ttm:agent="v2">Two</p>
            <p begin="1" end="6" ttm:agent="v3">Three</p>""")
        assertEquals(listOf(0, 1, 2), doc.playbackFrame(1_000L).activeLineIndices)
        assertEquals(listOf(0, 1, 2), doc.playbackFrame(5_000L).anchorLineIndices)
        assertEquals(listOf(0, 2), doc.playbackFrame(5_000L).activeLineIndices)
        assertEquals(0, doc.playbackFrame(7_000L).anchorIndex)
        val solo = document("""<p begin="1" end="8" ttm:agent="v1">Long</p>
            <p begin="3" end="4" ttm:agent="v1">Short</p>""")
        assertFalse(solo.isDuet)
        assertEquals(listOf(0, 1), solo.playbackFrame(3_000L).activeLineIndices)
    }

    @Test
    fun duetCenteringAvailabilityUsesOnlyTheCurrentMediaDocument() {
        val duet = document("""<p begin="1" end="3" ttm:agent="v1">One</p>
            <p begin="4" end="6" ttm:agent="v2">Two</p>""")
        val state = LyricsUiState.Available(duet, mediaId = "current")
        assertTrue(state.isDuetForMedia("current"))
        assertFalse(state.isDuetForMedia("next"))
        assertFalse(state.isDuetForMedia(null))
        assertFalse(LyricsUiState.Loading.isDuetForMedia("current"))
        assertFalse(LyricsUiState.Unavailable.isDuetForMedia("current"))
        assertFalse(LyricsUiState.Available(document("""<p begin="1" end="3">Solo</p>"""),
            mediaId = "current").isDuetForMedia("current"))
    }

    @Test
    fun lrcPlaybackFramesPreserveTheExistingSingleLineFocusPolicy() {
        val doc = LyricsParser.parse("[00:01]First\n[00:03]\n[00:06]Second", LyricsSource.SIDECAR)!!
        assertFalse(doc.isDuet)
        for (position in listOf(0L, 1_000L, 2_999L, 3_000L, 5_000L, 6_000L, 20_000L)) {
            val current = doc.currentLineIndex(position)
            assertEquals(if (current >= 0) listOf(current) else emptyList<Int>(),
                doc.playbackFrame(position).activeLineIndices)
            assertEquals(doc.focusLineIndex(position), doc.playbackFrame(position).anchorIndex)
        }
    }

    private fun document(body: String, head: String = "", attributes: String = "") =
        requireNotNull(LyricsParser.parse(
            """<tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:tts="http://www.w3.org/ns/ttml#styling"
                xmlns:itunes="http://itunes.apple.com/lyric-ttml-extensions"
                xmlns:amll="http://www.example.com/ns/amll" $attributes>
                <head><metadata>$head</metadata></head><body>$body</body></tt>""",
            LyricsSource.SIDECAR,
        ))

    @Test
    fun allWikiTimestampFormsApplyToLinesWordsAndDurations() {
        val cases = listOf(
            "45" to 45_000L, "12.5" to 12_500L, "120.456" to 120_456L,
            "01:30" to 90_000L, "2:45.500" to 165_500L, "00:15.5" to 15_500L,
            "01:02:03" to 3_723_000L, "1:4.150" to 64_150L,
            "0:00.000" to 0L, "00012.050" to 12_050L,
            "0:0:12.123" to 12_123L, "000:001:000.001" to 60_001L,
            "12.123456" to 12_123L,
        )
        for ((time, expected) in cases) {
            val line = document("""<p begin="$time" dur="1.25"><span begin="$time" dur="0.5">Word</span></p>""")
                .lines.single()
            assertEquals(time, expected, line.startTimeMs)
            assertEquals(time, expected + 1_250L, line.endTimeMs)
            assertEquals(time, expected, line.words.single().startTimeMs)
            assertEquals(time, expected + 500L, line.words.single().endTimeMs)
            if (expected > 0L) {
                val ended = document("""<p begin="0" end="$time"><span begin="0" end="$time">End</span></p>""")
                    .lines.single()
                assertEquals(time, expected, ended.endTimeMs)
                assertEquals(time, expected, ended.words.single().endTimeMs)
                val duration = document("""<p begin="1" dur="$time">Duration</p>""").lines.single()
                assertEquals(time, expected + 1_000L, duration.endTimeMs)
            }
        }
    }

    @Test
    fun plainSecondsBeforeOneMinuteRemainInBothSources() {
        val raw = """<tt><body><p begin="12.300" end="13.500">Before</p>
            <p begin="59.900" end="1:00.100">Across</p>
            <p begin="1:02.300" end="65">After</p></body></tt>"""
        for (source in LyricsSource.entries) {
            val doc = requireNotNull(LyricsParser.parse(raw, source))
            assertEquals(listOf("Before", "Across", "After"), doc.lines.map { it.displayText })
            assertEquals(listOf(12_300L, 59_900L, 62_300L), doc.lines.map { it.startTimeMs })
            assertEquals(0, doc.currentLineIndex(12_500L))
            assertEquals(1, doc.currentLineIndex(60_000L))
        }
        assertEquals(3, parseEmbeddedLyrics(listOf(raw), 70_000L)!!.lines.size)
    }

    @Test
    fun fractionalSecondsDoNotLoseAMillisecondToFloatingPoint() {
        for (millisecond in 0..999) {
            val stamp = "1.${millisecond.toString().padStart(3, '0')}"
            val line = document("""<p begin="$stamp" end="2.1">Precise</p>""").lines.single()
            assertEquals(stamp, 1_000L + millisecond, line.startTimeMs)
        }
    }

    @Test
    fun malformedOrOverflowTimesDoNotCreateBogusLines() {
        for (time in listOf("NaN", "Infinity", "1e3", "-1", "1::2", "1:NaN", "1:2:3:4:5",
            "1:2:3:bad", "999999999999999999999999999", "1:99999999999999999999999")) {
            val doc = document("""<p begin="$time" end="10">Invalid</p><p begin="11" end="12">Valid</p>""")
            assertEquals(time, listOf("Valid"), doc.lines.map { it.displayText })
        }
    }

    @Test
    fun prefixedNamespaceAndBomDoNotPreventReading() {
        val doc = LyricsParser.parse("""\uFEFF<t:tt xmlns:t="http://www.w3.org/ns/ttml"><t:body>
            <t:p begin="1.2" end="2.3">Namespaced</t:p></t:body></t:tt>""".replace("\\uFEFF", "\uFEFF"),
            LyricsSource.SIDECAR)!!
        assertEquals(LyricsFormat.TTML, doc.format)
        assertEquals("Namespaced", doc.lines.single().displayText)
    }

    @Test
    fun lineAuxiliariesNeverLeakIntoMainTextAndAllLanguagesSurvive() {
        val line = document("""<p begin="1" end="3" itunes:key="L1" ttm:agent="singer">
            Hello <span ttm:role="x-translation" xml:lang="zh-Hans" begin="1" end="3">你好</span>world
            <span ttm:role="x-translation" xml:lang="fr">Bonjour</span>
            <span ttm:role="x-roman" xml:lang="en-Latn">he lo</span></p>""",
            attributes = "xml:lang=\"en\" itunes:timing=\"Word\"",
        ).lines.single()
        assertEquals("Hello world", line.displayText)
        assertTrue(line.words.isEmpty())
        assertEquals("你好\nBonjour", line.translation)
        assertEquals(listOf("zh-Hans", "fr"), line.translations.map { it.language })
        assertEquals("he lo", line.romanizations.single().text)
        assertEquals("he lo", line.romanization)
        assertEquals("en", line.language)
        assertEquals("singer", line.agent)
        assertEquals("L1", line.key)
    }

    @Test
    fun wordModeDependsOnContentAndNestedWrappersAreNotDuplicated() {
        val line = document("""<p begin="1" end="4"><span begin="1" end="4">
            <span begin="1" end="2">Hello </span><span begin="2" end="4">world</span>
            </span></p>""", attributes = "itunes:timing=\"Line\"",
        ).lines.single()
        assertEquals("Hello world", line.displayText)
        assertEquals(2, line.words.size)
    }

    @Test
    fun indentationDoesNotInventSpacesAndInlineSpacesRemain() {
        val lines = document("""<p begin="1" end="3">
            <span begin="1" end="2">你</span>
            <span begin="2" end="3">好</span>
            </p><p begin="4" end="6"><span begin="4" end="5">Hello</span> <span begin="5" end="6">world</span></p>
            <p begin="7" end="9"><span begin="7" end="8">Hello </span>
            <span begin="8" end="9">world</span></p>""").lines
        assertEquals(listOf("你好", "Hello world", "Hello world"), lines.map { it.displayText })
    }

    @Test
    fun backgroundVocalsHaveIndependentTimingAndAuxiliaries() {
        val lines = document("""<p begin="1" end="8" ttm:agent="v1">
            <span begin="1" end="8">Main</span>
            <span ttm:role="x-bg"><span begin="3" end="4">(Back </span><span begin="4" end="5">voice)</span>
              <span ttm:role="x-translation" xml:lang="zh">和声</span>
              <span ttm:role="x-roman">back voice</span></span>
            <span ttm:role="x-translation">主唱</span></p>""").lines
        assertEquals(listOf("Main", "(Back voice)"), lines.map { it.displayText })
        assertEquals(listOf(false, true), lines.map { it.isBackground })
        assertEquals(8_000L, lines[0].endTimeMs)
        assertEquals(3_000L, lines[1].startTimeMs)
        assertEquals(5_000L, lines[1].endTimeMs)
        assertEquals("主唱", lines[0].translation)
        assertEquals("和声", lines[1].translation)
        assertEquals("back voice", lines[1].romanizations.single().text)
    }

    @Test
    fun backgroundOnlyAndUntimedBackgroundContainersAreReadable() {
        val lines = document("""<p begin="1" end="5"><span ttm:role="x-bg" begin="2" end="4">(Echo)</span></p>
            <p begin="6" end="8"><span ttm:role="x-bg">(Again)</span></p>""").lines
        assertEquals(listOf("(Echo)", "(Again)"), lines.map { it.displayText })
        assertTrue(lines.all { it.isBackground && it.words.isEmpty() })
        assertEquals(2_000L, lines[0].startTimeMs)
        assertEquals(8_000L, lines[1].endTimeMs)
    }

    @Test
    fun headTranslationsAndTransliterationsLinkByKeyIncludingBackground() {
        val line = """<p begin="1" end="5" itunes:key="L1"><span begin="1" end="5">主</span>
            <span ttm:role="x-bg"><span begin="2" end="3">(和)</span></span>
            <span ttm:role="x-translation" xml:lang="en">Inline</span></p>"""
        val head = """<iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <translations><translation type="subtitle" xml:lang="zh-Hans">
              <text for="L1"><span begin="1" end="5">主译</span><span ttm:role="x-bg">和译</span></text>
              <text for="L99">Unmatched</text></translation>
              <translation type="replacement" xml:lang="zh-Hant"><text for="L1">主譯</text></translation></translations>
            <transliterations><transliteration xml:lang="ja-Latn"><text for="L1">
              <span begin="1" end="5">shu</span><span ttm:role="x-bg"><span begin="2" end="3">wa</span></span>
              </text></transliteration></transliterations></iTunesMetadata>"""
        val lines = document(line, head).lines
        assertEquals("Inline\n主译\n主譯", lines[0].translation)
        assertEquals(listOf("en", "zh-Hans", "zh-Hant"), lines[0].translations.map { it.language })
        assertEquals("主译", lines[0].translationFor(LyricsTranslationLanguage.AUTO, "zh-CN"))
        assertEquals("Inline", lines[0].translationFor(LyricsTranslationLanguage.ENGLISH, "zh-CN"))
        assertEquals("和译", lines[1].translationFor(LyricsTranslationLanguage.AUTO, "zh-CN"))
        assertEquals("主译", lines[0].translations[1].words.single().text)
        assertEquals("shu", lines[0].romanizations.single().text)
        assertEquals("和译", lines[1].translation)
        assertEquals("wa", lines[1].romanizations.single().words.single().text)
        assertEquals("ja-Latn", lines[1].romanizations.single().language)
    }

    @Test
    fun rubyReadsBaseTextAndRetainsMultipleTimedPronunciations() {
        val line = document("""<p begin="1" end="3">
            <span tts:ruby="container"><span tts:ruby="base">所</span>
              <span tts:ruby="textContainer"><span tts:ruby="text" begin="1.1" end="1.2">しょ</span></span></span>
            <span tts:ruby="container"><span tts:ruby="base">詮</span>
              <span tts:ruby="textContainer"><span tts:ruby="text" begin="1.2" end="1.3">せ</span>
                <span tts:ruby="text" begin="1.3" end="1.4">ん</span></span></span>
            </p>""").lines.single()
        assertEquals("所詮", line.displayText)
        assertEquals(2, line.words.size)
        assertEquals(1_100L, line.words[0].startTimeMs)
        assertEquals(1_400L, line.words[1].endTimeMs)
        assertEquals(listOf("せ", "ん"), line.words[1].ruby.map { it.text })
        assertEquals(listOf(1_200L, 1_300L), line.words[1].ruby.map { it.startTimeMs })
    }

    @Test
    fun explicitOverlappingVoicesRemainActiveAfterTheLaterShortLineEnds() {
        val doc = document("""<p begin="1" end="8" ttm:agent="v1"><span begin="1" end="8">Long</span></p>
            <p begin="3" end="4" ttm:agent="v2"><span begin="3" end="4">Short</span></p>
            <p begin="3" end="6" ttm:agent="v3">Third</p>""")
        assertEquals(listOf(8_000L, 4_000L, 6_000L), doc.lines.map { it.endTimeMs })
        assertEquals(8_000L, doc.lines.first().words.single().endTimeMs)
        assertEquals(2, doc.currentLineIndex(5_000L))
        assertEquals(0, doc.currentLineIndex(7_000L))
        assertEquals(-1, doc.currentLineIndex(9_000L))
    }

    @Test
    fun rubyCanInheritTimingFromItsEnclosingWord() {
        val line = document("""<p begin="1" end="4"><span begin="2" end="3">
            <span tts:ruby="container"><span tts:ruby="base">字</span>
              <span tts:ruby="textContainer"><span tts:ruby="text">zi</span></span></span>
            </span></p>""").lines.single()
        val word = line.words.single()
        assertEquals("字", line.displayText)
        assertEquals("zi", word.ruby.single().text)
        assertEquals(2_000L, word.ruby.single().startTimeMs)
        assertEquals(3_000L, word.ruby.single().endTimeMs)
    }

    @Test
    fun backgroundRubyInheritsItsOwnContainerInsteadOfTheParagraph() {
        val lines = document("""<p begin="1" end="8">Main
            <span ttm:role="x-bg" begin="2" end="4"><span tts:ruby="container">
              <span tts:ruby="base">和</span><span tts:ruby="textContainer">
                <span tts:ruby="text">wa</span></span></span></span></p>""").lines
        val background = lines.single { it.isBackground }
        assertEquals("和", background.displayText)
        assertEquals(2_000L, background.words.single().startTimeMs)
        assertEquals(4_000L, background.words.single().endTimeMs)
        assertEquals(2_000L, background.words.single().ruby.single().startTimeMs)
        assertEquals(4_000L, background.words.single().ruby.single().endTimeMs)
        assertEquals("Main", lines.single { !it.isBackground }.displayText)
    }

    @Test
    fun metadataLanguageAndSongPartAreRetainedWithoutOffsettingTimes() {
        val doc = document("""<div begin="10" end="30" itunes:songPart="Verse">
            <p begin="12" end="15" itunes:key="L1" ttm:agent="v1">Text</p></div>""",
            head = """<ttm:title>Title</ttm:title><ttm:agent xml:id="v1" type="person"><ttm:name type="full">Singer</ttm:name></ttm:agent>
            <amll:meta key="artists" value="One"/><amll:meta key="artists" value="Two"/>
            <amll:meta key="album" value="Album"/><amll:meta key="ncmMusicId" value="123"/>
            <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal"><songwriters><songwriter>Writer</songwriter></songwriters></iTunesMetadata>""",
            attributes = "xml:lang=\"ja\"",
        )
        assertEquals(listOf("Title"), doc.metadata["musicName"])
        assertEquals(listOf("One", "Two"), doc.metadata["artists"])
        assertEquals("Singer", doc.agents.single().name)
        assertEquals("person", doc.agents.single().type)
        assertEquals(listOf("Writer"), doc.songwriters)
        assertEquals("ja", doc.language)
        assertEquals("Verse", doc.lines.single().songPart)
        assertEquals(12_000L, doc.lines.single().startTimeMs)
    }

    @Test
    fun onlyBodyParagraphsAreLyricsAndExternalEntitiesAreRejected() {
        val doc = document("""<p begin="1" end="2">Body</p>""", head = """<p begin="3" end="4">Head</p>""")
        assertEquals(listOf("Body"), doc.lines.map { it.displayText })
        assertNull(LyricsParser.parse("""<!DOCTYPE tt [<!ENTITY xxe SYSTEM "file:///missing">]>
            <tt><body><p begin="1" end="2">&xxe;</p></body></tt>""", LyricsSource.SIDECAR))
    }
}
