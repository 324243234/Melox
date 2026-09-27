package com.melox.player.playback

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Applies the optional play/pause volume fade to the session player. */
internal class PlaybackVolumeFade(
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
) {
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
            player.volume = 1f
        }
    }

    fun requestPlaybackIntent(play: Boolean) {
        volumeJob?.cancel()
        volumeJob = null
        pauseFadeOutPending = false
        if (play) {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            if (!player.playWhenReady) {
                player.volume = if (enabled) 0f else 1f
                if (enabled) resumingForFade = true
                player.play()
            }
            if (enabled) {
                volumeJob = scope.launch { rampVolume(player.volume, 1f, 250L) }
            } else {
                player.volume = 1f
            }
        } else if (player.playWhenReady) {
            if (enabled && player.playbackState == Player.STATE_READY) {
                pauseFadeOutPending = true
                volumeJob = scope.launch {
                    rampVolume(player.volume, 0f, 250L)
                    pauseFadeOutPending = false
                    internalPause = true
                    player.pause()
                    player.volume = 1f
                }
            } else {
                player.pause()
            }
        }
    }

    fun onEvents() {
        val playWhenReady = player.playWhenReady
        if (playWhenReady == lastPlayWhenReady) return
        lastPlayWhenReady = playWhenReady
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
            player.volume = 0f
            volumeJob = scope.launch { rampVolume(0f, 1f, 250L) }
        } else if (player.playbackState == Player.STATE_READY) {
            resumingForFade = true
            player.play()
            volumeJob = scope.launch {
                rampVolume(player.volume, 0f, 250L)
                internalPause = true
                player.pause()
                player.volume = 1f
            }
        }
    }

    private suspend fun rampVolume(start: Float, end: Float, durationMs: Long) {
        val startMs = SystemClock.elapsedRealtime()
        while (true) {
            val fraction = ((SystemClock.elapsedRealtime() - startMs).toFloat() / durationMs)
                .coerceIn(0f, 1f)
            player.volume = start + (end - start) * fraction
            if (fraction >= 1f) break
            delay(16L)
        }
    }

    fun release() {
        volumeJob?.cancel()
        player.volume = 1f
    }
}
