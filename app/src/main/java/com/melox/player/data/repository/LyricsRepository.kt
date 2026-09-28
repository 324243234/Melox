package com.melox.player.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.amr.AmrExtractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.CommentFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.inspector.MetadataRetriever
import com.melox.player.data.lyrics.LyricsParser
import com.melox.player.model.LyricsDocument
import com.melox.player.model.LyricsFormat
import com.melox.player.model.LyricsSidecarFormatPriority
import com.melox.player.model.LyricsSource
import com.melox.player.model.LyricsSourcePriority
import com.melox.player.playback.PlaybackExtractorsFactory
import com.kyant.taglib.TagLib
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

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
    private val applicationContext = context.applicationContext
    private val contentResolver = applicationContext.contentResolver

    suspend fun load(request: LyricsRequest): LyricsDocument? {
        lyricsSourceOrder(request.sourcePriority).forEach { source ->
            val document = when (source) {
                LyricsSource.EMBEDDED -> readEmbedded(request)
                LyricsSource.SIDECAR -> readSidecar(request)
            }
            if (document != null) return document
        }
        return null
    }

    private fun readEmbedded(request: LyricsRequest): LyricsDocument? =
        readEmbeddedCandidates(request.contentUri)
            .firstNotNullOfOrNull { candidate ->
                LyricsParser.parse(
                    raw = candidate,
                    source = LyricsSource.EMBEDDED,
                    durationMs = request.durationMs,
                )
            }

    private fun readSidecar(request: LyricsRequest): LyricsDocument? {
        val candidates = exactLyricsSidecarCandidates(
            audioFileName = request.fileName,
            formatPriority = request.sidecarFormatPriority,
        )
        if (candidates.isEmpty()) return null
        val directCandidates = readDirectSidecars(request.folderPath, candidates)
        directCandidates.firstNotNullOfOrNull { (text, format) ->
            LyricsParser.parse(
                raw = text,
                source = LyricsSource.SIDECAR,
                preferredFormat = format,
                durationMs = request.durationMs,
            )
        }?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val relativePath = request.folderPath.toRelativeMediaStorePath() ?: return null
        val collection = request.contentUri.toMediaStoreFilesCollection() ?: return null
        val candidateNames = candidates.mapTo(mutableSetOf()) { it.first }
        val textByName = runCatching {
            contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                ),
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?",
                arrayOf(relativePath),
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val result = mutableMapOf<String, String>()
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameColumn).orEmpty()
                    if (displayName !in candidateNames || displayName in result) continue
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                    readText(uri)?.let { result[displayName] = it }
                }
                result
            }
        }.getOrNull().orEmpty()
        return candidates.firstNotNullOfOrNull { (name, format) ->
            val text = textByName[name] ?: return@firstNotNullOfOrNull null
            LyricsParser.parse(
                raw = text,
                source = LyricsSource.SIDECAR,
                preferredFormat = format,
                durationMs = request.durationMs,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun readDirectSidecars(
        folderPath: String?,
        candidates: List<Pair<String, LyricsFormat>>,
    ): List<Pair<String, LyricsFormat>> {
        val path = folderPath ?: return emptyList()
        val directory = when {
            path.startsWith("/storage/") ||
                path.startsWith("/sdcard/") ||
                path.startsWith("/mnt/") -> File(path)
            else -> File(Environment.getExternalStorageDirectory(), path.trimStart('/'))
        }
        val canonicalDirectory = runCatching { directory.canonicalFile }.getOrNull()
            ?: return emptyList()
        return candidates.mapNotNull { (name, format) ->
            val file = runCatching { File(canonicalDirectory, name).canonicalFile }.getOrNull()
                ?.takeIf { it.parentFile == canonicalDirectory }
                ?: return@mapNotNull null
            val text = runCatching {
                if (file.isFile && file.length() in 1..MAX_SIDECAR_BYTES) {
                    file.inputStream().use(::readBounded)
                } else {
                    null
                }
            }.getOrNull()
            text?.let { it to format }
        }
    }

    private fun readText(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.use(::readBounded)
    }.getOrNull()

    @OptIn(UnstableApi::class)
    @Suppress("DEPRECATION")
    private fun readEmbeddedCandidates(contentUri: String): List<String> {
        if (contentUri.isBlank()) return emptyList()
        val groups = runCatching {
            MetadataRetriever.Builder(
                applicationContext,
                MediaItem.fromUri(contentUri),
            ).setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    applicationContext,
                    PlaybackExtractorsFactory(
                        DefaultExtractorsFactory()
                            .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
                            .setAmrExtractorFlags(AmrExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
                            .setMp4ExtractorFlags(
                                Mp4Extractor.FLAG_READ_SEF_DATA or
                                    Mp4Extractor.FLAG_OMIT_TRACK_SAMPLE_TABLE,
                            ),
                    ),
                ),
            ).build().use { retriever ->
                retriever.retrieveTrackGroups().get(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }.getOrNull()
        if (groups == null) return readTagLibLyrics(contentUri)
        val metadataCandidates = buildList {
            for (groupIndex in 0 until groups.length) {
                val group = groups[groupIndex]
                for (formatIndex in 0 until group.length) {
                    val metadata = group.getFormat(formatIndex).metadata ?: continue
                    for (entryIndex in 0 until metadata.length()) {
                        extractLyricsText(metadata[entryIndex])?.let { text ->
                            if (text.isNotBlank() && none { it == text }) {
                                add(text.take(MAX_EMBEDDED_CHARS))
                            }
                        }
                    }
                }
            }
        }
        if (metadataCandidates.isNotEmpty()) return metadataCandidates
        return readTagLibLyrics(contentUri)
    }

    private fun readTagLibLyrics(contentUri: String): List<String> = runCatching {
        contentResolver.openFileDescriptor(Uri.parse(contentUri), "r")?.use { descriptor ->
            val properties = TagLib.getMetadata(descriptor.dup().detachFd(), false)?.propertyMap.orEmpty()
            properties.entries.asSequence()
                .filter { (key, _) -> key.isLyricsKey() }
                .flatMap { (_, values) -> values.asSequence() }
                .map(String::trim)
                .filter(String::isNotBlank)
                .filterNot { it.contains('�') }
                .map { it.take(MAX_EMBEDDED_CHARS) }
                .distinct()
                .toList()
        }.orEmpty()
    }.getOrDefault(emptyList())

    @OptIn(UnstableApi::class)
    private fun extractLyricsText(entry: Any): String? = when (entry) {
        is TextInformationFrame -> when {
            entry.id.equals("USLT", ignoreCase = true) -> entry.values.joinToString("\n")
            entry.id.equals("TXXX", ignoreCase = true) &&
                entry.description.isLyricsKey() -> entry.values.joinToString("\n")
            else -> null
        }
        is VorbisComment -> entry.value.takeIf { entry.key.isLyricsKey() }
        is BinaryFrame -> entry.data.takeIf {
            entry.id.equals("USLT", ignoreCase = true)
        }?.let(::decodeUsltFrame)
        is CommentFrame -> entry.text.takeIf { entry.description.isLyricsKey() }
        is InternalFrame -> entry.text.takeIf { entry.description.isLyricsKey() }
        else -> null
    }

    private fun decodeUsltFrame(data: ByteArray): String? {
        if (data.size < 5 || data.size > MAX_EMBEDDED_CHARS) return null
        val encoding = data[0].toInt() and 0xFF
        val charset = when (encoding) {
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> StandardCharsets.ISO_8859_1
        }
        val delimiterLength = if (encoding == 1 || encoding == 2) 2 else 1
        var delimiterStart = 4
        while (delimiterStart + delimiterLength <= data.size) {
            val isDelimiter = if (delimiterLength == 1) {
                data[delimiterStart].toInt() == 0
            } else {
                data[delimiterStart].toInt() == 0 && data[delimiterStart + 1].toInt() == 0
            }
            if (isDelimiter) break
            delimiterStart += delimiterLength
        }
        val textStart = (delimiterStart + delimiterLength).coerceAtMost(data.size)
        if (textStart >= data.size) return null
        return String(data, textStart, data.size - textStart, charset)
            .trim('\u0000', '\uFEFF', ' ')
            .takeIf(String::isNotBlank)
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

    private fun String?.isLyricsKey(): Boolean {
        val normalized = this?.uppercase(Locale.ROOT).orEmpty()
            .substringAfterLast(':')
            .replace(" ", "")
            .replace("_", "")
        return normalized in LYRICS_KEYS || normalized.contains("LYRIC")
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
        private const val MAX_EMBEDDED_CHARS = 2 * 1024 * 1024
        private const val METADATA_TIMEOUT_SECONDS = 5L
        private val LYRICS_KEYS = setOf(
            "LYRICS",
            "SYNCEDLYRICS",
            "UNSYNCEDLYRICS",
            "USLT",
            "LYRIC",
            "LYRICSENG",
            "UNSYNCEDLYRICS",
            "ITXT",
            "ITEXT",
            "LYRICIST",
        )
        private val SHARED_STORAGE_ROOTS = listOf(
            "/storage/emulated/0",
            "/storage/self/primary",
            "/mnt/sdcard",
            "/sdcard",
        )
    }
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
