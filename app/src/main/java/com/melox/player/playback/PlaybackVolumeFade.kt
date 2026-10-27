package com.melox.player.playback

import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Applies the optional play/pause volume fade to the session player. */
internal class PlaybackVolumeFade(
    private val player: Player,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val awaitFrame: suspend () -> Unit = { delay(16L) },
) {
    private var focusVolumeMultiplier = 1f
    private var fadeVolume = 1f
        set(value) {
            field = value
            player.volume = value * focusVolumeMultiplier
        }

    fun setFocusVolumeMultiplier(multiplier: Float) {
        focusVolumeMultiplier = multiplier
        player.volume = fadeVolume * multiplier
    }

    fun pauseImmediately() {
        volumeJob?.cancel()
        volumeJob = null
        pauseFadeOutPending = false
        resumingForFade = false
        internalPause = player.playWhenReady
        player.pause()
        fadeVolume = 1f
    }

    private var enabled = false
    private var volumeJob: Job? = null
    private var pauseFadeOutPending = false
    private var internalPause = false
    private var resumingForFade = false
    private var lastPlayWhenReady = player.playWhenReady

    val hasPendingPause: Boolean
        get() = pauseFadeOutPending

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            volumeJob?.cancel()
            volumeJob = null
            if (pauseFadeOutPending) {
                pauseFadeOutPending = false
                internalPause = true
                player.pause()
            }
            fadeVolume = 1f
        }
    }

    fun requestPlaybackIntent(play: Boolean) {
        volumeJob?.cancel()
        volumeJob = null
        pauseFadeOutPending = false
        if (play) {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            if (!player.playWhenReady) {
                fadeVolume = if (enabled) 0f else 1f
                if (enabled) resumingForFade = true
                player.play()
            }
            if (enabled) {
                volumeJob = scope.launch { rampVolume(fadeVolume, 1f, 250L) }
            } else {
                fadeVolume = 1f
            }
        } else if (player.playWhenReady) {
            if (enabled && player.playbackState == Player.STATE_READY) {
                pauseFadeOutPending = true
                volumeJob = scope.launch {
                    rampVolume(fadeVolume, 0f, 250L)
                    pauseFadeOutPending = false
                    internalPause = true
                    player.pause()
                    fadeVolume = 1f
                }
            } else {
                player.pause()
            }
        }
    }

    fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        val changed = playWhenReady != lastPlayWhenReady
        lastPlayWhenReady = playWhenReady
        if (isForcedPlaybackPause(playWhenReady, reason)) {
            volumeJob?.cancel()
            volumeJob = null
            pauseFadeOutPending = false
            internalPause = false
            resumingForFade = false
            fadeVolume = 1f
            return
        }
        if (!changed) return
        if (internalPause) {
            internalPause = false
            return
        }
        if (resumingForFade) {
            resumingForFade = false
            return
        }
        if (!enabled) return
        volumeJob?.cancel()
        if (playWhenReady) {
            fadeVolume = 0f
            volumeJob = scope.launch { rampVolume(0f, 1f, 250L) }
        } else if (player.playbackState == Player.STATE_READY) {
            pauseFadeOutPending = true
            resumingForFade = true
            player.play()
            volumeJob = scope.launch {
                rampVolume(fadeVolume, 0f, 250L)
                pauseFadeOutPending = false
                internalPause = true
                player.pause()
                fadeVolume = 1f
            }
        }
    }

    private suspend fun rampVolume(start: Float, end: Float, durationMs: Long) {
        val startMs = nowMs()
        while (true) {
            val fraction = ((nowMs() - startMs).toFloat() / durationMs)
                .coerceIn(0f, 1f)
            fadeVolume = start + (end - start) * fraction
            if (fraction >= 1f) break
            awaitFrame()
        }
    }

    fun release() {
        volumeJob?.cancel()
        fadeVolume = 1f
    }
}

internal fun isForcedPlaybackPause(playWhenReady: Boolean, reason: Int): Boolean =
    !playWhenReady &&
        reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST &&
        reason != Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
