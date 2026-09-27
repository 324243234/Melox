package com.melox.player.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.FlacFrameReader
import androidx.media3.extractor.FlacMetadataReader
import androidx.media3.extractor.FlacStreamMetadata
import androidx.media3.extractor.ForwardingExtractorInput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.flac.FlacConstants
import androidx.media3.extractor.flac.FlacExtractor

private const val FLAC_STREAM_MARKER = 0x664C6143
private const val FLAC_FRAME_RESYNC_BUFFER_SIZE = 64 * 1024
private const val MAX_FLAC_FRAME_RESYNC_BYTES = 32 * 1024 * 1024
private const val RESERVED_FLAC_METADATA_TYPE = 7
private const val MISSING_FLAC_FRAME_SYNC_MESSAGE = "First frame does not start with sync code."

@UnstableApi
internal class PlaybackExtractorsFactory(
    private val delegate: DefaultExtractorsFactory = DefaultExtractorsFactory(),
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().withFlacRecovery()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = delegate.createExtractors(uri, responseHeaders).withFlacRecovery()
}

@UnstableApi
private fun Array<Extractor>.withFlacRecovery(): Array<Extractor> =
    map { extractor ->
        if (extractor is FlacExtractor) {
            ResynchronizingFlacExtractor(extractor)
        } else {
            extractor
        }
    }.toTypedArray()

