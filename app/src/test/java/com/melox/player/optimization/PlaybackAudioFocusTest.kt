package com.melox.player.optimization

import android.media.AudioManager
import com.melox.player.model.TransientAudioFocusBehavior
import com.melox.player.playback.PlaybackAudioFocus
import org.junit.Assert.*
import org.junit.Test

class PlaybackAudioFocusTest {
    @Test
    fun duckingRestoresVolumeWithoutRestartingPlayback() {
        val fixture = Fixture()
        fixture.play()
        fixture.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertTrue(fixture.playing)
        assertEquals(0.2f, fixture.volume, 0f)
        fixture.change(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1f, fixture.volume, 0f)
        assertEquals(0, fixture.resumes)
    }

    @Test
    fun exclusiveTransientLossPausesEvenWithDuckingSelected() {
        val fixture = Fixture()
        fixture.play()
        fixture.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(fixture.playing)
        fixture.change(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(fixture.playing)
        assertEquals(1, fixture.resumes)
        fixture.change(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1, fixture.resumes)
    }

    @Test
    fun pauseModeAlsoPausesForDuckingRequests() {
        val fixture = Fixture(TransientAudioFocusBehavior.PAUSE)
        fixture.play()
        fixture.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertFalse(fixture.playing)
        assertEquals(1f, fixture.volume, 0f)
        fixture.change(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(fixture.playing)
    }

    @Test
    fun keepVolumeIgnoresTransientLossButPermanentLossStillStopsPlayback() {
        val fixture = Fixture(TransientAudioFocusBehavior.KEEP_VOLUME)
        fixture.play()
        fixture.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        fixture.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(fixture.playing)
        assertEquals(1f, fixture.volume, 0f)
        val stale = fixture.listener
        fixture.change(AudioManager.AUDIOFOCUS_LOSS)
        assertFalse(fixture.playing)
        stale(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(fixture.playing)
    }

    @Test
    fun manualPauseDuringTransientLossCancelsAutomaticResume() {
        val fixture = Fixture()
        fixture.play()
        fixture.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        val stale = fixture.listener
        fixture.focus.onUserPause()
        stale(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(fixture.playing)
        assertEquals(0, fixture.resumes)
    }

    @Test
    fun disablingFocusRestoresVolumeAndRejectsOldCallbacks() {
        val fixture = Fixture()
        fixture.play()
        fixture.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        val stale = fixture.listener
        fixture.focus.updateSettings(false, TransientAudioFocusBehavior.DUCK)
        stale(AudioManager.AUDIOFOCUS_LOSS)
        assertTrue(fixture.playing)
        assertEquals(1f, fixture.volume, 0f)
        fixture.focus.onPlaybackIntentChanged(false)
        fixture.play()
        assertEquals(1, fixture.requests)
    }

    @Test
    fun delayedFocusResumesOnlyAfterGrantAndFailedFocusNeverResumes() {
        for (result in listOf(AudioManager.AUDIOFOCUS_REQUEST_DELAYED, AudioManager.AUDIOFOCUS_REQUEST_FAILED)) {
            val fixture = Fixture(result = result)
            fixture.play()
            assertFalse(fixture.playing)
            fixture.change(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED, fixture.playing)
        }
    }

    @Test
    fun replacingARequestAndReleasingRejectEarlierFocusEvents() {
        val fixture = Fixture()
        fixture.play()
        val stale = fixture.listener
        fixture.focus.updateSettings(false, TransientAudioFocusBehavior.DUCK)
        fixture.focus.updateSettings(true, TransientAudioFocusBehavior.PAUSE)
        stale(AudioManager.AUDIOFOCUS_LOSS)
        assertTrue(fixture.playing)
        fixture.focus.release()
        fixture.change(AudioManager.AUDIOFOCUS_LOSS)
        assertTrue(fixture.playing)
        assertEquals(2, fixture.requests)
        assertEquals(2, fixture.abandons)
    }

    @Test
    fun changingTransientPolicyAppliesWithoutTakingFocusFromTheInterruptingApp() {
        val fixture = Fixture()
        fixture.play()
        fixture.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        fixture.focus.updateSettings(true, TransientAudioFocusBehavior.PAUSE)
        assertFalse(fixture.playing)
        assertEquals(1, fixture.requests)
        fixture.focus.updateSettings(true, TransientAudioFocusBehavior.KEEP_VOLUME)
        assertFalse(fixture.playing)
        fixture.change(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(fixture.playing)
        assertEquals(1, fixture.resumes)
    }

    private class Fixture(
        behavior: TransientAudioFocusBehavior = TransientAudioFocusBehavior.DUCK,
        val result: Int = AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
    ) {
        var playing = false
        var volume = 1f
        var resumes = 0
        var requests = 0
        var abandons = 0
        var listener: (Int) -> Unit = {}
        val focus: PlaybackAudioFocus = PlaybackAudioFocus(
            requestFocus = { listener = it; requests++; result },
            abandonFocus = { abandons++ },
            isPlaybackRequested = { playing },
            pause = { updatePlaying(false) },
            resume = { resumes++; updatePlaying(true) },
            setVolumeMultiplier = { volume = it },
        ).apply { updateSettings(true, behavior) }

        fun play() = updatePlaying(true)
        fun change(value: Int) = listener(value)
        private fun updatePlaying(value: Boolean) {
            playing = value
            focus.onPlaybackIntentChanged(value)
        }
    }
}
