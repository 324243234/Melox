package com.melox.player.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import java.util.ArrayDeque
import java.util.Locale
import java.io.IOException

internal data class CustomFolderScope(
    val volumeName: String,
    val folderPrefix: String,
    val legacyFolderPrefix: String,
)

internal data class CustomFolderAudioDocument(
    val uri: Uri,
    val volumeName: String?,
    val documentId: String,
    val displayName: String,
    val mimeType: String?,
    val folderPath: String?,
    val fileSizeBytes: Long,
    val dateModifiedEpochSeconds: Long,
    val scannerPath: String?,
)

internal class CustomFolderAudioDiscovery(
    private val contentResolver: ContentResolver,
) {
    fun enumerate(treeUriString: String): List<CustomFolderAudioDocument> {
        val treeUri = runCatching { Uri.parse(treeUriString) }
            .getOrNull()
            ?.takeIf { uri -> DocumentsContract.isTreeUri(uri) }
            ?: throw IOException("Invalid music folder: $treeUriString")
        val rootDocumentId = runCatching {
            DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrThrow()
        val pendingDirectories = ArrayDeque<String>().apply { add(rootDocumentId) }
        val seenDocumentIds = HashSet<String>()
        val documents = mutableListOf<CustomFolderAudioDocument>()

        while (pendingDirectories.isNotEmpty()) {
            val parentDocumentId = pendingDirectories.removeFirst()
            if (!seenDocumentIds.add(parentDocumentId)) continue
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                parentDocumentId,
            )
            readScanSource(parentDocumentId) {
                contentResolver.query(
                    childrenUri,
                    DOCUMENT_PROJECTION,
                    null,
                    null,
                    null,
                )
            }.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                )
                val nameColumn = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                )
                val mimeColumn = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                )
                val sizeColumn = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_SIZE,
                )
                val modifiedColumn = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                )
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(idColumn) ?: continue
                    val displayName = cursor.getString(nameColumn)?.trim().orEmpty()
                    val mimeType = cursor.getString(mimeColumn)
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pendingDirectories.add(documentId)
                        continue
                    }
                    if (!isSupportedAudioDocument(displayName, mimeType)) continue
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        documentId,
                    )
                    val location = documentLocationForId(documentId, Build.VERSION.SDK_INT)
                    documents += CustomFolderAudioDocument(
                        uri = documentUri,
                        volumeName = location?.volumeName,
                        documentId = documentId,
                        displayName = displayName,
                        mimeType = mimeType,
                        folderPath = location?.folderPath,
                        fileSizeBytes = sizeColumn
                            .takeIf { it >= 0 }
                            ?.let(cursor::getLong)
                            ?.coerceAtLeast(0L)
                            ?: 0L,
                        dateModifiedEpochSeconds = modifiedColumn
                            .takeIf { it >= 0 }
                            ?.let(cursor::getLong)
                            ?.coerceAtLeast(0L)
                            ?.div(MILLIS_PER_SECOND)
                            ?: 0L,
                        scannerPath = location?.scannerPath?.takeIf {
                            treeUri.authority == EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY
                        },
                    )
                }
            }
        }
        return documents
    }

    private companion object {
        private const val MILLIS_PER_SECOND = 1_000L
        private const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY =
            "com.android.externalstorage.documents"
        private val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

private data class DocumentLocation(
    val volumeName: String,
    val folderPath: String,
    val scannerPath: String,
)

internal fun customFolderScopeForDocumentId(
    documentId: String,
    sdkInt: Int,
): CustomFolderScope? {
    val separator = documentId.indexOf(':')
    if (separator <= 0) return null
    val documentVolume = documentId.substring(0, separator)
    val relativePath = documentId.substring(separator + 1).trim('/')
    val volumeName = mediaStoreVolumeName(documentVolume, sdkInt)
    val relativePrefix = "/$relativePath".trimEnd('/').ifEmpty { "/" }
    val legacyRoot = if (documentVolume.equals("primary", ignoreCase = true)) {
        "/storage/emulated/0"
    } else {
        "/storage/$documentVolume"
    }
    return CustomFolderScope(
        volumeName = volumeName,
        folderPrefix = relativePrefix,
        legacyFolderPrefix = "$legacyRoot/$relativePath".trimEnd('/'),
    )
}

internal fun customFolderScopeMatches(
    scope: CustomFolderScope,
    volumeName: String,
    rawPath: String?,
    includesFileName: Boolean,
    sdkInt: Int,
): Boolean {
    if (sdkInt >= Build.VERSION_CODES.Q &&
        !scope.volumeName.equals(volumeName, ignoreCase = true)
    ) return false
    return folderMatchesPrefix(
        rawPath = rawPath,
        includesFileName = includesFileName,
        prefix = if (sdkInt >= Build.VERSION_CODES.Q) {
            scope.folderPrefix
        } else {
            scope.legacyFolderPrefix
        },
    )
}

internal fun isSupportedAudioDocument(
    displayName: String,
    mimeType: String?,
): Boolean {
    if (mimeType?.startsWith("audio/", ignoreCase = true) == true) return true
    return displayName
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT) in AUDIO_EXTENSIONS
}

internal fun stableDocumentTrackId(uriString: String): Long {
    var hash = FNV_OFFSET_BASIS
    uriString.toByteArray(Charsets.UTF_8).forEach { byte ->
        hash = hash xor (byte.toLong() and 0xffL)
        hash *= FNV_PRIME
    }
    return hash or Long.MIN_VALUE
}

private fun documentLocationForId(documentId: String, sdkInt: Int): DocumentLocation? {
    val separator = documentId.indexOf(':')
    if (separator <= 0) return null
    val documentVolume = documentId.substring(0, separator)
    val relativeFilePath = documentId.substring(separator + 1).trim('/')
    if (relativeFilePath.isEmpty()) return null
    val relativeFolderPath = relativeFilePath.substringBeforeLast('/', missingDelimiterValue = "")
    val storageRoot = if (documentVolume.equals("primary", ignoreCase = true)) {
        "/storage/emulated/0"
    } else {
        "/storage/$documentVolume"
    }
    return DocumentLocation(
        volumeName = mediaStoreVolumeName(documentVolume, sdkInt),
        folderPath = if (sdkInt >= Build.VERSION_CODES.Q) {
            "/$relativeFolderPath".trimEnd('/').ifEmpty { "/" }
        } else {
            "$storageRoot/$relativeFolderPath".trimEnd('/')
        },
        scannerPath = "$storageRoot/$relativeFilePath",
    )
}

private fun mediaStoreVolumeName(documentVolume: String, sdkInt: Int): String =
    if (sdkInt >= Build.VERSION_CODES.Q && documentVolume.equals("primary", ignoreCase = true)) {
        MediaStoreVolumeExternalPrimary
    } else if (sdkInt >= Build.VERSION_CODES.Q) {
        documentVolume
    } else {
        MediaStoreVolumeExternal
    }

private const val MediaStoreVolumeExternalPrimary = "external_primary"
private const val MediaStoreVolumeExternal = "external"
private const val FNV_OFFSET_BASIS = -3750763034362895579L
private const val FNV_PRIME = 1099511628211L
private val AUDIO_EXTENSIONS = setOf(
    "aac",
    "aif",
    "aiff",
    "alac",
    "ape",
    "flac",
    "m4a",
    "mp3",
    "mp4",
    "oga",
    "ogg",
    "opus",
    "wav",
    "wave",
    "wma",
)
