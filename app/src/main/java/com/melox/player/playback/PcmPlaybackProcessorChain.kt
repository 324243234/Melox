package com.melox.player.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi

/** Uses Media3's float-capable Sonic without its integer-only silence-skipping processor. */
@UnstableApi
internal class PcmPlaybackProcessorChain : AudioProcessorChain {
    private val sonic = SonicAudioProcessor()
    private val processors = arrayOf<AudioProcessor>(sonic)

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(parameters: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(parameters.speed)
        sonic.setPitch(parameters.pitch)
        return parameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    override fun getMediaDuration(playoutDuration: Long): Long =
        if (sonic.isActive) sonic.getMediaDuration(playoutDuration) else playoutDuration

    override fun getSkippedOutputFrameCount(): Long = 0L
}
