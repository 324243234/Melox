package com.melox.player.data.library

import com.melox.player.model.MusicTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Serializes the latest library snapshot and explicit cache invalidations. */
internal class MusicLibrarySaveQueue(
    private val scope: CoroutineScope,
    private val save: suspend (List<MusicTrack>, Boolean) -> Boolean,
    private val clearCache: suspend () -> Unit,
    private val onFailure: () -> Unit,
    private val awaitRetry: suspend () -> Unit = { delay(5_000L) },
) {
    private data class Operation(val tracks: List<MusicTrack>?)
    private var pending: Operation? = null
    private var revision = 0L
    private var invalidationPending = false
    private var job: Job? = null
    private var retrySignal: CompletableDeferred<Unit>? = null

    fun enqueue(tracks: List<MusicTrack>, invalidate: Boolean = false) {
        invalidationPending = invalidationPending || invalidate
        enqueue(Operation(tracks))
    }

    fun clear() {
        invalidationPending = true
        enqueue(Operation(null))
    }

    private fun enqueue(operation: Operation) {
        pending = operation
        revision += 1L
        retrySignal?.complete(Unit)
        if (job?.isActive == true) return
        job = scope.launch(start = CoroutineStart.LAZY) {
            var failureReported = false
            while (true) {
                val snapshot = pending ?: return@launch
                val savingRevision = revision
                val invalidating = invalidationPending
                val saved = try {
                    withContext(NonCancellable) {
                        val tracks = snapshot.tracks
                        if (tracks == null) { clearCache(); true } else save(tracks, invalidating)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    false
                }
                if (saved) {
                    if (savingRevision == revision) {
                        pending = null
                        invalidationPending = false
                    }
                    failureReported = false
                } else {
                    if (!failureReported) {
                        onFailure()
                        failureReported = true
                    }
                    if (!isActive) return@launch
                    if (savingRevision == revision) waitForRetry()
                }
            }
        }.also(Job::start)
    }

    private suspend fun waitForRetry() {
        val signal = CompletableDeferred<Unit>()
        retrySignal = signal
        try {
            coroutineScope {
                val retry = launch { awaitRetry(); signal.complete(Unit) }
                try { signal.await() } finally { retry.cancel() }
            }
        } finally {
            retrySignal = null
        }
    }
}
