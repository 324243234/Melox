package com.melox.player

import com.melox.player.data.repository.TrackSourceDeletionResult
import com.melox.player.data.repository.deleteAndReconcileTrackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSourceDeletionTest {
    @Test
    fun alreadyCancelledUiJobDoesNotStartDeletion() = runBlocking {
        var attempted = false
        val job = launch {
            coroutineContext.cancel()
            deleteAndReconcileTrackSource(
                delete = {
                    attempted = true
                    TrackSourceDeletionResult.Deleted
                },
                onDeleted = { error("Cancelled operation must not reconcile") },
            )
        }
        job.join()
        assertFalse(attempted)
    }

    @Test
    fun completedDeletionReconcilesWhenTheUiJobIsCancelledDuringFileIo() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var reconciled = false
        val job = launch {
            deleteAndReconcileTrackSource(
                delete = {
                    withContext(Dispatchers.Default) {
                        started.complete(Unit)
                        finish.await()
                        TrackSourceDeletionResult.Deleted
                    }
                },
                onDeleted = { reconciled = true },
            )
        }
        started.await()
        job.cancel()
        finish.complete(Unit)
        job.join()
        assertTrue(reconciled)
    }

    @Test
    fun deletionFailureAndPendingPermissionsKeepLibraryAndQueueUntouched() = runBlocking {
        var reconciled = false
        for (result in listOf(
            TrackSourceDeletionResult.Failed,
            TrackSourceDeletionResult.StoragePermissionRequired,
        )) {
            assertEquals(
                result,
                deleteAndReconcileTrackSource({ result }, { reconciled = true }),
            )
            assertFalse(reconciled)
        }
    }

    @Test
    fun operationCancellationBeforeConfirmedDeletionDoesNotReconcile() = runBlocking {
        var reconciled = false
        try {
            deleteAndReconcileTrackSource(
                delete = { throw CancellationException("Source operation cancelled") },
                onDeleted = { reconciled = true },
            )
            error("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertFalse(reconciled)
        }
    }
}
