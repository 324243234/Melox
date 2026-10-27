package com.melox.player.data.playback

import com.melox.player.model.PlaybackSnapshot
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.CheckedOutputStream

internal data class PlaybackSnapshotRecord(val snapshot: PlaybackSnapshot, val generation: Long)

internal data class PlaybackPositionCheckpoint(
    val generation: Long,
    val currentIndex: Int,
    val positionMs: Long,
) {
    fun applyTo(record: PlaybackSnapshotRecord): PlaybackSnapshot? =
        if (generation != 0L && generation == record.generation &&
            currentIndex in record.snapshot.queue.indices && positionMs >= 0L
        ) {
            record.snapshot.copy(currentIndex = currentIndex, positionMs = positionMs)
        } else null
}

/** A constant-size progress update that cannot attach to a different saved queue. */
internal object PlaybackPositionCheckpointCodec {
    private const val MAGIC = 0x4D45504F
    private const val VERSION = 1

    fun write(stream: OutputStream, checkpoint: PlaybackPositionCheckpoint) {
        val checksum = CRC32()
        val output = DataOutputStream(CheckedOutputStream(stream, checksum))
        output.writeInt(MAGIC)
        output.writeInt(VERSION)
        output.writeLong(checkpoint.generation)
        output.writeInt(checkpoint.currentIndex)
        output.writeLong(checkpoint.positionMs)
        output.writeLong(checksum.value)
        output.flush()
    }

    fun read(stream: InputStream): PlaybackPositionCheckpoint {
        val checksum = CRC32()
        val input = DataInputStream(CheckedInputStream(stream, checksum))
        if (input.readInt() != MAGIC || input.readInt() != VERSION) {
            throw IOException("Unrecognized playback checkpoint")
        }
        val checkpoint = PlaybackPositionCheckpoint(input.readLong(), input.readInt(), input.readLong())
        val actualChecksum = checksum.value
        if (actualChecksum != input.readLong()) throw IOException("Playback checkpoint checksum mismatch")
        return checkpoint
    }
}
