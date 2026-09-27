package com.melox.player.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

@UnstableApi
internal class PrecisionRenderersFactory(
    context: Context,
    private val highPrecisionEnabled: Boolean,
    private val onFloatOutputChanged: (Boolean) -> Unit,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink = object : ForwardingAudioSink(
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(highPrecisionEnabled)
            .setEnableAudioOutputPlaybackParameters(false)
            .build(),
    ) {
        override fun setListener(listener: AudioSink.Listener) {
            super.setListener(object : AudioSink.Listener by listener {
                override fun onAudioTrackInitialized(config: AudioSink.AudioTrackConfig) {
                    listener.onAudioTrackInitialized(config)
                    onFloatOutputChanged(highPrecisionEnabled && config.encoding == C.ENCODING_PCM_FLOAT)
                }

            })
        }
    }
}
