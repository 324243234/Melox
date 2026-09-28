package com.melox.player

import com.melox.player.data.repository.customFolderPrefixForDocumentId
import com.melox.player.data.repository.customFolderScopeForDocumentId
import com.melox.player.data.repository.customFolderScopeMatches
import com.melox.player.data.repository.exactLyricsSidecarCandidates
import com.melox.player.data.repository.folderMatchesPrefix
import com.melox.player.data.repository.isSupportedAudioDocument
import com.melox.player.data.repository.lyricsSourceOrder
import com.melox.player.data.repository.stableDocumentTrackId
import com.melox.player.data.repository.stableMediaStoreTrackId
import com.melox.player.model.LyricsFormat
import com.melox.player.model.LyricsSidecarFormatPriority
import com.melox.player.model.LyricsSource
import com.melox.player.model.LyricsSourcePriority
import com.melox.player.ui.viewmodel.shouldEmitScanCompletion
import com.melox.player.ui.viewmodel.shouldEmitScanNoChanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicScanLogicTest {
    @Test
    fun primaryDocumentTreeMapsToMediaStoreRelativePrefix() {
        assertEquals(
            "/Music/Albums",
            customFolderPrefixForDocumentId("primary:Music/Albums", sdkInt = 29),
        )
        assertEquals(
            "/storage/emulated/0/Music/Albums",
            customFolderPrefixForDocumentId("primary:Music/Albums", sdkInt = 28),
        )
        assertNull(customFolderPrefixForDocumentId("1234-5678:Music", sdkInt = 29))
    }

    @Test
    fun customFolderMatchesOnlyTheFolderAndItsDescendants() {
        assertTrue(
            folderMatchesPrefix(
                rawPath = "Music/Albums/",
                includesFileName = false,
                prefix = "/Music",
            ),
        )
        assertTrue(
            folderMatchesPrefix(
                rawPath = "/storage/emulated/0/Music/song.flac",
                includesFileName = true,
                prefix = "/storage/emulated/0/Music",
            ),
        )
        assertFalse(
            folderMatchesPrefix(
                rawPath = "Podcasts/",
                includesFileName = false,
                prefix = "/Music",
            ),
        )
        assertFalse(
            folderMatchesPrefix(
                rawPath = "Music2/song.flac",
                includesFileName = false,
                prefix = "/Music",
            ),
        )
    }

    @Test
    fun secondaryStorageTreeMatchesOnlyItsOwnMediaStoreVolume() {
        val scope = requireNotNull(
            customFolderScopeForDocumentId("1234-5678:Music/Albums", sdkInt = 29),
        )

        assertEquals("1234-5678", scope.volumeName)
        assertEquals("/Music/Albums", scope.folderPrefix)
        assertTrue(
            customFolderScopeMatches(
                scope = scope,
                volumeName = "1234-5678",
                rawPath = "Music/Albums/Live/",
                includesFileName = false,
                sdkInt = 29,
            ),
        )
        assertFalse(
            customFolderScopeMatches(
                scope = scope,
                volumeName = "external_primary",
                rawPath = "Music/Albums/Live/",
                includesFileName = false,
                sdkInt = 29,
            ),
        )
    }

    @Test
    fun safFallbackRecognizesAudioMimeTypesAndReferenceExtensions() {
        assertTrue(isSupportedAudioDocument("track.bin", "audio/x-custom"))
        assertTrue(isSupportedAudioDocument("track.ape", "application/octet-stream"))
        assertTrue(isSupportedAudioDocument("track.OPUS", null))
        assertFalse(isSupportedAudioDocument("cover.jpg", "image/jpeg"))
    }

    @Test
    fun directDocumentTrackIdsAreStableAndSeparatedFromMediaStoreIds() {
        val first = stableDocumentTrackId("content://documents/tree/primary%3AMusic/one.flac")
        val repeated = stableDocumentTrackId("content://documents/tree/primary%3AMusic/one.flac")
        val second = stableDocumentTrackId("content://documents/tree/primary%3AMusic/two.flac")

        assertEquals(first, repeated)
        assertTrue(first < 0L)
        assertTrue(second < 0L)
        assertFalse(first == second)
    }

    @Test
    fun secondaryVolumeTrackIdsDoNotCollideWithPrimaryRows() {
        assertEquals(42L, stableMediaStoreTrackId("external_primary", 42L))
        val secondary = stableMediaStoreTrackId("1234-5678", 42L)

        assertTrue(secondary > 0L)
        assertFalse(secondary == 42L)
        assertEquals(secondary, stableMediaStoreTrackId("1234-5678", 42L))
        assertFalse(secondary == stableMediaStoreTrackId("8765-4321", 42L))
    }

    @Test
    fun onlyExplicitUnchangedScansRequestNoChangesFeedback() {
        assertTrue(
            shouldEmitScanNoChanges(
                libraryChanged = false,
                notifyIfUnchanged = true,
            ),
        )
        assertFalse(
            shouldEmitScanNoChanges(
                libraryChanged = true,
                notifyIfUnchanged = true,
            ),
        )
        assertFalse(
            shouldEmitScanNoChanges(
                libraryChanged = false,
                notifyIfUnchanged = false,
            ),
        )
    }

    @Test
    fun onlyExplicitChangedScansRequestCompletionFeedback() {
        assertTrue(
            shouldEmitScanCompletion(
                libraryChanged = true,
                notifyUser = true,
            ),
        )
        assertFalse(
            shouldEmitScanCompletion(
                libraryChanged = false,
                notifyUser = true,
            ),
        )
        assertFalse(
            shouldEmitScanCompletion(
                libraryChanged = true,
                notifyUser = false,
            ),
        )
    }

    @Test
    fun sidecarCandidatesRequireTheExactAudioFileStem() {
        assertEquals(
            listOf(
                "Song Name.lrc" to LyricsFormat.LRC,
                "Song Name.ttml" to LyricsFormat.TTML,
            ),
            exactLyricsSidecarCandidates("Song Name.flac"),
        )
        assertEquals(
            listOf(
                "Song Name.ttml" to LyricsFormat.TTML,
                "Song Name.lrc" to LyricsFormat.LRC,
            ),
            exactLyricsSidecarCandidates(
                audioFileName = "Song Name.flac",
                formatPriority = LyricsSidecarFormatPriority.TTML,
            ),
        )
        assertFalse(
            exactLyricsSidecarCandidates("Song Name.flac")
                .any { (name, _) -> name == "Song Name (1).lrc" },
        )
        assertTrue(exactLyricsSidecarCandidates("Album/Song Name.flac").isEmpty())
        assertTrue(exactLyricsSidecarCandidates("Album\\Song Name.flac").isEmpty())
    }

    @Test
    fun lyricSourcePriorityKeepsTheOtherSourceAsFallback() {
        assertEquals(
            listOf(LyricsSource.EMBEDDED, LyricsSource.SIDECAR),
            lyricsSourceOrder(LyricsSourcePriority.EMBEDDED),
        )
        assertEquals(
            listOf(LyricsSource.SIDECAR, LyricsSource.EMBEDDED),
            lyricsSourceOrder(LyricsSourcePriority.SIDECAR),
        )
    }
}
