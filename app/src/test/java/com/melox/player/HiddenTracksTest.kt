package com.melox.player

import com.melox.player.data.library.visibleMusicTracks
import com.melox.player.data.library.musicFolderPathFromSourceUri
import com.melox.player.data.playlist.resolvePlaylistTracks
import com.melox.player.model.LocalPlaylist
import com.melox.player.model.PlaylistTrackEntry
import com.melox.player.model.MusicTrack
import com.melox.player.model.PlaybackMode
import com.melox.player.model.PlaybackQueueItem
import com.melox.player.model.PlaybackSnapshot
import com.melox.player.playback.hiddenPlaybackContentUris
import com.melox.player.playback.withoutContentUris
import com.melox.player.playback.visibleQueueStart
import androidx.media3.common.C
import com.melox.player.ui.viewmodel.visibleLibraryProjections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class HiddenTracksTest {
    @Test
    fun individualHidingUsesContentUriAndCombinesWithFolders() {
        val tracks = listOf(track(1, "/Music"), track(2, "/Other"), track(3, "/Other"))
        val hiddenUris = setOf(tracks[1].contentUri)
        assertEquals(listOf(3L), visibleMusicTracks(tracks, listOf("/Music"), hiddenUris).map { it.id })
        assertEquals(listOf(1L, 3L), visibleMusicTracks(tracks, emptyList(), hiddenUris).map { it.id })
        assertEquals(tracks, visibleMusicTracks(tracks, emptyList(), emptySet()))
    }

    @Test
    fun hidingProjectsAllLibraryGroupsFromVisibleTracks() = runBlocking {
        val tracks = listOf(track(1, "/Music"), track(2, "/Other"))
        val projection = visibleLibraryProjections(
            MutableStateFlow(tracks),
            MutableStateFlow(emptyList()),
            MutableStateFlow(setOf(tracks[0].contentUri)),
        ).first()
        assertEquals(listOf(2L), projection.tracks.map { it.id })
        assertEquals(listOf(2L), projection.albums.flatMap { it.tracks }.map { it.id })
        assertEquals(listOf(2L), projection.artists.flatMap { it.tracks }.map { it.id })
        assertEquals(listOf(2L), projection.folders.flatMap { it.tracks }.map { it.id })
    }

    @Test
    fun queueRulesKeepIndividualUrisEvenOutsideTheCachedLibrary() {
        val tracks = listOf(track(1, "/Music"), track(2, "/Other"))
        assertEquals(
            setOf(tracks[0].contentUri, "content://unindexed/7"),
            hiddenPlaybackContentUris(tracks, listOf("/Music"), setOf("content://unindexed/7")),
        )
    }

    @Test
    fun restoreKeepsCurrentPositionWhenEarlierAndLaterSongsAreHidden() {
        val snapshot = snapshot(1)
        val filtered = snapshot.withoutContentUris(setOf(uri(1), uri(3)))
        assertEquals(listOf(uri(2)), filtered.queue.map { it.contentUri })
        assertEquals(0, filtered.currentIndex)
        assertEquals(12345L, filtered.positionMs)
        assertEquals(PlaybackMode.RANDOM, filtered.playbackMode)
    }

    @Test
    fun restoreMovesHiddenCurrentSongToNextSurvivorAtStart() {
        val filtered = snapshot(0).withoutContentUris(setOf(uri(1), uri(2)))
        assertEquals(listOf(uri(3)), filtered.queue.map { it.contentUri })
        assertEquals(0, filtered.currentIndex)
        assertEquals(0L, filtered.positionMs)
    }

    @Test
    fun restoreWrapsWhenTheCurrentTailIsHidden() {
        val filtered = snapshot(2).withoutContentUris(setOf(uri(3)))
        assertEquals(0, filtered.currentIndex)
        assertEquals(0L, filtered.positionMs)
    }

    @Test
    fun allHiddenRestoreProducesEmptyQueueAndNoOpRetainsSnapshot() {
        val snapshot = snapshot(1)
        assertSame(snapshot, snapshot.withoutContentUris(emptySet()))
        val filtered = snapshot.withoutContentUris(snapshot.queue.map { it.contentUri }.toSet())
        assertTrue(filtered.queue.isEmpty())
        assertEquals(-1, filtered.currentIndex)
        assertEquals(0L, filtered.positionMs)
    }

    @Test
    fun hidingOneSourceRemovesAllDuplicateQueueSlots() {
        val source = snapshot(1)
        val duplicated = source.copy(queue = source.queue + source.queue[1])
        val filtered = duplicated.withoutContentUris(setOf(uri(2)))
        assertEquals(listOf(uri(1), uri(3)), filtered.queue.map { it.contentUri })
        assertEquals(1, filtered.currentIndex)
        assertEquals(0L, filtered.positionMs)
    }

    @Test
    fun documentAndFileSourcesResolveParentsWithoutLibraryCache() {
        val primary = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FAlbum%2Fsong.flac"
        val removable = "content://com.android.externalstorage.documents/document/ABCD-1234%3AMusic%2Fsong.flac"
        assertEquals("/Music/Album", musicFolderPathFromSourceUri(primary))
        assertEquals("/Music", musicFolderPathFromSourceUri(removable))
        assertEquals("/", musicFolderPathFromSourceUri("content://com.android.externalstorage.documents/document/primary%3Asong.flac"))
        assertEquals("/storage/emulated/0/Music", musicFolderPathFromSourceUri("file:///storage/emulated/0/Music/song.flac"))
        assertEquals("/storage/emulated/0/Music", musicFolderPathFromSourceUri("file:///sdcard/Music/song.flac"))
        assertNull(musicFolderPathFromSourceUri("content://unrelated.provider/audio/1"))
        assertNull(musicFolderPathFromSourceUri("content://media/external/audio/media/1"))
    }

    @Test
    fun queueStartMapsSurvivingAndHiddenSelectionsWithoutSkippingPosition() {
        assertEquals(0 to 123L, visibleQueueStart(listOf(1, 3), 1, 123L))
        assertEquals(1 to C.TIME_UNSET, visibleQueueStart(listOf(1, 3), 2, 123L))
        assertEquals(0 to C.TIME_UNSET, visibleQueueStart(listOf(1, 3), 4, 123L))
        assertEquals(C.INDEX_UNSET to C.TIME_UNSET, visibleQueueStart(emptyList(), 1, 123L))
        assertEquals(C.INDEX_UNSET to C.TIME_UNSET, visibleQueueStart(listOf(1, 3), C.INDEX_UNSET, C.TIME_UNSET))
    }

    @Test
    fun hiddenPlaylistSlotsRemainAndPartialRestoreRespectsFolderRules() {
        val tracks = listOf(track(1, "/Music"), track(2, "/Other"), track(3, "/Other"))
        val playlist = LocalPlaylist(
            id = "playlist", name = "Playlist", createdAtEpochMillis = 0L, updatedAtEpochMillis = 0L,
            entries = tracks.map { PlaylistTrackEntry("entry-${it.id}", 0L, it) },
        )
        val readableUris = tracks.mapTo(HashSet(), MusicTrack::contentUri)
        val hiddenUris = setOf(uri(1), uri(2), uri(3))
        fun available(uris: Set<String>): List<Boolean> {
            val resolved = resolvePlaylistTracks(playlist, visibleMusicTracks(tracks, listOf("/Music"), uris), readableUris)
            assertEquals(playlist.entries, resolved.map { it.entry })
            assertEquals(tracks, resolved.map { it.track })
            return resolved.map { it.available }
        }
        assertEquals(listOf(false, false, false), available(hiddenUris))
        assertEquals(listOf(false, true, false), available(hiddenUris - uri(2)))
        assertEquals(listOf(false, true, true), available(emptySet()))
        assertEquals(3, playlist.entries.size)
    }

    @Test
    fun undisclosedExternalFolderIsNotTreatedAsHidden() {
        val external = track(1, "/Other").copy(contentUri = "content://unrelated.provider/audio/1", folderPath = null)
        assertEquals(listOf(external), visibleMusicTracks(listOf(external), listOf("/storage/emulated/0/Music")))
    }

    private fun snapshot(currentIndex: Int) = PlaybackSnapshot(
        queue = (1..3).map { id ->
            PlaybackQueueItem(
                mediaId = "$id", trackId = id.toLong(), contentUri = uri(id), title = "Song $id",
                artist = null, album = null, durationMs = 60_000, dateModifiedEpochSeconds = 0,
                fileSizeBytes = 100, sourceOrder = id.toDouble(), playbackMode = PlaybackMode.RANDOM,
            )
        },
        currentIndex = currentIndex,
        positionMs = 12345,
        playbackMode = PlaybackMode.RANDOM,
    )

    private fun uri(id: Int) = "content://music/$id"

    private fun track(id: Int, path: String) = MusicTrack(
        id = id.toLong(), title = "Song $id", artist = "Artist $id", album = "Album $id",
        durationMs = 60_000, dateAddedEpochSeconds = 0, dateModifiedEpochSeconds = 0,
        fileName = "$id.flac", fileSizeBytes = 100, contentUri = uri(id), titleSectionKey = "S",
        titleSortKey = "song $id", folderPath = path,
    )
}
