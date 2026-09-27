// SPDX-License-Identifier: Apache-2.0
package com.melox.player.ui.screen.playback

import androidx.compose.runtime.mutableDoubleStateOf

/** A repeat starts a new monotonic clock interval without replacing the lyric document. */
internal class LyricPlaybackClock(initialPositionMs: Long, initialIteration: Long) {
    val positionMs = mutableDoubleStateOf(initialPositionMs.coerceAtLeast(0L).toDouble())
    private var iteration = initialIteration

    fun resetForIteration(playbackIteration: Long, sampledPositionMs: Long): Boolean {
        if (iteration == playbackIteration) return false
        iteration = playbackIteration
        positionMs.doubleValue = sampledPositionMs.coerceAtLeast(0L).toDouble()
        return true
    }
}
