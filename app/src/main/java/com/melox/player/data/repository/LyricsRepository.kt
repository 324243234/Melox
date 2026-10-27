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
import com.melox.player.model.LyricsFormatPriority
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
    val formatPriority: LyricsFormatPriority =
        LyricsFormatPriority.LRC,
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

    private fun readEmbedded(request: LyricsRequest): LyricsDocument? {
        val properties = readTagLibLyrics(request.contentUri)
        return parseEmbeddedLyrics(
            candidates = embeddedLyricsCandidates(properties),
            durationMs = request.durationMs,
            translations = embeddedLyricsCandidates(properties, translation = true),
            formatPriority = request.formatPriority,
        )
    }

    private fun readSidecar(request: LyricsRequest): LyricsDocument? {
        val candidates = exactLyricsSidecarCandidates(
            audioFileName = request.fileName,
            formatPriority = request.formatPriority,
        )
        if (candidates.isEmpty()) return null
        val candidateNames = candidates.flatMap { (name, _) ->
            listOf(name) + translationSidecarCandidates(name)
        }.distinct()
        val indexedUris by lazy { querySidecarUris(request, candidateNames) }
        var readFailure: Exception? = null
        val document = readFirstSidecar(candidates, request.durationMs, request.formatPriority) { name ->
            val directText = try {
                readDirectSidecar(request.folderPath, name)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                readFailure = exception
                null
            }
            directText ?: try {
                indexedUris[name]?.let(::readText)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                readFailure = exception
                null
            }
        }
        if (document != null) return document
        readFailure?.let { throw it }
        return null
    }

    private fun querySidecarUris(request: LyricsRequest, candidateNames: List<String>): Map<String, Uri> {
        val relativePath = request.folderPath.toRelativeMediaStorePath()
        val collection = request.contentUri.toMediaStoreFilesCollection()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            relativePath == null || collection == null
        ) {
            return emptyMap()
        }
        val cursor = contentResolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} IN (${candidateNames.joinToString { "?" }})",
            (listOf(relativePath) + candidateNames).toTypedArray(),
            null,
        ) ?: throw IOException("Unable to query lyric sidecars")
        return cursor.use {
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

    private fun readTagLibLyrics(contentUri: String): Map<String, Array<String>> {
        val descriptor = contentResolver.openFileDescriptor(Uri.parse(contentUri), "r")
            ?: throw IOException("Unable to open embedded lyrics")
        return descriptor.use {
            val metadata = TagLib.getMetadata(descriptor.dup().detachFd(), false)
                ?: throw IOException("Unable to read embedded lyrics")
            metadata.propertyMap.orEmpty()
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

internal fun embeddedLyricsCandidates(
    properties: Map<String, Array<String>>,
    translation: Boolean = false,
): List<String> =
    properties.entries.asSequence()
        .filter { (key, _) ->
            val normalized = key.normalizedLyricsKey()
            (normalized in LYRICS_KEYS || normalized.contains("LYRIC")) &&
                normalized.contains("TRANSLAT") == translation
        }
        .sortedWith(compareBy<Map.Entry<String, Array<String>>> { (key, _) ->
            val normalized = key.normalizedLyricsKey()
            when {
                normalized.contains("TTML") -> 0
                normalized in LYRICS_KEYS -> 1
                else -> 2
            }
        }.thenBy { (key, _) -> key.normalizedLyricsKey() })
        .flatMap { (_, values) -> values.asSequence() }
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot { it.contains('�') }
        .map { it.take(MAX_EMBEDDED_CHARS) }
        .distinct()
        .toList()

internal fun parseEmbeddedLyrics(
    candidates: List<String>,
    durationMs: Long,
    translations: List<String> = emptyList(),
    formatPriority: LyricsFormatPriority = LyricsFormatPriority.LRC,
): LyricsDocument? =
    preferredLyricsDocument(formatPriority, candidates.asSequence().mapNotNull { text ->
        LyricsParser.parse(text, LyricsSource.EMBEDDED, durationMs = durationMs)
    })?.withLrcTranslation(translations.asSequence(), durationMs)

/** Inspect parsed content rather than trusting a tag name or file extension. */
private fun preferredLyricsDocument(
    priority: LyricsFormatPriority,
    documents: Sequence<LyricsDocument>,
): LyricsDocument? {
    var fallback: LyricsDocument? = null
    for (document in documents) {
        if (document.format == priority.format) return document
        if (fallback == null) fallback = document
    }
    return fallback
}

internal fun exactLyricsSidecarCandidates(
    audioFileName: String?,
    formatPriority: LyricsFormatPriority = LyricsFormatPriority.LRC,
): List<Pair<String, LyricsFormat>> {
    val fileName = audioFileName ?: return emptyList()
    if (fileName.any { it == '/' || it == '\\' }) return emptyList()
    val stem = fileName.substringBeforeLast('.', fileName).takeIf(String::isNotBlank)
        ?: return emptyList()
    return when (formatPriority) {
        LyricsFormatPriority.LRC -> listOf(
            "$stem.lrc" to LyricsFormat.LRC,
            "$stem.ttml" to LyricsFormat.TTML,
        )
        LyricsFormatPriority.TTML -> listOf(
            "$stem.ttml" to LyricsFormat.TTML,
            "$stem.lrc" to LyricsFormat.LRC,
        )
    }
}

internal fun lyricsSourceOrder(priority: LyricsSourcePriority): List<LyricsSource> = when (priority) {
    LyricsSourcePriority.EMBEDDED -> listOf(LyricsSource.EMBEDDED, LyricsSource.SIDECAR)
    LyricsSourcePriority.SIDECAR -> listOf(LyricsSource.SIDECAR, LyricsSource.EMBEDDED)
}

private fun translationSidecarCandidates(fileName: String): List<String> {
    val stem = fileName.substringBeforeLast('.')
    return listOf("${stem}_trans.lrc", "$stem.trans.lrc", "$stem.zh.lrc")
}

/** Select by actual format before reading the chosen document's optional LRC translation. */
internal fun readFirstSidecar(
    candidates: List<Pair<String, LyricsFormat>>,
    durationMs: Long,
    formatPriority: LyricsFormatPriority = if (candidates.firstOrNull()?.second == LyricsFormat.TTML)
        LyricsFormatPriority.TTML else LyricsFormatPriority.LRC,
    read: (String) -> String?,
): LyricsDocument? {
    var readFailure: Exception? = null
    var selectedName: String? = null
    val document = preferredLyricsDocument(formatPriority, candidates.asSequence().mapNotNull { (name, _) ->
        try {
            read(name)?.let { text ->
                LyricsParser.parse(text, LyricsSource.SIDECAR, durationMs = durationMs)
                    ?.also { if (selectedName == null || it.format == formatPriority.format) selectedName = name }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            readFailure = exception
            null
        }
    })
    if (document == null) {
        readFailure?.let { throw it }
        return null
    }
    val translations = if (document.format == LyricsFormat.LRC) {
        translationSidecarCandidates(requireNotNull(selectedName)).asSequence().mapNotNull { translationName ->
            try {
                read(translationName)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        }
    } else emptySequence()
    return document.withLrcTranslation(translations, durationMs)
}

private fun LyricsDocument.withLrcTranslation(texts: Sequence<String>, durationMs: Long): LyricsDocument {
    if (format != LyricsFormat.LRC || lines.none { it.displayText.isNotBlank() && it.translation.isNullOrBlank() }) {
        return this
    }
    for (text in texts) {
        val translation = LyricsParser.parse(text, source, LyricsFormat.LRC, durationMs)
            ?.takeIf { it.format == LyricsFormat.LRC } ?: continue
        val byTime = translation.lines.associate { it.startTimeMs to it.displayText.trim() }
        val translatedLines = lines.map { line ->
            val value = byTime[line.startTimeMs]
                ?.takeIf { it.isNotBlank() && it != line.displayText.trim() }
            if (line.displayText.isNotBlank() && line.translation.isNullOrBlank() && value != null) {
                line.copy(translation = value)
            } else {
                line
            }
        }
        if (translatedLines != lines) return copy(lines = translatedLines)
    }
    return this
}
