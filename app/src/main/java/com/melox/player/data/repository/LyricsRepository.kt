package com.melox.player.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.melox.player.data.lyrics.LyricsParser
import com.melox.player.model.LyricsDocument
import com.melox.player.model.LyricsFormat
import com.melox.player.model.LyricsSidecarFormatPriority
import com.melox.player.model.LyricsSource
import com.melox.player.model.LyricsSourcePriority
import com.kyant.taglib.TagLib
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

data class LyricsRequest(
    val mediaId: String,
    val contentUri: String,
    val fileName: String?,
    val folderPath: String?,
    val durationMs: Long,
    val refreshRevision: Long = 0L,
    val sourcePriority: LyricsSourcePriority = LyricsSourcePriority.EMBEDDED,
    val sidecarFormatPriority: LyricsSidecarFormatPriority =
        LyricsSidecarFormatPriority.LRC,
)

/** Reads timestamped lyrics from local audio metadata and sidecars. */
class LyricsRepository(context: Context) {
    private val contentResolver = context.applicationContext.contentResolver

    suspend fun load(request: LyricsRequest): LyricsDocument? {
        var readFailure: Exception? = null
        val failedSources = mutableSetOf<LyricsSource>()
        lyricsSourceOrder(request.sourcePriority).forEach { source ->
            try {
                val document = when (source) {
                    LyricsSource.EMBEDDED -> readEmbedded(request)
                    LyricsSource.SIDECAR -> readSidecar(request)
                }
                if (document != null) return document
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                readFailure = exception
                failedSources += source
            }
        }
        readFailure?.let { throw LyricsReadException(failedSources, it) }
        return null
    }

    private fun readEmbedded(request: LyricsRequest): LyricsDocument? = parseEmbeddedLyrics(
        candidates = readTagLibLyrics(request.contentUri),
        durationMs = request.durationMs,
    )

    private fun readSidecar(request: LyricsRequest): LyricsDocument? {
        val candidates = exactLyricsSidecarCandidates(
            audioFileName = request.fileName,
            formatPriority = request.sidecarFormatPriority,
        )
        if (candidates.isEmpty()) return null
        var directReadFailure: Exception? = null
        val directDocument = readFirstSidecar(candidates, request.durationMs) { name ->
            try {
                readDirectSidecar(request.folderPath, name)
            } catch (exception: Exception) {
                directReadFailure = exception
                null
            }
        }
        if (directDocument != null) return directDocument
        val relativePath = request.folderPath.toRelativeMediaStorePath()
        val collection = request.contentUri.toMediaStoreFilesCollection()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            relativePath == null || collection == null
        ) {
            directReadFailure?.let { throw it }
            return null
        }
        val candidateNames = candidates.map { it.first }
        val cursor = contentResolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} IN (${candidateNames.joinToString { "?" }})",
            (listOf(relativePath) + candidateNames).toTypedArray(),
            null,
        ) ?: throw IOException("Unable to query lyric sidecars")
        val uriByName = cursor.use {
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            buildMap {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn).orEmpty()
                    if (name in candidateNames && name !in this) {
                        put(name, ContentUris.withAppendedId(collection, cursor.getLong(idColumn)))
                    }
                }
            }
        }
        var indexedReadFailure: Exception? = null
        val indexedDocument = readFirstSidecar(candidates, request.durationMs) { name ->
            uriByName[name]?.let { uri ->
                try {
                    readText(uri)
                } catch (exception: Exception) {
                    indexedReadFailure = exception
                    null
                }
            }
        }
        if (indexedDocument != null) return indexedDocument
        (indexedReadFailure ?: directReadFailure)?.let { throw it }
        return null
    }

    @Suppress("DEPRECATION")
    private fun readDirectSidecar(folderPath: String?, name: String): String? {
        val path = folderPath ?: return null
        val directory = when {
            path.startsWith("/storage/") || path.startsWith("/sdcard/") ||
                path.startsWith("/mnt/") -> File(path)
            else -> File(Environment.getExternalStorageDirectory(), path.trimStart('/'))
        }.canonicalFile
        val file = File(directory, name).canonicalFile
        if (file.parentFile != directory || !file.isFile ||
            file.length() !in 1..MAX_SIDECAR_BYTES
        ) return null
        return file.inputStream().use(::readBounded)
    }

    private fun readText(uri: Uri): String? =
        (contentResolver.openInputStream(uri) ?: throw IOException("Unable to open lyric sidecar"))
            .use(::readBounded)

    private fun readTagLibLyrics(contentUri: String): List<String> {
        val descriptor = contentResolver.openFileDescriptor(Uri.parse(contentUri), "r")
            ?: throw IOException("Unable to open embedded lyrics")
        return descriptor.use {
            val metadata = TagLib.getMetadata(descriptor.dup().detachFd(), false)
                ?: throw IOException("Unable to read embedded lyrics")
            embeddedLyricsCandidates(metadata.propertyMap.orEmpty())
        }
    }

    private fun readBounded(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_SIDECAR_BYTES) return null
            output.write(buffer, 0, read)
        }
        return decodeText(output.toByteArray()).takeIf(String::isNotBlank)
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte()))) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, charset("GB18030"))
        }
    }

    private fun String?.toRelativeMediaStorePath(): String? {
        var path = this?.replace('\\', '/')?.trim().orEmpty()
        if (path.isEmpty()) return null
        SHARED_STORAGE_ROOTS.firstOrNull { root ->
            path.equals(root, ignoreCase = true) || path.startsWith("$root/", ignoreCase = true)
        }?.let { root ->
            path = path.substring(root.length)
        }
        return path.trim('/').takeIf(String::isNotEmpty)?.plus("/")
    }

    private fun String.toMediaStoreFilesCollection(): Uri? {
        val audioUri = runCatching { Uri.parse(this) }.getOrNull() ?: return null
        if (audioUri.scheme != "content" || audioUri.authority != MediaStore.AUTHORITY) return null
        val volumeName = audioUri.pathSegments.firstOrNull()?.takeIf(String::isNotBlank)
            ?: return null
        return MediaStore.Files.getContentUri(volumeName)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private companion object {
        private const val MAX_SIDECAR_BYTES = 2L * 1024L * 1024L
        private val SHARED_STORAGE_ROOTS = listOf(
            "/storage/emulated/0",
            "/storage/self/primary",
            "/mnt/sdcard",
            "/sdcard",
        )
    }
}

