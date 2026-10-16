package com.melox.player.optimization

import com.melox.player.data.library.MusicLibraryCacheAccess
import com.melox.player.data.library.MusicLibrarySaveQueue
import com.melox.player.data.library.MusicLibraryUpdates
import com.melox.player.data.repository.hasReusableDocumentProperties
import com.melox.player.data.repository.stableDocumentTrackId
import com.melox.player.model.MusicTrack
import com.melox.player.model.PlaybackMode
import com.melox.player.model.PlaybackQueueItem
import com.melox.player.model.PlaybackSnapshot
import com.melox.player.playback.withRestoredPlaybackMode
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class LibraryConsistencyOptimizationTest {
    @Test
    fun failedScanRollbackPreservesDeletionAndNewTags() {
        val updates = MusicLibraryUpdates()
        val previous = listOf(track(1, "Alpha"), track(2, "Beta"), track(3, "Gamma"))
        val started = updates.revision
        updates.delete(previous[0].contentUri)
        val edited = previous[2].copy(title = "A new title", titleSortKey = "a new title")
        assertTrue(updates.acceptTagRefresh(edited, updates.beginTagRefresh(edited.contentUri)))
        val rollback = updates.reconcile(previous, started)
        assertEquals(listOf(edited, previous[1]), rollback)
    }

    @Test
    fun anInitialPartialListDoesNotDiscardAnEditNeededByTheFinalScan() {
        val updates = MusicLibraryUpdates()
        val first = track(1)
        val saf = track(2)
        val started = updates.revision
        val edited = saf.copy(artist = "New artist")
        assertTrue(updates.acceptTagRefresh(edited, updates.beginTagRefresh(saf.contentUri)))
        assertEquals(listOf(first), updates.reconcile(listOf(first), started))
        assertEquals(listOf(first, edited), updates.reconcile(listOf(first, saf), started))
    }

    @Test
    fun anOlderTagRequestCannotReplaceTheLatestRequest() {
        val updates = MusicLibraryUpdates()
        val original = track(1)
        val first = updates.beginTagRefresh(original.contentUri)
        val second = updates.beginTagRefresh(original.contentUri)
        assertFalse(updates.acceptTagRefresh(original.copy(title = "Old"), first))
        val latest = original.copy(title = "Latest")
        assertTrue(updates.acceptTagRefresh(latest, second))
        assertFalse(updates.acceptTagRefresh(original, first))
        assertEquals(latest, updates.reconcile(listOf(original), 0L).single())
    }

    @Test
    fun deletionRejectsPendingTagsAndEveryScanResult() {
        val updates = MusicLibraryUpdates()
        val original = track(1)
        val request = updates.beginTagRefresh(original.contentUri)
        updates.delete(original.contentUri)
        assertFalse(updates.acceptTagRefresh(original.copy(title = "Edited"), request))
        assertTrue(updates.reconcile(listOf(original), updates.revision).isEmpty())
        updates.clear()
        assertTrue(updates.reconcile(listOf(original), updates.revision).isEmpty())
    }

    @Test
    fun clearingTheLibraryInvalidatesPendingTagReads() {
        val updates = MusicLibraryUpdates()
        val original = track(1)
        val request = updates.beginTagRefresh(original.contentUri)
        updates.clear()
        assertFalse(updates.acceptTagRefresh(original, request))
        assertEquals(listOf(original), updates.reconcile(listOf(original), 0L))
    }

    @Test
    fun concurrentTagsDoNotReplaceFreshlyScannedAudioIdentityOrFileName() {
        val updates = MusicLibraryUpdates()
        val old = track(1)
        val edited = old.copy(artist = "Edited", dateModifiedEpochSeconds = 12L, fileSizeBytes = 120L)
        updates.acceptTagRefresh(edited, updates.beginTagRefresh(old.contentUri))
        val scanned = old.copy(durationMs = 20_000L, sampleRateHz = 96_000,
            bitDepth = 24, fileName = "renamed.flac", folderPath = "New folder")
        val merged = updates.reconcile(listOf(scanned), 0L).single()
        assertEquals("Edited", merged.artist)
        assertEquals(12L, merged.dateModifiedEpochSeconds)
        assertEquals(120L, merged.fileSizeBytes)
        assertEquals(20_000L, merged.durationMs)
        assertEquals(96_000, merged.sampleRateHz)
        assertEquals(24, merged.bitDepth)
        assertEquals("renamed.flac", merged.fileName)
        assertEquals("New folder", merged.folderPath)
        assertEquals(scanned.contentUri, merged.contentUri)
    }

    @Test
    fun aNewerScanCanReadNewTagsWithoutAnOlderOverlay() {
        val updates = MusicLibraryUpdates()
        val old = track(1)
        updates.acceptTagRefresh(old.copy(title = "First edit"), updates.beginTagRefresh(old.contentUri))
        val started = updates.revision
        val scanned = old.copy(title = "Later file contents")
        assertEquals(listOf(scanned), updates.reconcile(listOf(scanned), started))
    }

    @Test
    fun unrelatedIdentitiesAndMissingTracksAreNotInventedByTagOverlays() {
        val updates = MusicLibraryUpdates()
        val old = track(1)
        updates.acceptTagRefresh(old.copy(title = "Edited"), updates.beginTagRefresh(old.contentUri))
        assertTrue(updates.reconcile(emptyList(), 0L).isEmpty())
        val different = old.copy(id = 99L)
        assertEquals(listOf(different), updates.reconcile(listOf(different), 0L))
        assertEquals(listOf(track(2)), updates.reconcile(listOf(track(2)), 0L))
    }

    @Test
    fun renamingASafFileInvalidatesReuseEvenWithAnUnchangedFingerprint() {
        val uri = "content://documents/one"
        val original = track(1).copy(id = stableDocumentTrackId(uri), contentUri = uri,
            fileName = "old.flac", title = "An embedded title")
        assertTrue(original.hasReusableDocumentProperties(uri, 10L, 100L, "old.flac"))
        assertFalse(original.hasReusableDocumentProperties(uri, 10L, 100L, "new.flac"))
        assertFalse(original.hasReusableDocumentProperties(uri, 10L, 100L, "OLD.flac"))
        assertFalse(original.copy(title = "old").hasReusableDocumentProperties(uri, 10L, 100L, "new.flac"))
    }

    @Test
    fun cacheAccessIsSharedByFileAcrossClients() {
        val path = "test-${UUID.randomUUID()}"
        assertSame(MusicLibraryCacheAccess.forPath(path), MusicLibraryCacheAccess.forPath(path))
        assertNotSame(MusicLibraryCacheAccess.forPath(path), MusicLibraryCacheAccess.forPath("$path-other"))
    }

    @Test
    fun staleCacheUpgradeCannotOverwriteANewerSaveOrClear() = runBlocking {
        for (replacement in listOf("new snapshot", "cleared")) {
            val access = MusicLibraryCacheAccess()
            var contents = "old snapshot"
            val (readRevision, _) = access.read { contents }
            assertTrue(access.write(access.nextWrite()) { contents = replacement; true })
            var upgradeRan = false
            assertTrue(access.writeBack(readRevision) { upgradeRan = true; contents = "old upgraded"; true })
            assertFalse(upgradeRan)
            assertEquals(replacement, contents)
        }
    }

    @Test
    fun aReadAfterAWriteWasRequestedCannotOverwriteItsLaterCommit() = runBlocking {
        val access = MusicLibraryCacheAccess()
        var contents = "old snapshot"
        val pendingWrite = access.nextWrite()
        val (readRevision, _) = access.read { contents }
        access.write(pendingWrite) { contents = "new snapshot"; true }
        access.writeBack(readRevision) { contents = "old upgraded"; true }
        assertEquals("new snapshot", contents)
    }

    @Test
    fun twoBackgroundUpgradesCannotOverwriteEachOtherFromTheSameReadVersion() = runBlocking {
        val access = MusicLibraryCacheAccess()
        val first = access.read { "old" }.first
        val second = access.read { "old" }.first
        var contents = "old"
        access.writeBack(first) { contents = "first upgraded"; true }
        access.writeBack(second) { contents = "second upgraded"; true }
        assertEquals("first upgraded", contents)
    }

    @Test
    fun anOlderWriteQueuedAfterANewerRequestIsSkipped() = runBlocking {
        val access = MusicLibraryCacheAccess()
        val old = access.nextWrite()
        val latest = access.nextWrite()
        val writes = mutableListOf<String>()
        assertTrue(access.write(latest) { writes += "latest"; true })
        assertTrue(access.write(old) { writes += "old"; true })
        assertEquals(listOf("latest"), writes)
    }

    @Test
    fun cacheReadersAndWritersCannotOverlap() = runBlocking {
        val access = MusicLibraryCacheAccess()
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val reader = async {
            access.read { events += "read:start"; reading.complete(Unit); release.await(); events += "read:end" }
        }
        withTimeout(2_000L) { reading.await() }
        val writer = async { access.write(access.nextWrite()) { events += "write"; true } }
        yield()
        assertEquals(listOf("read:start"), events)
        release.complete(Unit)
        withTimeout(2_000L) { reader.await(); writer.await() }
        assertEquals(listOf("read:start", "read:end", "write"), events)
        var staleWriteRan = false
        access.writeBack(reader.await().first) { staleWriteRan = true; true }
        assertFalse(staleWriteRan)
    }

    @Test
    fun aFailedCacheOperationReleasesItsLock() = runBlocking {
        val access = MusicLibraryCacheAccess()
        assertFalse(access.write(access.nextWrite()) { false })
        try {
            access.read<Unit> { throw IOException("broken snapshot") }
            fail("Expected read failure")
        } catch (_: IOException) { }
        assertEquals("readable", withTimeout(2_000L) { access.read { "readable" }.second })
    }

    @Test
    fun libraryWritesAreSerialAndCoalesceToTheLatestList() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val writes = mutableListOf<Pair<Long, Boolean>>()
        val queue = MusicLibrarySaveQueue(scope, save = { tracks, invalidating ->
            writes += tracks.single().id to invalidating
            if (writes.size == 1) { writing.complete(Unit); release.await() } else done.complete(Unit)
            true
        }, clearCache = { fail("Unexpected clear") }, onFailure = { fail("Unexpected failure") })
        try {
            queue.enqueue(listOf(track(1)))
            withTimeout(2_000L) { writing.await() }
            queue.enqueue(listOf(track(2)), invalidate = true)
            queue.enqueue(listOf(track(3)))
            assertEquals(listOf(1L to false), writes)
            release.complete(Unit)
            withTimeout(2_000L) { done.await() }
            assertEquals(listOf(1L to false, 3L to true), writes)
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test
    fun librarySaveFailuresRetryTheLatestListAndReportOnce() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val retrying = Channel<Unit>(Channel.UNLIMITED)
        val retry = Channel<Unit>(Channel.UNLIMITED)
        val done = CompletableDeferred<Unit>()
        val writes = mutableListOf<Pair<Long, Boolean>>()
        var failures = 0
        val queue = MusicLibrarySaveQueue(scope, save = { tracks, invalidating ->
            writes += tracks.single().id to invalidating
            if (writes.size < 3) false else { done.complete(Unit); true }
        }, clearCache = {}, onFailure = { failures++ }, awaitRetry = { retrying.send(Unit); retry.receive() })
        try {
            queue.enqueue(listOf(track(1)), invalidate = true)
            withTimeout(2_000L) { retrying.receive() }
            retry.send(Unit)
            withTimeout(2_000L) { retrying.receive() }
            assertEquals(1, failures)
            queue.enqueue(listOf(track(2)))
            retry.send(Unit)
            withTimeout(2_000L) { done.await() }
            assertEquals(listOf(1L to true, 1L to true, 2L to true), writes)
            queue.enqueue(listOf(track(3)))
            yield()
            assertEquals(3L to false, writes.last())
        } finally { scope.cancel() }
    }

    @Test
    fun aClearBehindAnActiveWriteRunsLast() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val actions = mutableListOf<String>()
        val queue = MusicLibrarySaveQueue(scope, save = { _, _ ->
            actions += "save"; writing.complete(Unit); release.await(); true
        }, clearCache = { actions += "clear"; done.complete(Unit) }, onFailure = { fail("Unexpected failure") })
        try {
            queue.enqueue(listOf(track(1)))
            withTimeout(2_000L) { writing.await() }
            queue.enqueue(listOf(track(2)))
            queue.clear()
            release.complete(Unit)
            withTimeout(2_000L) { done.await() }
            assertEquals(listOf("save", "clear"), actions)
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test
    fun aNewScanCanSupersedeAPendingClearButStillInvalidatesOldCache() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val done = CompletableDeferred<Boolean>()
        val queue = MusicLibrarySaveQueue(scope, save = { _, invalidating ->
            done.complete(invalidating); true
        }, clearCache = { fail("The replacement should own the clear") }, onFailure = { fail("Unexpected failure") })
        try {
            queue.clear()
            queue.enqueue(listOf(track(1)))
            assertTrue(withTimeout(2_000L) { done.await() })
        } finally { scope.cancel() }
    }

    @Test
    fun aFailedClearIsReportedAndRetried() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val done = CompletableDeferred<Unit>()
        var clears = 0
        var failures = 0
        val queue = MusicLibrarySaveQueue(scope, save = { _, _ -> fail("Unexpected save"); false },
            clearCache = { if (++clears == 1) throw IOException("busy") else done.complete(Unit) },
            onFailure = { failures++ }, awaitRetry = { yield() })
        try {
            queue.clear()
            withTimeout(2_000L) { done.await() }
            assertEquals(2, clears)
            assertEquals(1, failures)
        } finally { scope.cancel() }
    }

    @Test
    fun aClearWakesAFailedWriteWithoutWaitingForTheRetryTimer() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val waiting = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val cleared = CompletableDeferred<Unit>()
        var attempts = 0
        val queue = MusicLibrarySaveQueue(scope, save = { _, _ -> attempts++; false },
            clearCache = { cleared.complete(Unit) }, onFailure = {},
            awaitRetry = { waiting.complete(Unit); never.await() })
        try {
            queue.enqueue(listOf(track(1)))
            withTimeout(2_000L) { waiting.await() }
            queue.clear()
            withTimeout(2_000L) { cleared.await() }
            assertEquals(1, attempts)
        } finally { scope.cancel() }
    }

    @Test
    fun restoringWithoutANewModeKeepsTheSavedSnapshot() {
        val saved = snapshot()
        assertSame(saved, saved.withRestoredPlaybackMode(null))
        assertSame(saved, saved.withRestoredPlaybackMode(saved.playbackMode))
    }

    @Test
    fun aNewRepeatModeWinsAndPreservesQueueSlotAndPosition() {
        val saved = snapshot().copy(queue = snapshot().queue.reversed(), currentIndex = 1,
            playbackMode = PlaybackMode.RANDOM)
        val restored = saved.withRestoredPlaybackMode(PlaybackMode.REPEAT_ONE)
        assertEquals(PlaybackMode.REPEAT_ONE, restored.playbackMode)
        assertEquals(listOf(0.0, 1.0, 2.0), restored.queue.map { it.sourceOrder })
        assertEquals(saved.queue[saved.currentIndex].sourceOrder, restored.queue[restored.currentIndex].sourceOrder, 0.0)
        assertEquals(saved.positionMs, restored.positionMs)
        assertTrue(restored.queue.all { it.playbackMode == PlaybackMode.REPEAT_ONE })
    }

    @Test
    fun requestingRandomModeKeepsTheActiveDuplicateSlotFirst() {
        val saved = snapshot()
        val restored = saved.withRestoredPlaybackMode(PlaybackMode.RANDOM)
        assertEquals(0, restored.currentIndex)
        assertEquals(1.0, restored.queue.first().sourceOrder, 0.0)
        assertEquals(saved.queue.map { it.sourceOrder }.toSet(), restored.queue.map { it.sourceOrder }.toSet())
        assertEquals(saved.positionMs, restored.positionMs)
    }

    @Test
    fun anEmptyRestoredQueueCanRetainTheNewMode() {
        val saved = snapshot().copy(queue = emptyList(), currentIndex = -1, positionMs = 0L)
        val restored = saved.withRestoredPlaybackMode(PlaybackMode.REPEAT_ONE)
        assertTrue(restored.queue.isEmpty())
        assertEquals(-1, restored.currentIndex)
        assertEquals(PlaybackMode.REPEAT_ONE, restored.playbackMode)
    }

    private fun track(id: Long, title: String = "Track $id") = MusicTrack(
        id, title, null, null, durationMs = 1_000L, dateAddedEpochSeconds = 1L,
        dateModifiedEpochSeconds = 10L, fileName = "$id.flac", fileSizeBytes = 100L,
        contentUri = "content://library/$id", titleSectionKey = "T", titleSortKey = title.lowercase(),
        audioPropertiesScanned = true,
    )

    private fun snapshot() = PlaybackSnapshot(
        queue = List(3) { index -> PlaybackQueueItem(
            mediaId = "duplicate", trackId = 1L, contentUri = "content://library/1",
            title = "Track", artist = null, album = null, durationMs = 10_000L,
            dateModifiedEpochSeconds = 10L, fileSizeBytes = 100L, sourceOrder = index.toDouble(),
        ) }, currentIndex = 1, positionMs = 4_000L, playbackMode = PlaybackMode.ORDER,
    )
}
