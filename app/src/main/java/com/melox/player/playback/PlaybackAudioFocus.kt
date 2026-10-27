package com.melox.player.playback

import android.media.AudioManager
import com.melox.player.model.TransientAudioFocusBehavior

/** Coordinates platform focus with playback intent, independently of the decoder instance. */
internal class PlaybackAudioFocus(
    private val requestFocus: (onChange: (Int) -> Unit) -> Int,
    private val abandonFocus: () -> Unit,
    private val isPlaybackRequested: () -> Boolean,
    private val pause: () -> Unit,
    private val resume: () -> Unit,
    private val setVolumeMultiplier: (Float) -> Unit,
) {
    private var enabled = true
    private var behavior = TransientAudioFocusBehavior.DUCK
    private var requested = false
    private var granted = false
    private var resumeOnGain = false
    private var changingPlayback = false
    private var lastFocusChange = AudioManager.AUDIOFOCUS_GAIN
    private var generation = 0L
    var volumeMultiplier = 1f
        private set

    fun updateSettings(enabled: Boolean, behavior: TransientAudioFocusBehavior) {
        if (this.enabled == enabled && this.behavior == behavior) return
        val wasEnabled = this.enabled
        this.enabled = enabled
        this.behavior = behavior
        when {
            !enabled -> clearFocus()
            !wasEnabled && isPlaybackRequested() -> acquireFocus()
            requested && !granted && lastFocusChange != AudioManager.AUDIOFOCUS_GAIN ->
                onFocusChange(lastFocusChange)
        }
    }

    fun onPlaybackIntentChanged(play: Boolean) {
        if (changingPlayback) return
        if (!play) {
            clearFocus(restoreVolume = !isPlaybackRequested())
        } else if (enabled && !granted) {
            resumeOnGain = false
            acquireFocus()
        }
    }

    fun onUserPause() {
        clearFocus(restoreVolume = !isPlaybackRequested())
    }

    fun release() {
        clearFocus()
    }

    private fun acquireFocus() {
        val requestGeneration = ++generation
        requested = true
        when (requestFocus { change ->
            if (enabled && requestGeneration == generation) onFocusChange(change)
        }) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                granted = true
                updateVolume(1f)
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                granted = false
                resumeOnGain = true
                pauseInternally()
            }
            else -> {
                pauseInternally()
                clearFocus()
            }
        }
    }

    private fun onFocusChange(change: Int) {
        lastFocusChange = change
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                granted = true
                updateVolume(1f)
                val shouldResume = resumeOnGain
                resumeOnGain = false
                if (shouldResume) changePlayback(resume)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                pauseInternally()
                clearFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                granted = false
                when {
                    behavior == TransientAudioFocusBehavior.KEEP_VOLUME -> updateVolume(1f)
                    behavior == TransientAudioFocusBehavior.DUCK &&
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> updateVolume(0.2f)
                    else -> {
                        resumeOnGain = resumeOnGain || isPlaybackRequested()
                        pauseInternally()
                        updateVolume(1f)
                    }
                }
            }
        }
    }

    private fun pauseInternally() = changePlayback(pause)

    private fun changePlayback(action: () -> Unit) {
        changingPlayback = true
        try { action() } finally { changingPlayback = false }
    }

    private fun updateVolume(value: Float) {
        volumeMultiplier = value
        setVolumeMultiplier(value)
    }

    private fun clearFocus(restoreVolume: Boolean = true) {
        generation++
        lastFocusChange = AudioManager.AUDIOFOCUS_GAIN
        granted = false
        resumeOnGain = false
        if (requested) {
            requested = false
            abandonFocus()
        }
        if (restoreVolume) updateVolume(1f)
    }
}
