// SPDX-License-Identifier: Apache-2.0
package com.melox.player.ui.screen.playback

import androidx.compose.runtime.mutableDoubleStateOf

/** Each media transition starts a new monotonic interval, even for the same lyric document. */
internal class LyricPlaybackClock(
    initialPositionMs: Long,
    initialIteration: Long,
    initialDiscontinuityRevision: Long = 0L,
) {
    val positionMs = mutableDoubleStateOf(initialPositionMs.coerceAtLeast(0L).toDouble())
    private var iteration = initialIteration
    private var discontinuityRevision = initialDiscontinuityRevision
    private var wasActive = false

    fun updateFromPlayback(
        playbackIteration: Long,
        sampledPositionMs: Long,
        active: Boolean,
        positionDiscontinuityRevision: Long = 0L,
    ): Boolean {
        val iterationReset = resetForIteration(playbackIteration, sampledPositionMs)
        val seekReset = discontinuityRevision != positionDiscontinuityRevision
        discontinuityRevision = positionDiscontinuityRevision
        val reanchor = iterationReset || seekReset || !active || !wasActive
        wasActive = active
        if (reanchor) {
            positionMs.doubleValue = sampledPositionMs.coerceAtLeast(0L).toDouble()
        }
        return reanchor
    }

    fun resetForIteration(playbackIteration: Long, sampledPositionMs: Long): Boolean {
        if (iteration == playbackIteration) return false
        iteration = playbackIteration
        positionMs.doubleValue = sampledPositionMs.coerceAtLeast(0L).toDouble()
        return true
    }
}
