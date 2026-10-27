package com.melox.player.optimization

import com.melox.player.data.playback.PlaybackPositionCheckpoint
import com.melox.player.data.playback.PlaybackPositionCheckpointCodec
import com.melox.player.data.playback.PlaybackSnapshotCodec
import com.melox.player.data.playback.PlaybackSnapshotRecord
import com.melox.player.data.playlist.PlaylistSaveQueue
import com.melox.player.data.repository.LyricsRequest
import com.melox.player.data.repository.LyricsReadException
import com.melox.player.data.repository.hasReusableDocumentProperties
import com.melox.player.data.repository.readFirstSidecar
import com.melox.player.data.repository.readScanSource
import com.melox.player.data.repository.stableDocumentTrackId
import com.melox.player.model.LocalPlaylist
import com.melox.player.model.LyricLine
import com.melox.player.model.LyricsDocument
import com.melox.player.model.LyricsFormat
import com.melox.player.model.LyricsSource
import com.melox.player.model.LyricsUiState
import com.melox.player.model.MusicTrack
import com.melox.player.model.PlaybackMode
import com.melox.player.model.PlaybackQueueItem
import com.melox.player.model.PlaybackSnapshot
import com.melox.player.ui.component.library.ArtworkDecodeLimiter
import com.melox.player.ui.viewmodel.resolveLyricsStates
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.CRC32
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class SharedFeatureOptimizationTest {
    @Test
    fun emptyScanIsSuccessfulButNullOrFailedSourcesAbortTheScan() {
        assertEquals(emptyList<String>(), readScanSource("empty") { emptyList<String>() })
        assertThrows(IOException::class.java) { readScanSource<String>("failed") { null } }
        val denied = SecurityException("denied")
        assertSame(denied, assertThrows(SecurityException::class.java) {
            readScanSource<String>("denied") { throw denied }
        })
        val readVolumes = mutableListOf<String>()
        assertThrows(IOException::class.java) {
            listOf("primary", "failed", "secondary").flatMap { volume ->
                readVolumes += volume
                readScanSource(volume) {
                    if (volume == "failed") null else listOf(volume)
                }
            }
        }
        assertEquals(listOf("primary", "failed"), readVolumes)
    }

    @Test
    fun safMetadataReuseRequiresPositiveMatchingFreshnessAndAnAlreadyReadFile() {
        val uri = "content://documents/track"
        val track = MusicTrack(
            id = stableDocumentTrackId(uri), title = "Track", artist = null, album = null,
            durationMs = 1_000L, dateAddedEpochSeconds = 1L, dateModifiedEpochSeconds = 10L,
            fileName = "track.flac", fileSizeBytes = 100L, contentUri = uri,
            titleSectionKey = "T", titleSortKey = "track", audioPropertiesScanned = true,
        )
        assertTrue(track.hasReusableDocumentProperties(uri, 10L, 100L, "track.flac"))
        assertFalse(track.hasReusableDocumentProperties(uri, 11L, 100L, "track.flac"))
        assertFalse(track.hasReusableDocumentProperties(uri, 10L, 101L, "track.flac"))
        assertFalse(track.hasReusableDocumentProperties("content://documents/other", 10L, 100L, "track.flac"))
        assertFalse(track.copy(audioPropertiesScanned = false).hasReusableDocumentProperties(uri, 10L, 100L, "track.flac"))
        assertFalse(track.copy(dateModifiedEpochSeconds = 0L).hasReusableDocumentProperties(uri, 0L, 100L, "track.flac"))
        assertFalse(track.copy(fileSizeBytes = 0L).hasReusableDocumentProperties(uri, 10L, 0L, "track.flac"))
    }

    @Test
    fun playlistFailuresRetryTheLatestEditAndReportOnlyOnce() = runBlocking {
        val done = CompletableDeferred<Unit>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val writes = mutableListOf<String>()
        var failures = 0
        var retries = 0
        lateinit var queue: PlaylistSaveQueue
        queue = PlaylistSaveQueue(scope, save = { playlists ->
            writes += playlists.single().name
            if (writes.size <= 2) throw IOException("disk full")
            done.complete(Unit)
        }, onFailure = { failures++ }, awaitRetry = {
            retries++
            if (retries == 1) queue.enqueue(listOf(playlist("latest")))
            yield()
        })
        try {
            queue.enqueue(listOf(playlist("first")))
            withTimeout(2_000L) { done.await() }
            assertEquals(listOf("first", "latest", "latest"), writes)
            assertEquals(1, failures)
        } finally { scope.cancel() }
    }

    @Test
    fun aCompletedOlderPlaylistWriteCannotDropAnEditMadeDuringThatWrite() = runBlocking {
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val writes = mutableListOf<String>()
        val queue = PlaylistSaveQueue(scope, save = { playlists ->
            writes += playlists.single().name
            if (writes.size == 1) {
                writing.complete(Unit)
                release.await()
            } else done.complete(Unit)
        }, onFailure = { fail("Unexpected save failure") })
        try {
            queue.enqueue(listOf(playlist("first")))
            withTimeout(2_000L) { writing.await() }
            queue.enqueue(listOf(playlist("second")))
            queue.enqueue(listOf(playlist("third")))
            release.complete(Unit)
            withTimeout(2_000L) { done.await() }
            assertEquals(listOf("first", "third"), writes)
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test
    fun lyricRefreshDoesNotPublishLoadingOrReplaceEqualContent() = runBlocking {
        val request = request()
        val document = document("Original")
        val states = flowOf(request, request.copy(refreshRevision = 1L))
            .resolveLyricsStates { document.copy(lines = document.lines.toList()) }.toList()
        assertEquals(listOf(LyricsUiState.Loading, LyricsUiState.Available(document, request.mediaId)), states)
    }

    @Test
    fun identicalLyricsOnAnotherTrackKeepTheirNewMediaIdentity() = runBlocking {
        val request = request()
        val document = document("Shared lyrics")
        val nextRequest = request.copy(mediaId = "next", contentUri = "content://next")
        val states = flowOf(request, nextRequest).resolveLyricsStates { document }.toList()
        assertEquals(
            listOf(
                LyricsUiState.Loading,
                LyricsUiState.Available(document, request.mediaId),
                LyricsUiState.Loading,
                LyricsUiState.Available(document, nextRequest.mediaId),
            ), states,
        )
    }

    @Test
    fun lyricRefreshPublishesChangedContentAndConfirmedDeletion() = runBlocking {
        val request = request()
        val original = document("Original")
        val changed = document("Changed")
        val states = flowOf(request, request.copy(refreshRevision = 1L), request.copy(refreshRevision = 2L))
            .resolveLyricsStates {
                when (it.refreshRevision) { 0L -> original; 1L -> changed; else -> null }
            }.toList()
        assertEquals(listOf(LyricsUiState.Loading, LyricsUiState.Available(original, request.mediaId),
            LyricsUiState.Available(changed, request.mediaId), LyricsUiState.Unavailable), states)
    }

    @Test
    fun failedSameTrackLyricRefreshRetainsItsLastDocumentAndAllowsAnotherRefresh() = runBlocking {
        val request = request()
        val original = document("Original")
        val changed = document("Changed")
        val states = flowOf(request, request.copy(refreshRevision = 1L), request.copy(refreshRevision = 2L))
            .resolveLyricsStates {
                when (it.refreshRevision) { 0L -> original; 1L -> throw IOException("temporary failure"); else -> changed }
            }.toList()
        assertEquals(listOf(LyricsUiState.Loading, LyricsUiState.Available(original, request.mediaId),
            LyricsUiState.Available(changed, request.mediaId)), states)
    }

    @Test
    fun aFailedNewTrackLoadCannotKeepThePreviousTracksLyrics() = runBlocking {
        val request = request()
        val original = document("Original")
        val states = flowOf(request, request.copy(mediaId = "other", contentUri = "content://other"))
            .resolveLyricsStates {
                if (it.mediaId == request.mediaId) original else throw IOException("unreadable")
            }.toList()
        assertEquals(listOf(LyricsUiState.Loading, LyricsUiState.Available(original, request.mediaId),
            LyricsUiState.Loading, LyricsUiState.Unavailable), states)
    }

    @Test
    fun aConfirmedLyricRemovalIsPublishedEvenIfTheOtherSourceFails() = runBlocking {
        val request = request()
        val original = document("Original")
        val states = flowOf(request, request.copy(refreshRevision = 1L))
            .resolveLyricsStates {
                if (it.refreshRevision == 0L) original else throw LyricsReadException(
                    setOf(LyricsSource.SIDECAR), IOException("sidecar query failed"),
                )
            }.toList()
        assertEquals(listOf(LyricsUiState.Loading, LyricsUiState.Available(original, request.mediaId),
            LyricsUiState.Unavailable), states)
    }

    @Test
    fun failureReadingTheDisplayedLyricSourceRetainsItsDocument() = runBlocking {
        val request = request()
        val original = document("Original")
        val states = flowOf(request, request.copy(refreshRevision = 1L))
            .resolveLyricsStates {
                if (it.refreshRevision == 0L) original else throw LyricsReadException(
                    setOf(LyricsSource.EMBEDDED), IOException("embedded read failed"),
                )
            }.toList()
        assertEquals(listOf(LyricsUiState.Loading, LyricsUiState.Available(original, request.mediaId)), states)
    }

    @Test
    fun releasingThePlaylistOwnerStillFlushesAnEditQueuedDuringTheCurrentWrite() = runBlocking {
        val owner = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + owner)
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val queue = PlaylistSaveQueue(scope, save = {
            writes += it.single().name
            if (writes.size == 1) { writing.complete(Unit); release.await() }
            else done.complete(Unit)
        }, onFailure = { fail("Unexpected failure") })
        try {
            queue.enqueue(listOf(playlist("first")))
            withTimeout(2_000L) { writing.await() }
            queue.enqueue(listOf(playlist("latest")))
            owner.cancel()
            release.complete(Unit)
            withTimeout(2_000L) { done.await() }
            assertEquals(listOf("first", "latest"), writes)
        } finally { release.complete(Unit); owner.cancel() }
    }

    @Test
    fun preferredSidecarStopsBeforeReadingTheFallback() {
        val reads = mutableListOf<String>()
        val document = readFirstSidecar(listOf("song.lrc" to LyricsFormat.LRC,
            "song.ttml" to LyricsFormat.TTML), 10_000L) {
            reads += it
            "[00:01.00]Lyrics"
        }
        assertEquals(listOf("song.lrc", "song_trans.lrc", "song.trans.lrc", "song.zh.lrc"), reads)
        assertEquals("Lyrics", document?.lines?.single()?.text)
    }

    @Test
    fun missingOrInvalidPreferredSidecarStillReadsTheFallbackInOrder() {
        for (first in listOf(null, "")) {
            val reads = mutableListOf<String>()
            val document = readFirstSidecar(listOf("song.ttml" to LyricsFormat.TTML,
                "song.lrc" to LyricsFormat.LRC), 10_000L) {
                reads += it
                if (it.endsWith("ttml")) first else "[00:01.00]Fallback"
            }
            assertEquals(listOf("song.ttml", "song.lrc", "song_trans.lrc", "song.trans.lrc", "song.zh.lrc"), reads)
            assertEquals("Fallback", document?.lines?.single()?.text)
        }
    }

    @Test
    fun progressCheckpointIsSmallAndCannotApplyToAnotherQueueGeneration() {
        val snapshot = snapshot()
        val record = PlaybackSnapshotRecord(snapshot, 101L)
        val checkpoint = PlaybackPositionCheckpoint(101L, 1, 9_876L)
        val bytes = ByteArrayOutputStream().also { PlaybackPositionCheckpointCodec.write(it, checkpoint) }.toByteArray()
        assertEquals(36, bytes.size)
        assertEquals(checkpoint, PlaybackPositionCheckpointCodec.read(ByteArrayInputStream(bytes)))
        assertEquals(9_876L, checkpoint.applyTo(record)?.positionMs)
        assertEquals(1, checkpoint.applyTo(record)?.currentIndex)
        assertSame(snapshot.queue, checkpoint.applyTo(record)?.queue)
        assertNull(checkpoint.applyTo(record.copy(generation = 102L)))
        assertNull(checkpoint.copy(currentIndex = 2).applyTo(record))
        assertNull(checkpoint.copy(positionMs = -1).applyTo(record))
        assertNull(checkpoint.copy(generation = 0L).applyTo(record.copy(generation = 0L)))
    }

    @Test
    fun truncatedOrCorruptedProgressDoesNotDecode() {
        val bytes = ByteArrayOutputStream().also {
            PlaybackPositionCheckpointCodec.write(it, PlaybackPositionCheckpoint(101L, 0, 123L))
        }.toByteArray()
        assertThrows(IOException::class.java) {
            PlaybackPositionCheckpointCodec.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 1)))
        }
        bytes[12] = (bytes[12].toInt() xor 1).toByte()
        assertThrows(IOException::class.java) { PlaybackPositionCheckpointCodec.read(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun fullSnapshotRetainsGenerationAndStillReadsVersionTwo() {
        val snapshot = snapshot()
        val bytes = ByteArrayOutputStream().also { PlaybackSnapshotCodec.write(it, snapshot, 123L) }.toByteArray()
        assertEquals(PlaybackSnapshotRecord(snapshot, 123L),
            PlaybackSnapshotCodec.readRecord(ByteArrayInputStream(bytes)))
        val versionTwo = ByteArrayOutputStream().also { stream ->
            val output = DataOutputStream(stream)
            output.writeInt(0x4D454C50)
            output.writeInt(2)
            output.write(bytes, 16, bytes.size - 16 - 8)
            output.writeLong(CRC32().apply { update(stream.toByteArray()) }.value)
        }.toByteArray()
        assertEquals(PlaybackSnapshotRecord(snapshot, 0L),
            PlaybackSnapshotCodec.readRecord(ByteArrayInputStream(versionTwo)))
    }

    @Test
    fun listArtworkDecodesAreBoundedAndLeaveCapacityForThePlayer() = runBlocking {
        val limiter = ArtworkDecodeLimiter()
        val release = CompletableDeferred<Unit>()
        val listsEntered = CompletableDeferred<Unit>()
        val playerEntered = CompletableDeferred<Unit>()
        var activeLists = 0
        val jobs = List(8) {
            launch {
                limiter.decode(foreground = false) {
                    activeLists++
                    if (activeLists == 2) listsEntered.complete(Unit)
                    release.await()
                    activeLists--
                }
            }
        }
        try {
            withTimeout(2_000L) { listsEntered.await() }
            repeat(10) { yield() }
            assertEquals(2, activeLists)
            val foreground = launch {
                limiter.decode(foreground = true) { playerEntered.complete(Unit); release.await() }
            }
            withTimeout(2_000L) { playerEntered.await() }
            release.complete(Unit)
            jobs.joinAll()
            foreground.join()
            assertEquals(0, activeLists)
        } finally { release.complete(Unit); jobs.forEach { it.cancel() } }
    }

    @Test
    fun cancelledArtworkConsumersReleaseTheirDecodeSlots() = runBlocking {
        val limiter = ArtworkDecodeLimiter()
        repeat(4) {
            val entered = CompletableDeferred<Unit>()
            val blocked = launch {
                limiter.decode(foreground = false) {
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
            }
            withTimeout(2_000L) { entered.await() }
            blocked.cancel()
            blocked.join()
        }
        withTimeout(2_000L) {
            assertEquals("available", limiter.decode(false) { "available" })
        }
    }

    private fun playlist(name: String) = LocalPlaylist("1", name, 1L, 1L)
    private fun request() = LyricsRequest("track", "content://track", "track.flac", "/Music", 10_000L)
    private fun document(text: String) = LyricsDocument(
        lines = listOf(LyricLine("main", 1_000L, 2_000L, text, emptyList(), null)),
        format = LyricsFormat.LRC, source = LyricsSource.EMBEDDED,
    )
    private fun snapshot() = PlaybackSnapshot(
        queue = listOf("one", "two").map {
            PlaybackQueueItem(it, null, "content://$it", it, null, null, 10_000L, 1L, 1L)
        }, currentIndex = 0, positionMs = 123L, playbackMode = PlaybackMode.ORDER,
    )
}
