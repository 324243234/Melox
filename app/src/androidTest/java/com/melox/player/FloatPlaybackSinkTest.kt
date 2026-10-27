package com.melox.player

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.melox.player.playback.PcmPlaybackProcessorChain
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual sink and timing pipeline without opening a speaker or DAC. */
@RunWith(AndroidJUnit4::class)
class FloatPlaybackSinkTest {
    @Test
    fun highResolutionInputStaysFloatWhileSpeedAndMediaPositionChangeTogether() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (encoding in listOf(C.ENCODING_PCM_24BIT, C.ENCODING_PCM_FLOAT)) {
                val provider = CapturingProvider()
                val sink = DefaultAudioSink.Builder(instrumentation.targetContext)
                    .setAudioOutputProvider(provider)
                    .setAudioProcessorChain(PcmPlaybackProcessorChain())
                    .setEnableFloatOutput(true)
                    .setEnableAudioOutputPlaybackParameters(false)
                    .build()
                try {
                    for ((initialSpeed, speed) in listOf(2f to 2f, 0.5f to 0.5f, 1f to 1f, 1f to 2f)) {
                        sink.flush()
                        sink.setPlaybackParameters(PlaybackParameters(initialSpeed, 1f))
                        sink.configure(AudioSink.AudioSinkConfig.Builder(Format.Builder()
                            .setSampleMimeType(MimeTypes.AUDIO_RAW)
                            .setSampleRate(RATE).setChannelCount(2).setPcmEncoding(encoding)
                            .build()).build())
                        sink.play()
                        var offset = 0
                        var switchFrame = RATE
                        while (offset < RATE) {
                            if (initialSpeed != speed && switchFrame == RATE && offset >= RATE / 2) {
                                switchFrame = offset
                                sink.setPlaybackParameters(PlaybackParameters(speed, 1f))
                            }
                            val frames = minOf(1_024, RATE - offset)
                            val input = ByteBuffer.allocateDirect(frames * 2 * if (encoding == C.ENCODING_PCM_FLOAT) 4 else 3)
                                .order(ByteOrder.nativeOrder())
                            repeat(frames) { frame ->
                                val value = (0.000001 * sin(2 * PI * 440 * (offset + frame) / RATE)).toFloat()
                                repeat(2) {
                                    if (encoding == C.ENCODING_PCM_FLOAT) input.putFloat(value)
                                    else {
                                        val pcm = (value * 8_388_608).toInt()
                                        input.put(pcm.toByte()).put((pcm shr 8).toByte()).put((pcm shr 16).toByte())
                                    }
                                }
                            }
                            input.flip()
                            var attempts = 0
                            while (!sink.handleBuffer(input, offset * 1_000_000L / RATE, 1)) {
                                check(++attempts < 100) { "Sink did not consume PCM input" }
                            }
                            offset += frames
                        }
                        var attempts = 0
                        do {
                            sink.playToEndOfStream()
                            check(++attempts < 100) { "Sink did not drain" }
                        } while (!sink.isEnded)
                        assertEquals(speed, sink.playbackParameters.speed, 0f)
                        assertEquals(C.ENCODING_PCM_FLOAT, provider.output.config.encoding)
                        val expectedFrames = switchFrame / initialSpeed.toDouble() + (RATE - switchFrame) / speed.toDouble()
                        assertEquals(expectedFrames, provider.output.bytes.size() / 8.0, RATE * 0.01)
                        assertEquals(1_000_000.0, sink.getCurrentPositionUs(true).toDouble(), 20_000.0)
                        val data = ByteBuffer.wrap(provider.output.bytes.toByteArray()).order(ByteOrder.nativeOrder())
                        var peak = 0f
                        while (data.hasRemaining()) peak = maxOf(peak, abs(data.float))
                        assertTrue("Low-level float signal was lost", peak > 0.0000001f)
                        assertTrue(peak < 0.000002f)
                    }
                } finally { sink.release() }
            }
        }
    }

    private class CapturingProvider : AudioOutputProvider {
        lateinit var output: CapturingOutput
        override fun getFormatSupport(config: AudioOutputProvider.FormatConfig) =
            AudioOutputProvider.FormatSupport.Builder()
                .setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY).build()
        override fun getOutputConfig(config: AudioOutputProvider.FormatConfig) =
            AudioOutputProvider.OutputConfig.Builder()
                .setEncoding(config.format.pcmEncoding).setSampleRate(config.format.sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setBufferSize(16_384)
                .setAudioAttributes(config.audioAttributes).setUsePlaybackParameters(false).build()
        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput =
            CapturingOutput(config).also { output = it }
        override fun addListener(listener: AudioOutputProvider.Listener) = Unit
        override fun removeListener(listener: AudioOutputProvider.Listener) = Unit
        override fun release() = Unit
    }

    private class CapturingOutput(val config: AudioOutputProvider.OutputConfig) : AudioOutput {
        val bytes = ByteArrayOutputStream()
        override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            bytes.write(data)
            return true
        }
        override fun play() = Unit
        override fun pause() = Unit
        override fun flush() { bytes.reset() }
        override fun stop() = Unit
        override fun release() = Unit
        override fun setVolume(volume: Float) = Unit
        override fun isOffloadedPlayback() = false
        override fun getAudioSessionId() = 1
        override fun getSampleRate() = config.sampleRate
        override fun getBufferSizeInFrames() = config.bufferSize / 8L
        override fun getPositionUs() = bytes.size() / 8L * 1_000_000L / config.sampleRate
        override fun getPlaybackParameters() = PlaybackParameters.DEFAULT
        override fun isStalled() = false
        override fun addListener(listener: AudioOutput.Listener) = Unit
        override fun removeListener(listener: AudioOutput.Listener) = Unit
        override fun setPlaybackParameters(playbackParams: PlaybackParameters) = Unit
        override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit
        override fun setOffloadEndOfStream() = Unit
        override fun attachAuxEffect(effectId: Int) = Unit
        override fun setAuxEffectSendLevel(level: Float) = Unit
        override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit
    }

    private companion object { const val RATE = 48_000 }
}