internal class LyricsReadException(
    val failedSources: Set<LyricsSource>,
    cause: Exception,
) : IOException("Unable to read lyrics", cause)

private const val MAX_EMBEDDED_CHARS = 2 * 1024 * 1024
private val LYRICS_KEYS = setOf(
    "LYRICS", "SYNCEDLYRICS", "UNSYNCEDLYRICS", "USLT", "SYLT", "LYRIC",
    "LYRICSENG", "ITXT", "ITEXT", "LYRICIST", "TTML", "©LYR",
)

private fun String.normalizedLyricsKey(): String = uppercase(Locale.ROOT)
    .substringAfterLast(':')
    .replace(" ", "")
    .replace("_", "")

internal fun embeddedLyricsCandidates(properties: Map<String, Array<String>>): List<String> =
    properties.entries.asSequence()
        .filter { (key, _) ->
            val normalized = key.normalizedLyricsKey()
            normalized in LYRICS_KEYS || normalized.contains("LYRIC")
        }
        .sortedBy { (key, _) -> if (key.normalizedLyricsKey().contains("TTML")) 0 else 1 }
        .flatMap { (_, values) -> values.asSequence() }
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot { it.contains('�') }
        .map { it.take(MAX_EMBEDDED_CHARS) }
        .distinct()
        .toList()

internal fun parseEmbeddedLyrics(candidates: List<String>, durationMs: Long): LyricsDocument? =
    candidates.firstNotNullOfOrNull { text ->
        LyricsParser.parse(text, LyricsSource.EMBEDDED, durationMs = durationMs)
    }

internal fun exactLyricsSidecarCandidates(
    audioFileName: String?,
    formatPriority: LyricsSidecarFormatPriority = LyricsSidecarFormatPriority.LRC,
): List<Pair<String, LyricsFormat>> {
    val fileName = audioFileName ?: return emptyList()
    if (fileName.any { it == '/' || it == '\\' }) return emptyList()
    val stem = fileName.substringBeforeLast('.', fileName).takeIf(String::isNotBlank)
        ?: return emptyList()
    return when (formatPriority) {
        LyricsSidecarFormatPriority.LRC -> listOf(
            "$stem.lrc" to LyricsFormat.LRC,
            "$stem.ttml" to LyricsFormat.TTML,
        )
        LyricsSidecarFormatPriority.TTML -> listOf(
            "$stem.ttml" to LyricsFormat.TTML,
            "$stem.lrc" to LyricsFormat.LRC,
        )
    }
}

internal fun lyricsSourceOrder(priority: LyricsSourcePriority): List<LyricsSource> = when (priority) {
    LyricsSourcePriority.EMBEDDED -> listOf(LyricsSource.EMBEDDED, LyricsSource.SIDECAR)
    LyricsSourcePriority.SIDECAR -> listOf(LyricsSource.SIDECAR, LyricsSource.EMBEDDED)
}

/** Reads only as far as the first parseable sidecar in the user's format order. */
internal fun readFirstSidecar(
    candidates: List<Pair<String, LyricsFormat>>,
    durationMs: Long,
    read: (String) -> String?,
): LyricsDocument? = candidates.firstNotNullOfOrNull { (name, format) ->
    read(name)?.let { text ->
        LyricsParser.parse(text, LyricsSource.SIDECAR, format, durationMs)
    }
}