@UnstableApi
internal class ResynchronizingFlacExtractor(
    private val delegate: FlacExtractor,
) : Extractor {
    private var streamMetadata: FlacStreamMetadata? = null
    private var metadataMaskState: FlacMetadataMaskState? = null
    private var recoveryAttempted = false

    override fun sniff(input: ExtractorInput): Boolean {
        val recognized = delegate.sniff(input)
        if (recognized) {
            input.peekFlacRecoveryMetadata()?.let { metadata ->
                streamMetadata = metadata.streamMetadata
                metadataMaskState = FlacMetadataMaskState(metadata.firstMetadataHeaderPosition)
            }
        }
        return recognized
    }

    override fun init(output: ExtractorOutput) = delegate.init(output)

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = try {
        delegate.read(input.withDuplicateStreamInfoHeadersMasked(), seekPosition)
    } catch (exception: ParserException) {
        val metadata = streamMetadata
        if (
            recoveryAttempted ||
            metadata == null ||
            !exception.contentIsMalformed ||
            exception.dataType != C.DATA_TYPE_MEDIA ||
            !exception.message.startsWith(MISSING_FLAC_FRAME_SYNC_MESSAGE)
        ) {
            throw exception
        }

        recoveryAttempted = true
        if (!resynchronizeFlacFrame(input, metadata)) throw exception
        delegate.read(input.withDuplicateStreamInfoHeadersMasked(), seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        if (position == 0L) {
            recoveryAttempted = false
            metadataMaskState?.reset()
        }
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate

    private fun ExtractorInput.withDuplicateStreamInfoHeadersMasked(): ExtractorInput =
        metadataMaskState?.let { state ->
            DuplicateStreamInfoMaskingExtractorInput(
                input = this,
                state = state,
            )
        } ?: this
}

@UnstableApi
internal fun ExtractorInput.peekFlacRecoveryMetadata(): FlacRecoveryMetadata? = try {
    resetPeekPosition()
    FlacMetadataReader.peekId3Metadata(
        this,
        /* parseData = */ false,
        /* ignoreArtwork = */ true,
    )
    val streamMarkerPosition = peekPosition
    val streamInfo = ByteArray(
        FlacConstants.STREAM_MARKER_SIZE + FlacConstants.STREAM_INFO_BLOCK_SIZE,
    )
    peekFully(streamInfo, 0, streamInfo.size)
    val header = ParsableByteArray(streamInfo)
    val streamMarker = header.readInt()
    val metadataType = header.readUnsignedByte() and 0x7F
    val metadataLength = header.readUnsignedInt24()
    val streamInfoDataSize =
        FlacConstants.STREAM_INFO_BLOCK_SIZE - FlacConstants.METADATA_BLOCK_HEADER_SIZE
    if (
        streamMarker != FLAC_STREAM_MARKER ||
        metadataType != FlacConstants.METADATA_TYPE_STREAM_INFO ||
        metadataLength != streamInfoDataSize
    ) {
        null
    } else {
        val streamMetadata = FlacStreamMetadata(
            streamInfo,
            FlacConstants.STREAM_MARKER_SIZE + FlacConstants.METADATA_BLOCK_HEADER_SIZE,
        )
        FlacRecoveryMetadata(
            streamMetadata = streamMetadata,
            firstMetadataHeaderPosition = streamMarkerPosition + FlacConstants.STREAM_MARKER_SIZE,
        )
    }
} catch (_: Exception) {
    null
} finally {
    resetPeekPosition()
}

@UnstableApi
internal data class FlacRecoveryMetadata(
    val streamMetadata: FlacStreamMetadata,
    val firstMetadataHeaderPosition: Long,
)

@UnstableApi
internal class DuplicateStreamInfoMaskingExtractorInput(
    input: ExtractorInput,
    private val state: FlacMetadataMaskState,
) : ForwardingExtractorInput(input) {
    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        val startPosition = position
        return super.read(target, offset, length).also { bytesRead ->
            if (bytesRead > 0) maskHeaders(target, offset, bytesRead, startPosition)
        }
    }

    override fun readFully(
        target: ByteArray,
        offset: Int,
        length: Int,
        allowEndOfInput: Boolean,
    ): Boolean {
        val startPosition = position
        return super.readFully(target, offset, length, allowEndOfInput).also { completed ->
            if (completed) maskHeaders(target, offset, length, startPosition)
        }
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        val startPosition = position
        super.readFully(target, offset, length)
        maskHeaders(target, offset, length, startPosition)
    }

    override fun peek(target: ByteArray, offset: Int, length: Int): Int {
        val startPosition = peekPosition
        return super.peek(target, offset, length).also { bytesRead ->
            if (bytesRead > 0) maskHeaders(target, offset, bytesRead, startPosition)
        }
    }

    override fun peekFully(
        target: ByteArray,
        offset: Int,
        length: Int,
        allowEndOfInput: Boolean,
    ): Boolean {
        val startPosition = peekPosition
        return super.peekFully(target, offset, length, allowEndOfInput).also { completed ->
            if (completed) maskHeaders(target, offset, length, startPosition)
        }
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        val startPosition = peekPosition
        super.peekFully(target, offset, length)
        maskHeaders(target, offset, length, startPosition)
    }

    private fun maskHeaders(
        target: ByteArray,
        targetOffset: Int,
        length: Int,
        sourceStartPosition: Long,
    ) = state.maskHeaders(target, targetOffset, length, sourceStartPosition)
}

internal class FlacMetadataMaskState(
    private val firstMetadataHeaderPosition: Long,
) {
    private var nextMetadataHeaderPosition = firstMetadataHeaderPosition
    private var streamInfoSeen = false
    private var metadataFinished = false

    fun reset() {
        nextMetadataHeaderPosition = firstMetadataHeaderPosition
        streamInfoSeen = false
        metadataFinished = false
    }

    @OptIn(UnstableApi::class)
    fun maskHeaders(
        target: ByteArray,
        targetOffset: Int,
        length: Int,
        sourceStartPosition: Long,
    ) {
        if (metadataFinished || length < FlacConstants.METADATA_BLOCK_HEADER_SIZE) return
        val sourceEndPosition = sourceStartPosition + length
        while (nextMetadataHeaderPosition >= sourceStartPosition) {
            val headerEndPosition =
                nextMetadataHeaderPosition + FlacConstants.METADATA_BLOCK_HEADER_SIZE
            if (headerEndPosition > sourceEndPosition) return

            val headerOffset = targetOffset +
                (nextMetadataHeaderPosition - sourceStartPosition).toInt()
            val firstHeaderByte = target[headerOffset].toInt() and 0xFF
            val isLastMetadataBlock = firstHeaderByte and 0x80 != 0
            val metadataType = firstHeaderByte and 0x7F
            if (metadataType == FlacConstants.METADATA_TYPE_STREAM_INFO) {
                if (streamInfoSeen) {
                    target[headerOffset] = (
                        (firstHeaderByte and 0x80) or RESERVED_FLAC_METADATA_TYPE
                    ).toByte()
                } else {
                    streamInfoSeen = true
                }
            }
            val blockLength =
                ((target[headerOffset + 1].toInt() and 0xFF) shl 16) or
                    ((target[headerOffset + 2].toInt() and 0xFF) shl 8) or
                    (target[headerOffset + 3].toInt() and 0xFF)
            nextMetadataHeaderPosition = headerEndPosition + blockLength
            metadataFinished = isLastMetadataBlock
            if (metadataFinished) return
        }
    }
}

@UnstableApi
internal fun resynchronizeFlacFrame(
    input: ExtractorInput,
    streamMetadata: FlacStreamMetadata,
    maxSearchBytes: Int = MAX_FLAC_FRAME_RESYNC_BYTES,
): Boolean {
    val overlapBytes = FlacConstants.MAX_FRAME_HEADER_SIZE
    val buffer = ByteArray(FLAC_FRAME_RESYNC_BUFFER_SIZE + overlapBytes)
    val sampleNumberHolder = FlacFrameReader.SampleNumberHolder()
    var searchedBytes = 0

    while (searchedBytes < maxSearchBytes) {
        input.resetPeekPosition()
        val bytesToPeek = minOf(buffer.size, maxSearchBytes - searchedBytes)
        var bytesPeeked = 0
        while (bytesPeeked < bytesToPeek) {
            val read = input.peek(buffer, bytesPeeked, bytesToPeek - bytesPeeked)
            if (read == C.RESULT_END_OF_INPUT) break
            bytesPeeked += read
        }
        input.resetPeekPosition()

        val lastCandidateOffset = bytesPeeked - overlapBytes - 1
        if (lastCandidateOffset >= 0) {
            val candidateData = ParsableByteArray(buffer, bytesPeeked)
            for (offset in 0..lastCandidateOffset) {
                if (
                    buffer[offset].toInt() and 0xFF != 0xFF ||
                    buffer[offset + 1].toInt() and 0xFE != 0xF8
                ) {
                    continue
                }
                candidateData.setPosition(offset)
                val frameStartMarker =
                    ((buffer[offset].toInt() and 0xFF) shl 8) or
                        (buffer[offset + 1].toInt() and 0xFF)
                if (
                    FlacFrameReader.checkAndReadFrameHeader(
                        candidateData,
                        streamMetadata,
                        frameStartMarker,
                        sampleNumberHolder,
                    )
                ) {
                    input.skipFully(offset)
                    return true
                }
            }
        }

        val bytesToSkip = bytesPeeked - overlapBytes
        if (bytesToSkip <= 0) return false
        input.skipFully(bytesToSkip)
        searchedBytes += bytesToSkip
    }
    return false
}
