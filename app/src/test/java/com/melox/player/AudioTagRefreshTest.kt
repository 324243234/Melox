package com.melox.player

import com.melox.player.data.library.LocalAudioTags
import com.melox.player.data.library.createMusicSortKeys
import com.melox.player.data.library.withRefreshedTags
import com.melox.player.model.MusicTrack
import com.melox.player.model.PlaybackQueueItem
import com.melox.player.model.PlaybackUiState
import com.melox.player.model.withTrackMetadata
import org.junit.Assert.*
import org.junit.Test

class AudioTagRefreshTest {
    private val track = MusicTrack(
        id = 31L, title = "Before", artist = "Old artist", album = "Old album",
        durationMs = 123_456L, dateAddedEpochSeconds = 1L, dateModifiedEpochSeconds = 2L,
        fileName = "song.flac", fileSizeBytes = 8_000_000L,
        contentUri = "content://media/external/audio/media/31",
        titleSectionKey = "B", titleSortKey = "before", folderPath = "Music/",
        albumId = 41L, mediaStoreId = 31L, mimeType = "audio/flac",
        bitrateBitsPerSecond = 900_000, sampleRateHz = 96_000, channelCount = 2,
        bitDepth = 24, audioPropertiesScanned = true,
    )

    @Test
    fun descriptiveTagUpdatesPreserveAllStreamAndSourceFields() {
        val tags = LocalAudioTags("After", "New artist", "New album", "Album artist", 2026, 3, 2)
        val refreshed = track.withRefreshedTags(tags)
        assertEquals("After", refreshed.title)
        assertEquals("New artist", refreshed.artist)
        assertEquals("New album", refreshed.album)
        assertEquals("Album artist", refreshed.albumArtist)
        assertEquals(2026, refreshed.year)
        assertEquals(3, refreshed.trackNumber)
        assertEquals(2, refreshed.discNumber)
        assertEquals(createMusicSortKeys("After").section, refreshed.titleSectionKey)
        assertEquals(createMusicSortKeys("After").value, refreshed.titleSortKey)
        assertEquals(track, refreshed.copy(
            title = track.title, artist = track.artist, album = track.album,
            albumArtist = track.albumArtist, year = track.year,
            trackNumber = track.trackNumber, discNumber = track.discNumber,
            titleSectionKey = track.titleSectionKey, titleSortKey = track.titleSortKey,
        ))
    }

    @Test
    fun deletedTagsAreClearedWithoutChangingPlaybackStateOrAudio() {
        val refreshed = track.withRefreshedTags(LocalAudioTags())
        assertNull(refreshed.title)
        assertNull(refreshed.artist)
        assertNull(refreshed.album)
        val item = PlaybackQueueItem(
            "track:31", track.id, track.contentUri, "Before", track.artist, track.album,
            track.durationMs, track.dateModifiedEpochSeconds, track.fileSizeBytes,
        )
        val playback = PlaybackUiState(
            queue = listOf(item), currentIndex = 0, isPlaying = true, playWhenReady = true,
            positionMs = 42_000L, bufferedPositionMs = 72_000L,
            durationMs = track.durationMs, playbackIteration = 7L,
        )
        val projection = playback.withTrackMetadata(listOf(refreshed))
        assertEquals(playback, projection.copy(queue = playback.queue))
        assertEquals(item.contentUri, projection.currentItem?.contentUri)
        assertEquals(item.mediaId, projection.currentItem?.mediaId)
        assertEquals(item.durationMs, projection.currentItem?.durationMs)
    }
}
