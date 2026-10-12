package com.melox.player

import com.melox.player.data.repository.embeddedLyricsCandidates
import com.melox.player.data.repository.parseEmbeddedLyrics
import com.melox.player.model.LyricsFormat
import com.melox.player.model.LyricsSource
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
    fun wordLyricsAliasesTakePriorityOverPlainLyricsLikeHalcyon() {
        for (key in listOf("TTML", "TTML LYRICS", "TTML_LYRIC", "TXXX:TTML", "ttmllyrics")) {
            val candidates = embeddedLyricsCandidates(linkedMapOf(
                "LYRICS" to arrayOf(lineLyrics),
                key to arrayOf(wordLyrics),
                "TITLE" to arrayOf("Ignored title"),
            ))
            assertEquals(listOf(wordLyrics, lineLyrics), candidates)
            assertEquals(LyricsFormat.TTML, parseEmbeddedLyrics(candidates, 30_000L)?.format)
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
}
