package com.melox.player

import com.melox.player.model.MusicTrack
import com.melox.player.model.PlaybackMode
import com.melox.player.model.PlaybackQueueItem
import com.melox.player.model.PlaybackUiState
import com.melox.player.ui.component.library.handleSongListTrackClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SongListTrackClickTest {
    @Test
    fun currentPlayingSongOpensWithoutRestartingOrReplacingTheQueue() {
        assertCurrentSongOnlyOpens(isPlaying = true, playWhenReady = true)
    }

    @Test
    fun currentPausedSongOpensWithoutResumingOrSeeking() {
        assertCurrentSongOnlyOpens(isPlaying = false, playWhenReady = false)
    }

    @Test
    fun currentWaitingSongOpensWithoutChangingPendingPlayIntent() {
        assertCurrentSongOnlyOpens(isPlaying = false, playWhenReady = true)
    }

    @Test
    fun matchingUriOpensEvenWhenTheListHasNewMetadataAndADifferentId() {
        val harness = ClickHarness(playback())
        val updatedTrack = track.copy(id = 900L, title = "Updated title")

        harness.click(listOf(updatedTrack), 0)

        assertEquals(1, harness.openCalls)
        assertEquals(0, harness.playCalls)
        assertEquals(42_000L, harness.state.positionMs)
    }

    @Test
    fun matchingNumericIdWithAnotherUriStartsThatSong() {
        val harness = ClickHarness(playback())
        val anotherSource = track.copy(contentUri = "content://other/audio/1")
        val selection = listOf(anotherSource)

        harness.click(selection, 0)

        assertEquals(0, harness.openCalls)
        assertEquals(1, harness.playCalls)
        assertSame(selection, harness.playedTracks)
        assertEquals(0, harness.playedIndex)
    }

    @Test
    fun anotherSongKeepsTheOriginalQueueAndSelectedIndexForPlayback() {
        val harness = ClickHarness(playback())
        val anotherTrack = track.copy(id = 2L, contentUri = "content://media/audio/2")
        val selection = listOf(track, anotherTrack)

        harness.click(selection, 1)

        assertEquals(0, harness.openCalls)
        assertEquals(1, harness.playCalls)
        assertSame(selection, harness.playedTracks)
        assertEquals(1, harness.playedIndex)
    }

    @Test
    fun noCurrentSongStartsTheSelectedSong() {
        val harness = ClickHarness(PlaybackUiState())
        val selection = listOf(track)

        harness.click(selection, 0)

        assertEquals(0, harness.openCalls)
        assertEquals(1, harness.playCalls)
        assertSame(selection, harness.playedTracks)
        assertEquals(0, harness.playedIndex)
    }

    private fun assertCurrentSongOnlyOpens(isPlaying: Boolean, playWhenReady: Boolean) {
        for (mode in PlaybackMode.entries) {
            val original = playback().copy(
                isPlaying = isPlaying,
                playWhenReady = playWhenReady,
                playbackMode = mode,
            )
            val harness = ClickHarness(original)
            val anotherTrack = track.copy(id = 2L, contentUri = "content://media/audio/2")

            harness.click(listOf(anotherTrack, track), 1)

            assertEquals(1, harness.openCalls)
            assertEquals(0, harness.playCalls)
            assertSame(original, harness.state)
            assertSame(original.queue, harness.state.queue)
            assertEquals(42_000L, harness.state.positionMs)
            assertEquals(isPlaying, harness.state.isPlaying)
            assertEquals(playWhenReady, harness.state.playWhenReady)
        }
    }

    private class ClickHarness(var state: PlaybackUiState) {
        var openCalls = 0
        var playCalls = 0
        var playedTracks: List<MusicTrack>? = null
        var playedIndex = -1

        fun click(tracks: List<MusicTrack>, index: Int) {
            handleSongListTrackClick(
                selectedTrack = tracks[index],
                currentTrackContentUri = state.currentItem?.contentUri,
                onOpenPlayer = { openCalls++ },
                onPlayTrack = {
                    playCalls++
                    playedTracks = tracks
                    playedIndex = index
                    state = state.copy(isPlaying = true, playWhenReady = true, positionMs = 0L)
                },
            )
        }
    }

    private fun playback() = PlaybackUiState(
        queue = listOf(
            PlaybackQueueItem(
                mediaId = "current-slot",
                trackId = track.id,
                contentUri = track.contentUri,
                title = "Song",
                artist = "Artist",
                album = "Album",
                durationMs = track.durationMs,
                dateModifiedEpochSeconds = 0L,
                fileSizeBytes = 1L,
            ),
        ),
        currentIndex = 0,
        positionMs = 42_000L,
        durationMs = track.durationMs,
        positionDiscontinuityRevision = 4L,
        playbackIteration = 2L,
    )

    private val track = MusicTrack(
        id = 1L,
        title = "Song",
        artist = "Artist",
        album = "Album",
        durationMs = 180_000L,
        dateAddedEpochSeconds = 0L,
        dateModifiedEpochSeconds = 0L,
        fileName = "song.flac",
        fileSizeBytes = 1L,
        contentUri = "content://media/audio/1",
        titleSectionKey = "S",
        titleSortKey = "SONG",
    )
}
