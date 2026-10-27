package com.melox.player

import com.melox.player.data.repository.embeddedLyricsCandidates
import com.melox.player.data.repository.parseEmbeddedLyrics
import com.melox.player.data.repository.exactLyricsSidecarCandidates
import com.melox.player.data.repository.readFirstSidecar
import com.melox.player.data.repository.lyricsSourceOrder
import com.melox.player.model.LyricsFormat
import com.melox.player.model.LyricsSource
import com.melox.player.model.LyricsFormatPriority
import com.melox.player.model.LyricsSourcePriority
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class EmbeddedLyricsReadTest {
    private val lineLyrics = "[00:01.00]Line lyrics\n[00:03.00]Next line"
    private val wordLyrics = """
        <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
          <p begin="1s" end="3s"><span begin="1s" end="2s">Word </span><span begin="2s" end="3s">lyrics</span></p>
        </div></body></tt>
    """.trimIndent()

    @Test
    fun lineToWordAndBackParsesCurrentTags() {
        var currentTags = lineLyrics
        fun load() = requireNotNull(parseEmbeddedLyrics(
            embeddedLyricsCandidates(mapOf("LYRICS" to arrayOf(currentTags))),
            durationMs = 30_000L,
        ))

        val line = load()
        assertEquals(LyricsFormat.LRC, line.format)
        assertEquals("Line lyrics", line.lines.first().text)
        currentTags = wordLyrics
        val word = load()
        assertEquals(LyricsFormat.TTML, word.format)
        assertEquals(2, word.lines.first().words.size)
        assertEquals(2_000L, word.lines.first().words.last().startTimeMs)
        currentTags = lineLyrics
        val restored = load()
        assertEquals(line, restored)
        assertEquals(LyricsSource.EMBEDDED, restored.source)
    }

    @Test
    fun embeddedFormatsFollowPreferenceRatherThanTagNames() {
        for (key in listOf("TTML", "TTML LYRICS", "TTML_LYRIC", "TXXX:TTML", "ttmllyrics")) {
            val candidates = embeddedLyricsCandidates(linkedMapOf(
                "LYRICS" to arrayOf(lineLyrics),
                key to arrayOf(wordLyrics),
                "TITLE" to arrayOf("Ignored title"),
            ))
            assertEquals(listOf(wordLyrics, lineLyrics), candidates)
            for (priority in LyricsFormatPriority.entries) {
                assertEquals(priority.format,
                    parseEmbeddedLyrics(candidates, 30_000L, formatPriority = priority)?.format)
                val mislabeled = embeddedLyricsCandidates(mapOf(
                    "LYRICS" to arrayOf(wordLyrics), key to arrayOf(lineLyrics)))
                assertEquals(priority.format,
                    parseEmbeddedLyrics(mislabeled, 30_000L, formatPriority = priority)?.format)
            }
        }
    }

    @Test
    fun sourcePriorityAlwaysPrecedesTheSharedFormatPriority() {
        val properties = mapOf("LYRICS" to arrayOf(lineLyrics), "TTML" to arrayOf(wordLyrics))
        val files = mapOf("song.lrc" to lineLyrics, "song.ttml" to wordLyrics)
        for (source in LyricsSourcePriority.entries) for (format in LyricsFormatPriority.entries) {
            fun load(embedded: List<String>) = lyricsSourceOrder(source).firstNotNullOfOrNull { candidate ->
                when (candidate) {
                    LyricsSource.EMBEDDED -> parseEmbeddedLyrics(embedded, 30_000L, formatPriority = format)
                    LyricsSource.SIDECAR -> readFirstSidecar(exactLyricsSidecarCandidates("song.flac", format),
                        30_000L, format, files::get)
                }
            }!!
            val selected = load(embeddedLyricsCandidates(properties))
            assertEquals(lyricsSourceOrder(source).first(), selected.source)
            assertEquals(format.format, selected.format)
            // A valid embedded LRC must beat an external TTML when embedded is preferred.
            val onlyLrcEmbedded = load(listOf(lineLyrics))
            assertEquals(lyricsSourceOrder(source).first(), onlyLrcEmbedded.source)
            assertEquals(if (source == LyricsSourcePriority.EMBEDDED) LyricsFormat.LRC else format.format,
                onlyLrcEmbedded.format)
            assertEquals(LyricsSource.SIDECAR, load(listOf("Not timed")).source)
        }
    }

    @Test
    fun sidecarFormatsUseParsedContentAndRecoverFromUnreadablePreferredFiles() {
        val mislabeled = mapOf("song.lrc" to wordLyrics, "song.ttml" to lineLyrics)
        for (priority in LyricsFormatPriority.entries) {
            val candidates = exactLyricsSidecarCandidates("song.flac", priority)
            val document = readFirstSidecar(candidates, 30_000L, priority, mislabeled::get)!!
            assertEquals(priority.format, document.format)
            for (failure in listOf("missing", "invalid", "unreadable")) {
                val fallback = readFirstSidecar(candidates, 30_000L, priority) { name ->
                    when {
                        name == candidates.first().first -> when (failure) {
                            "missing" -> null
                            "invalid" -> "Not timed"
                            else -> throw IOException("Unreadable preferred file")
                        }
                        name == candidates.last().first -> if (priority == LyricsFormatPriority.LRC) wordLyrics else lineLyrics
                        else -> null
                    }
                }!!
                assertEquals(if (priority == LyricsFormatPriority.LRC) LyricsFormat.TTML else LyricsFormat.LRC,
                    fallback.format)
            }
            assertThrows(IOException::class.java) {
                readFirstSidecar(candidates, 30_000L, priority) { throw IOException("Unreadable file") }
            }
        }
        assertThrows(CancellationException::class.java) {
            readFirstSidecar(exactLyricsSidecarCandidates("song.flac"), 30_000L) { throw CancellationException() }
        }
    }

    @Test
    fun missingOrUntimedTagsDoNotRetainPreviousLyrics() {
        assertNotNull(parseEmbeddedLyrics(listOf(wordLyrics), 30_000L))
        assertNull(parseEmbeddedLyrics(emptyList(), 30_000L))
        assertNull(parseEmbeddedLyrics(listOf("Untimed lyrics"), 30_000L))
        assertEquals(LyricsFormat.LRC,
            parseEmbeddedLyrics(listOf("Invalid TTML", lineLyrics), 30_000L)?.format)
    }

    @Test
    fun tagCandidatesKeepCompatibleKeysAndRejectUnreadableText() {
        for (key in listOf("USLT", "SYLT", "©lyr", "SYNCED_LYRICS", "UNSYNCED LYRICS")) {
            assertEquals(listOf(lineLyrics), embeddedLyricsCandidates(mapOf(
                key to arrayOf("", "  $lineLyrics  ", lineLyrics, "\uFFFD unreadable"),
                "ARTIST" to arrayOf("Ignored artist"),
            )))
        }
    }

    @Test
    fun translationTagsNeverReplaceTheBilingualMainTagRegardlessOfMapOrder() {
        val bilingual = "[00:19.640]Original\n[00:19.640]Translation"
        val translated = "[00:19.64]Translation"
        for (entries in listOf(
            listOf("LYRICS_TRANSLATED", "LYRICS", "NERI_LYRICS_TRANSLATED"),
            listOf("NERI_LYRICS_TRANSLATED", "LYRICS_TRANSLATED", "LYRICS"),
            listOf("LYRICS", "LYRICS_TRANSLATED", "NERI_LYRICS_TRANSLATED"),
        )) {
            val properties = entries.associateWith { arrayOf(if (it == "LYRICS") bilingual else translated) }
            val candidates = embeddedLyricsCandidates(properties)
            val translations = embeddedLyricsCandidates(properties, translation = true)
            assertEquals(listOf(bilingual), candidates)
            assertEquals(listOf(translated), translations)
            val line = parseEmbeddedLyrics(candidates, 30_000L, translations)!!.lines.single()
            assertEquals("Original", line.displayText)
            assertEquals("Translation", line.translation)
        }
    }

    @Test
    fun separateEmbeddedTranslationsMatchEffectiveTimestampsWithoutChangingWords() {
        val original = "[00:01.00]<00:01.00>First <00:02.00>line\n[00:04.00]Second line"
        val properties = mapOf(
            "TXXX:LYRICS" to arrayOf(original),
            "NERI_LYRICS_TRANSLATED" to arrayOf("[offset:100]\n[00:00.90]Translated\n[00:05.00]Unmatched"),
        )
        val candidates = embeddedLyricsCandidates(properties)
        val base = parseEmbeddedLyrics(candidates, 10_000L)!!
        val merged = parseEmbeddedLyrics(candidates, 10_000L,
            embeddedLyricsCandidates(properties, translation = true))!!
        assertEquals(base.lines.map { it.copy(translation = null) }, merged.lines.map { it.copy(translation = null) })
        assertEquals("Translated", merged.lines.first().translation)
        assertNull(merged.lines.last().translation)
        assertNull(parseEmbeddedLyrics(embeddedLyricsCandidates(mapOf(
            "LYRICS_TRANSLATED" to arrayOf("[00:01.00]Translation only"),
        )), 10_000L))
    }

    @Test
    fun splitSidecarsUseOnlyMatchingTranslationLinesAndKeepTheMainTimeline() {
        for (suffix in listOf("_trans.lrc", ".trans.lrc", ".zh.lrc")) {
            val original = "[00:01.00]Original\n[00:03.00]Next\n[00:05.00]Untranslated"
            val files = mapOf(
                "song.lrc" to original,
                "song$suffix" to "[offset:100]\n[00:00.90]Translation\n[00:02.90]Next translation\n[00:08.00]Extra",
            )
            val reads = mutableListOf<String>()
            val document = readFirstSidecar(exactLyricsSidecarCandidates("song.flac"), 10_000L) {
                reads += it
                files[it]
            }!!
            val base = readFirstSidecar(listOf("song.lrc" to LyricsFormat.LRC), 10_000L) {
                files[it].takeIf { it == original }
            }!!
            assertEquals(base.lines, document.lines.map { it.copy(translation = null) })
            assertEquals(listOf("Translation", "Next translation", null), document.lines.map { it.translation })
            assertEquals(LyricsSource.SIDECAR, document.source)
            assertFalse(reads.contains("song.ttml"))
        }
    }

    @Test
    fun existingBilingualLyricsSkipTranslationSidecarsAndTtmlNeverReadsThem() {
        for ((name, text) in listOf(
            "song.lrc" to "[00:01.00]Original\n[00:01.00]Existing translation",
            "song.ttml" to wordLyrics,
        )) {
            val reads = mutableListOf<String>()
            val document = readFirstSidecar(exactLyricsSidecarCandidates("song.flac",
                if (name.endsWith("ttml")) LyricsFormatPriority.TTML else LyricsFormatPriority.LRC,
            ), 10_000L) {
                reads += it
                if (it == name) text else error("Unnecessary sidecar read: $it")
            }!!
            assertEquals(listOf(name), reads)
            if (document.format == LyricsFormat.LRC) assertEquals("Existing translation", document.lines.first().translation)
        }
    }

    @Test
    fun invalidMissingOrUnreadableTranslationsLeaveOriginalLyricsAvailable() {
        for (translation in listOf(null, "Not timed", "[00:05.00]Unmatched", "[00:01.00]Original", "unreadable")) {
            val document = readFirstSidecar(exactLyricsSidecarCandidates("song.flac"), 10_000L) {
                when {
                    it == "song.lrc" -> "[00:01.00]Original"
                    translation == "unreadable" -> throw IOException("Unreadable translation")
                    else -> translation
                }
            }!!
            assertEquals("Original", document.lines.single().displayText)
            assertNull(document.lines.single().translation)
        }
        assertThrows(CancellationException::class.java) {
            readFirstSidecar(exactLyricsSidecarCandidates("song.flac"), 10_000L) {
                if (it == "song.lrc") "[00:01.00]Original" else throw CancellationException()
            }
        }
    }

    @Test
    fun partialBilingualLyricsReadACompanionOnlyToFillMissingTranslations() {
        val reads = mutableListOf<String>()
        val files = mapOf(
            "song.lrc" to "[00:01.00]Original\n[00:01.00]Existing translation\n[00:03.00]Next\n[00:05.00]",
            "song_trans.lrc" to "[00:01.00]Replacement\n[00:03.00]Missing translation\n[00:05.00]Do not fill a blank row",
        )
        val document = readFirstSidecar(exactLyricsSidecarCandidates("song.flac"), 10_000L) {
            reads += it
            files[it]
        }!!
        assertEquals(listOf("song.lrc", "song_trans.lrc"), reads)
        assertEquals(listOf("Existing translation", "Missing translation"), document.lines.map { it.translation })
        assertEquals(listOf(1_000L, 3_000L), document.lines.map { it.startTimeMs })
    }

    @Test
    fun translationOnlySidecarCannotBecomeMainLyricsAndFormatFallbackRemainsAvailable() {
        val files = mapOf("song_trans.lrc" to "[00:01.00]Translation only")
        val candidates = exactLyricsSidecarCandidates("song.flac")
        assertNull(readFirstSidecar(candidates, 10_000L, read = files::get))
        val document = readFirstSidecar(candidates, 10_000L, read = (files + ("song.ttml" to wordLyrics))::get)!!
        assertEquals(LyricsFormat.TTML, document.format)
        assertNull(document.lines.single().translation)
    }
}
