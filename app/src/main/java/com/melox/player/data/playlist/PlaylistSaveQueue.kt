package com.melox.player.data.playlist

import com.melox.player.model.LocalPlaylist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Serializes optimistic edits and retries the latest unsaved playlist snapshot. */
internal class PlaylistSaveQueue(
    private val scope: CoroutineScope,
    private val save: suspend (List<LocalPlaylist>) -> Unit,
    private val onFailure: () -> Unit,
    private val awaitRetry: suspend () -> Unit = { delay(5_000L) },
) {
    private var pending: List<LocalPlaylist>? = null
    private var revision = 0L
    private var job: Job? = null

    fun enqueue(playlists: List<LocalPlaylist>) {
        pending = playlists
        revision += 1L
        if (job?.isActive == true) return
        job = scope.launch(start = CoroutineStart.LAZY) {
            var failureReported = false
            while (true) {
                val snapshot = pending ?: return@launch
                val savingRevision = revision
                try {
                    // Finish an already-started atomic write when the screen is released.
                    withContext(NonCancellable) { save(snapshot) }
                    if (savingRevision == revision) pending = null
                    failureReported = false
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    if (!failureReported) {
                        onFailure()
                        failureReported = true
                    }
                    if (!isActive) return@launch
                    if (savingRevision == revision) awaitRetry()
                }
            }
        }.also(Job::start)
    }
}
