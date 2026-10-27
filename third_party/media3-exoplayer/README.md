# Media3 ExoPlayer float playback patch

Base artifact: `androidx.media3:media3-exoplayer:1.11.0` from Google Maven.
Local coordinate: `androidx.media3:media3-exoplayer:1.11.0.1-melox`.
The common, session, decoder and all other Media3 libraries remain at 1.11.0.

Media3 1.11.0's `SonicAudioProcessor` already accepts both 16-bit integer and
32-bit float PCM. `DefaultAudioSink` still excludes its processor chain and
software playback-parameter timing when float output is selected. This patch:

1. Adds the configured chain after either the integer or float PCM converter.
2. Allows software playback parameters for float PCM, retaining the existing
   tunneling and encoded-output exclusions.

The app always installs `PcmPlaybackProcessorChain`, using the original Sonic
and its media-duration mapping. The upstream default chain contains an
integer-only silence skipper and must not be used for float playback. Melox
does not expose silence skipping. At 1x with unchanged pitch/sample rate,
Sonic is inactive and the sink bypasses it. No system playback-speed API,
new native library, decoder, mixer or integer conversion is introduced.

`PatchFloatPlayback.java` relocates the existing chain statement and removes
the float-only timing exclusion. It preserves stack-map frames and patches
only two methods in `DefaultAudioSink.class`. `rebuild.py` verifies upstream
AAR/POM/class hashes, confirms that no other class, method or AAR member
changes, and publishes the local POM/AAR. See `upstream.json` and
`verification.json`. The input artifact is pinned rather than downloaded by
the script. Apache 2.0 terms are retained in `LICENSE`.

Rebuild with JDK 21, ASM and ASM Tree 9.9.1, supplying their local paths:

```sh
python3 third_party/media3-exoplayer/rebuild.py \
  --aar /path/to/media3-exoplayer-1.11.0.aar \
  --pom /path/to/media3-exoplayer-1.11.0.pom \
  --asm /path/to/asm-9.9.1.jar \
  --asm-tree /path/to/asm-tree-9.9.1.jar \
  --java-home /path/to/jdk-21
```

`PcmPlaybackProcessorChainTest` exercises the actual Sonic on float and integer
PCM, presets at 44.1/48/96 kHz, sub-16-bit signals, pitch, EOS, flush and media
time mapping. `FloatPlaybackSinkTest` exercises the real sink with a capturing
output boundary, including 24-bit input conversion and speed changes. Device
audio compatibility, listening quality and output latency are separate checks.
