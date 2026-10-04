package com.melox.player.data.playlist

import com.melox.player.model.LocalPlaylist
import com.melox.player.model.MusicTrack
import com.melox.player.model.PlaylistTrackEntry
import com.melox.player.model.ResolvedPlaylistTrack
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistLogicTest {
    @Test
    fun snapshotRoundTripRetainsPlaylistAndUnavailableTrackMetadata() {
        val playlist = playlist(
            entries = listOf(
                entry("entry-1", track(1L, "Bravo", "content://music/1")),
                entry(
                    "entry-2",
                    track(2L, "Alpha", "content://music/2").copy(
                        albumArtist = "Album artist",
                        year = 2025,
                        trackNumber = 3,
                        discNumber = 2,
                        folderPath = "/Music/Test",
                        albumId = 91L,
                        mediaStoreId = 2L,
                        mimeType = "audio/flac",
                        bitrateBitsPerSecond = 1_411_200,
                        sampleRateHz = 96_000,
                        channelCount = 2,
                        bitDepth = 24,
                        audioPropertiesScanned = true,
                    ),
                ),
            ),
        )
        val bytes = ByteArrayOutputStream().also { output ->
            PlaylistSnapshotCodec.write(output, listOf(playlist))
        }.toByteArray()

        assertEquals(
            listOf(playlist),
            PlaylistSnapshotCodec.read(ByteArrayInputStream(bytes)),
        )
    }

    @Test
    fun snapshotRejectsChecksumCorruption() {
        val bytes = ByteArrayOutputStream().also { output ->
            PlaylistSnapshotCodec.write(
                output,
                listOf(playlist(entries = listOf(entry("entry", track(1L, "A", "uri:1"))))),
            )
        }.toByteArray()
        bytes[bytes.lastIndex - Long.SIZE_BYTES - 1] =
            (bytes[bytes.lastIndex - Long.SIZE_BYTES - 1].toInt() xor 1).toByte()

        assertThrows(IOException::class.java) {
            PlaylistSnapshotCodec.read(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun addingTracksPrependsNewEntriesAndPreservesNewSourceOrder() {
        val original = track(1L, "Original", "content://music/1")
        val second = track(2L, "Second", "content://music/2")
        val third = track(3L, "Third", "content://music/3")
        val playlist = playlist(entries = listOf(entry("existing", original)))
        val ids = ArrayDeque(listOf("new-2", "new-3"))

        val updated = addTracksToPlaylist(
            playlist = playlist,
            tracks = listOf(original.copy(title = "Duplicate"), second, third, second),
            nowEpochMillis = 200L,
            newEntryId = ids::removeFirst,
        )

        assertEquals(listOf("new-2", "new-3", "existing"), updated.entries.map { it.id })
        assertEquals(
            listOf(second.contentUri, third.contentUri, original.contentUri),
            updated.entries.map { it.trackSnapshot.contentUri },
        )
        assertEquals(200L, updated.updatedAtEpochMillis)
    }

    @Test
    fun addingOnlyExistingTracksLeavesPlaylistInstanceUntouched() {
        val original = track(1L, "Original", "content://music/1")
        val playlist = playlist(entries = listOf(entry("existing", original)))

        assertSame(
            playlist,
            addTracksToPlaylist(
                playlist = playlist,
                tracks = listOf(original),
                nowEpochMillis = 200L,
                newEntryId = { error("No entry should be created") },
            ),
        )
    }

    @Test
    fun resolutionUsesLiveMetadataOnlyForReadableCurrentLibraryRows() {
        val availableSnapshot = track(1L, "Old title", "content://music/1")
        val unavailableSnapshot = track(2L, "Missing title", "content://music/2")
        val liveTrack = availableSnapshot.copy(title = "New title")
        val resolved = resolvePlaylistTracks(
            playlist = playlist(
                entries = listOf(
                    entry("available", availableSnapshot),
                    entry("unavailable", unavailableSnapshot),
                ),
            ),
            libraryTracks = listOf(liveTrack),
            readableContentUris = setOf(liveTrack.contentUri),
        )

        assertTrue(resolved[0].available)
        assertEquals("New title", resolved[0].track.title)
        assertFalse(resolved[1].available)
        assertEquals("Missing title", resolved[1].track.title)
    }

    @Test
    fun playlistSortAddsCustomToEverySongsPageSortField() {
        val first = resolved(
            "first",
            track(1L, "Bravo", "uri:1").copy(
                dateAddedEpochSeconds = 30L,
                fileName = "c.mp3",
                fileSizeBytes = 200L,
                durationMs = 3_000L,
            ),
        )
        val second = resolved(
            "second",
            track(2L, "Alpha", "uri:2").copy(
                dateAddedEpochSeconds = 20L,
                fileName = "b.mp3",
                fileSizeBytes = 300L,
                durationMs = 1_000L,
            ),
        )
        val third = resolved(
            "third",
            track(3L, "Charlie", "uri:3").copy(
                dateAddedEpochSeconds = 10L,
                fileName = "a.mp3",
                fileSizeBytes = 100L,
                durationMs = 2_000L,
            ),
        )
        val tracks = listOf(first, second, third)

        assertEquals(listOf("first", "second", "third"), sortedIds(tracks, PlaylistSortField.CUSTOM))
        assertEquals(
            listOf("third", "second", "first"),
            sortPlaylistTracks(tracks, PlaylistSortConfig(descending = true)).map { it.entry.id },
        )
        assertEquals(listOf("second", "first", "third"), sortedIds(tracks, PlaylistSortField.TITLE))
        assertEquals(listOf("third", "second", "first"), sortedIds(tracks, PlaylistSortField.DATE_ADDED))
        assertEquals(listOf("third", "second", "first"), sortedIds(tracks, PlaylistSortField.FILE_NAME))
        assertEquals(listOf("third", "first", "second"), sortedIds(tracks, PlaylistSortField.FILE_SIZE))
        assertEquals(listOf("second", "third", "first"), sortedIds(tracks, PlaylistSortField.DURATION))
    }

    @Test
    fun playbackSelectionFiltersUnavailableEntriesAndKeepsDisplayedOrder() {
        val first = resolved("first", track(1L, "First", "uri:1"), available = true)
        val missing = resolved("missing", track(2L, "Missing", "uri:2"), available = false)
        val third = resolved("third", track(3L, "Third", "uri:3"), available = true)
        val displayed = listOf(third, missing, first)

        val (tracks, index) = requireNotNull(
            resolvePlaylistPlaybackSelection(displayed, selectedEntryId = "first"),
        )
        assertEquals(listOf(3L, 1L), tracks.map(MusicTrack::id))
        assertEquals(1, index)
        assertEquals(
            null,
            resolvePlaylistPlaybackSelection(displayed, selectedEntryId = "missing"),
        )
    }

    @Test
    fun removingSelectedEntriesCommitsOneOrderedPlaylistUpdate() {
        val playlist = playlist(
            entries = listOf(
                entry("first", track(1L, "First", "uri:1")),
                entry("second", track(2L, "Second", "uri:2")),
                entry("third", track(3L, "Third", "uri:3")),
            ),
        )

        val updated = removePlaylistEntries(
            playlist = playlist,
            entryIds = setOf("first", "third", "missing"),
            nowEpochMillis = 500L,
        )

        assertEquals(listOf("second"), updated.entries.map { it.id })
        assertEquals(500L, updated.updatedAtEpochMillis)
        assertSame(
            playlist,
            removePlaylistEntries(playlist, emptySet(), nowEpochMillis = 600L),
        )
    }

    @Test
    fun reorderAcceptsOnlyACompleteStableEntryPermutation() {
        val playlist = playlist(
            entries = listOf(
                entry("first", track(1L, "First", "uri:1")),
                entry("missing", track(2L, "Missing", "uri:2")),
                entry("third", track(3L, "Third", "uri:3")),
            ),
        )

        val updated = requireNotNull(
            reorderPlaylistEntries(
                playlist = playlist,
                orderedEntryIds = listOf("missing", "third", "first"),
                nowEpochMillis = 500L,
            ),
        )

        assertEquals(listOf("missing", "third", "first"), updated.entries.map { it.id })
        assertEquals(500L, updated.updatedAtEpochMillis)
        assertEquals(null, reorderPlaylistEntries(playlist, listOf("first", "third"), 600L))
        assertEquals(null, reorderPlaylistEntries(playlist, listOf("first", "first", "third"), 600L))
        assertEquals(null, reorderPlaylistEntries(playlist, listOf("first", "third", "extra"), 600L))
    }

    @Test
    fun reorderRejectsAStaleDraftAfterPlaylistContentsChange() {
        val original = playlist(
            entries = listOf(
                entry("first", track(1L, "First", "uri:1")),
                entry("second", track(2L, "Second", "uri:2")),
            ),
        )
        val changed = addTracksToPlaylist(
            playlist = original,
            tracks = listOf(track(3L, "New", "uri:3")),
            nowEpochMillis = 200L,
            newEntryId = { "new" },
        )

        assertEquals(
            null,
            reorderPlaylistEntries(changed, listOf("second", "first"), 300L),
        )
    }

    @Test
    fun newTracksRemainAtTheTopAfterManualReorder() {
        val original = playlist(
            entries = listOf(
                entry("first", track(1L, "First", "uri:1")),
                entry("second", track(2L, "Second", "uri:2")),
            ),
        )
        val reordered = requireNotNull(
            reorderPlaylistEntries(original, listOf("second", "first"), 200L),
        )

        val updated = addTracksToPlaylist(
            playlist = reordered,
            tracks = listOf(track(3L, "New", "uri:3")),
            nowEpochMillis = 300L,
            newEntryId = { "new" },
        )

        assertEquals(listOf("new", "second", "first"), updated.entries.map { it.id })
    }

    @Test
    fun descendingCustomDraftMapsBackToCanonicalPersistedOrder() {
        assertEquals(
            listOf("first", "third", "second"),
            canonicalPlaylistEntryOrder(
                displayedEntryIds = listOf("second", "third", "first"),
                descending = true,
            ),
        )
        assertEquals(
            listOf("second", "third", "first"),
            canonicalPlaylistEntryOrder(
                displayedEntryIds = listOf("second", "third", "first"),
                descending = false,
            ),
        )
    }

    @Test
    fun collectionReorderPreservesPlaylistObjectsAndSnapshotOrder() {
        val first = playlist(emptyList())
        val second = first.copy(id = "playlist-2", name = "Second")
        val third = first.copy(id = "playlist-3", name = "Third")
        val source = listOf(first, second, third)
        val ordered = requireNotNull(reorderPlaylists(source, listOf(third.id, first.id, second.id)))

        assertSame(third, ordered[0])
        assertSame(first, ordered[1])
        assertSame(second, ordered[2])
        val output = ByteArrayOutputStream()
        PlaylistSnapshotCodec.write(output, ordered)
        assertEquals(ordered, PlaylistSnapshotCodec.read(ByteArrayInputStream(output.toByteArray())))
        assertSame(source, reorderPlaylists(source, source.map(LocalPlaylist::id)))
    }

    @Test
    fun collectionReorderRejectsIncompleteDuplicateUnknownAndStaleIds() {
        val first = playlist(emptyList())
        val second = first.copy(id = "playlist-2")
        val source = listOf(first, second)
        assertEquals(null, reorderPlaylists(source, listOf(first.id)))
        assertEquals(null, reorderPlaylists(source, listOf(first.id, first.id)))
        assertEquals(null, reorderPlaylists(source, listOf(first.id, "unknown")))
        assertEquals(null, reorderPlaylists(listOf(first, first), listOf(first.id, first.id)))
        val created = first.copy(id = "new")
        assertEquals(null, reorderPlaylists(listOf(created) + source, listOf(second.id, first.id)))
        assertEquals(null, reorderPlaylists(listOf(first), listOf(second.id, first.id)))
    }

    @Test
    fun collectionReorderUsesLatestPlaylistContent() {
        val first = playlist(emptyList())
        val second = first.copy(id = "playlist-2")
        val renamed = first.copy(name = "Updated", entries = listOf(entry("song", track(1, "Song", "uri:1"))))
        val ordered = requireNotNull(reorderPlaylists(listOf(renamed, second), listOf(second.id, first.id)))
        assertSame(renamed, ordered[1])
    }

    private fun sortedIds(
        tracks: List<ResolvedPlaylistTrack>,
        field: PlaylistSortField,
    ): List<String> = sortPlaylistTracks(
        tracks,
        PlaylistSortConfig(field = field),
    ).map { it.entry.id }

    private fun playlist(entries: List<PlaylistTrackEntry>): LocalPlaylist = LocalPlaylist(
        id = "playlist-1",
        name = "Playlist",
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
        entries = entries,
    )

    private fun entry(id: String, track: MusicTrack): PlaylistTrackEntry = PlaylistTrackEntry(
        id = id,
        addedAtEpochMillis = 100L,
        trackSnapshot = track,
    )

    private fun resolved(
        id: String,
        track: MusicTrack,
        available: Boolean = true,
    ): ResolvedPlaylistTrack = ResolvedPlaylistTrack(
        entry = entry(id, track),
        track = track,
        available = available,
    )

    private fun track(
        id: Long,
        title: String,
        contentUri: String,
    ): MusicTrack = MusicTrack(
        id = id,
        title = title,
        artist = "Artist $id",
        album = "Album $id",
        durationMs = id * 1_000L,
        dateAddedEpochSeconds = id,
        dateModifiedEpochSeconds = id,
        fileName = "$id.mp3",
        fileSizeBytes = id * 100L,
        contentUri = contentUri,
        titleSectionKey = title.first().toString(),
        titleSortKey = "1_${title.uppercase()}",
    )
}
