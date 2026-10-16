package com.melox.player

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import com.melox.player.model.PlaybackMode
import com.melox.player.model.PlaybackQueueItem
import com.melox.player.model.PlaybackSnapshot
import com.melox.player.model.PlaybackUiState
import com.melox.player.playback.playbackStateDuringSnapshotRestore
import com.melox.player.playback.toInitialPlaybackState
import com.melox.player.ui.component.library.rememberArtworkLoad
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackStartupHandoffTest {
    @Test
    fun savedSongSurvivesEmptyConnectionSamplesUntilTheLiveQueueArrives() {
        val saved = savedPlayback()
        var displayed = saved
        repeat(3) {
            displayed = playbackStateDuringSnapshotRestore(displayed, PlaybackUiState(), true)
            assertSame(saved, displayed)
        }
        val live = saved.copy(
            queue = listOf(item("previous"), item("saved"), item("next")),
            currentIndex = 1,
            isPlaying = true,
            playWhenReady = true,
            positionMs = 4_500,
        )
        displayed = playbackStateDuringSnapshotRestore(displayed, live, true)
        assertSame(live, displayed)
        assertSame(live, playbackStateDuringSnapshotRestore(displayed, live, false))
    }

    @Test
    fun completedEmptyRestoreClearsMissingInvalidOrExcludedSavedPlayback() {
        val empty = PlaybackUiState(playbackMode = PlaybackMode.RANDOM)
        val displayed = playbackStateDuringSnapshotRestore(savedPlayback(), empty, true)
        assertEquals("saved", displayed.currentItem?.mediaId)
        assertSame(empty, playbackStateDuringSnapshotRestore(displayed, empty, false))
    }

    @Test
    fun explicitClearDuringRestoreCannotResurrectTheSavedSong() {
        val cleared = PlaybackUiState(playbackMode = PlaybackMode.REPEAT_ONE)
        val pending = playbackStateDuringSnapshotRestore(cleared, PlaybackUiState(), true)
        assertNull(pending.currentItem)
        assertNull(playbackStateDuringSnapshotRestore(pending, PlaybackUiState(), false).currentItem)
    }

    @Test
    fun liveReplacementWinsDuringRestoreAndOrdinaryClearsStillWork() {
        val replacement = PlaybackUiState(
            queue = listOf(item("new")),
            currentIndex = 0,
            playWhenReady = true,
            playbackSpeed = 1.5f,
        )
        val displayed = playbackStateDuringSnapshotRestore(savedPlayback(), replacement, true)
        assertSame(replacement, displayed)
        assertEquals("content://music/new", displayed.currentItem?.contentUri)
        val cleared = PlaybackUiState()
        assertSame(cleared, playbackStateDuringSnapshotRestore(displayed, cleared, false))
    }

    @Test
    fun firstLaunchWithoutSavedPlaybackUsesTheLiveEmptyState() {
        val live = PlaybackUiState(errorMessage = "Unavailable")
        assertSame(live, playbackStateDuringSnapshotRestore(PlaybackUiState(), live, true))
    }

    @Test
    fun anEmptyRestoreSampleDoesNotCancelOrRestartTheSavedArtworkRequest() = runBlocking {
        val displayed = mutableStateOf(savedPlayback())
        val decoded = CompletableDeferred<String>()
        val requests = mutableListOf<String>()
        var artwork: String? = null
        withArtworkComposition(content = {
            val current = displayed.value.currentItem
            val result = rememberArtworkLoad(current?.contentUri ?: "empty") {
                current?.let {
                    requests += it.contentUri
                    decoded.await()
                }
            }
            SideEffect { artwork = result?.value }
        }) {
            frame(0)
            assertEquals(listOf("content://music/saved"), requests)
            displayed.value = playbackStateDuringSnapshotRestore(
                displayed.value, PlaybackUiState(), true,
            )
            frame(16)
            val live = savedPlayback().copy(positionMs = 4_500)
            displayed.value = playbackStateDuringSnapshotRestore(displayed.value, live, true)
            frame(32)
            decoded.complete("saved-cover")
            frame(48)
            displayed.value = playbackStateDuringSnapshotRestore(displayed.value, live, false)
            frame(64)
            assertEquals(listOf("content://music/saved"), requests)
            assertEquals("saved-cover", artwork)
            displayed.value = playbackStateDuringSnapshotRestore(displayed.value, PlaybackUiState(), false)
            frame(80)
            assertNull(artwork)
        }
    }

    private fun savedPlayback() = PlaybackSnapshot(
        queue = listOf(item("saved")),
        currentIndex = 0,
        positionMs = 4_000,
        playbackMode = PlaybackMode.ORDER,
    ).toInitialPlaybackState()

    private fun item(id: String) = PlaybackQueueItem(
        mediaId = id,
        trackId = null,
        contentUri = "content://music/$id",
        title = "Track $id",
        artist = "Artist $id",
        album = null,
        durationMs = 60_000,
        dateModifiedEpochSeconds = 1,
        fileSizeBytes = 100,
    )
}
