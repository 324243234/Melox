package com.melox.player.data.repository

import com.melox.player.data.library.hasReusableAudioProperties
import com.melox.player.model.MusicTrack
import java.io.IOException

/** A missing cursor is a failed scan, not a successful empty source. */
internal fun <T : Any> readScanSource(source: String, read: () -> T?): T =
    read() ?: throw IOException("Unable to read music source: $source")

internal fun MusicTrack.hasReusableDocumentProperties(
    contentUri: String,
    dateModifiedEpochSeconds: Long,
    fileSizeBytes: Long,
    displayName: String?,
): Boolean = fileName == displayName && dateModifiedEpochSeconds > 0L && fileSizeBytes > 0L &&
    hasReusableAudioProperties(
        id = stableDocumentTrackId(contentUri),
        contentUri = contentUri,
        dateModifiedEpochSeconds = dateModifiedEpochSeconds,
        fileSizeBytes = fileSizeBytes,
    )
