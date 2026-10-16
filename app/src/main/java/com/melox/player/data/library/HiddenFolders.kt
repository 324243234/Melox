package com.melox.player.data.library

import com.melox.player.model.MusicTrack
import java.util.Locale

internal fun normalizeHiddenFolderPath(path: String): String =
    path.trim().replace('\\', '/').trimEnd('/').ifEmpty { "/" }

internal fun normalizedHiddenFolderPaths(paths: List<String>): List<String> =
    paths.map(::normalizeHiddenFolderPath).distinctBy { it.lowercase(Locale.ROOT) }

internal fun isMusicFolderHidden(path: String?, hiddenPaths: List<String>): Boolean {
    val normalized = path?.let(::normalizeHiddenFolderPath) ?: return false
    return hiddenPaths.any { prefix ->
        prefix == "/" || normalized.equals(prefix, ignoreCase = true) ||
            normalized.startsWith("$prefix/", ignoreCase = true)
    }
}

internal fun visibleMusicTracks(
    tracks: List<MusicTrack>,
    hiddenPaths: List<String>,
    hiddenTrackUris: Set<String> = emptySet(),
): List<MusicTrack> {
    if (hiddenPaths.isEmpty() && hiddenTrackUris.isEmpty()) return tracks
    val prefixes = normalizedHiddenFolderPaths(hiddenPaths)
    val hiddenByFolder = HashMap<Pair<String?, String>, Boolean>()
    return tracks.filterNot { track ->
        track.contentUri in hiddenTrackUris || hiddenByFolder.getOrPut(musicFolderIdentity(track)) {
            isMusicFolderHidden(fullMusicFolderPath(track), prefixes) ||
                isMusicFolderHidden(track.folderPath, prefixes)
        }
    }
}

internal fun hiddenFolderDisplayPaths(
    tracks: List<MusicTrack>,
    paths: List<String>,
): Map<String, String> {
    if (paths.isEmpty()) return emptyMap()
    val folders = tracks.distinctBy(::musicFolderIdentity)
    return paths.associateWith { path ->
        if (path.startsWith("/storage/") || path.startsWith("/mnt/") ||
            path == "/sdcard" || path.startsWith("/sdcard/")
        ) {
            path
        } else {
            folders.mapNotNull { track ->
                val relativePath = track.folderPath ?: return@mapNotNull null
                if (!isMusicFolderHidden(relativePath, listOf(path))) return@mapNotNull null
                val fullPath = fullMusicFolderPath(track) ?: return@mapNotNull null
                if (fullPath == relativePath) return@mapNotNull null
                val root = fullPath.removeSuffix(relativePath.trimEnd('/'))
                root + path.trimEnd('/').takeUnless { it == "/" }.orEmpty()
            }.distinct().sorted().joinToString("\n").ifEmpty {
                "/storage/emulated/0" + path.trimEnd('/').takeUnless { it == "/" }.orEmpty()
            }
        }
    }
}

/** Resolve folders encoded in document/file URIs without opening the source. */
internal fun musicFolderPathFromSourceUri(contentUri: String): String? {
    val uri = runCatching { java.net.URI(contentUri) }.getOrNull() ?: return null
    if (uri.scheme == "file") {
        val parent = normalizeMusicFolderPath(uri.path, includesFileName = true) ?: return null
        val primaryRoot = listOf("/sdcard", "/storage/self/primary", "/mnt/sdcard")
            .firstOrNull { parent == it || parent.startsWith("$it/") }
        return if (primaryRoot == null) parent else "/storage/emulated/0" + parent.removePrefix(primaryRoot)
    }
    if (uri.host != "com.android.externalstorage.documents") return null
    val documentId = uri.path.orEmpty().substringAfterLast("/document/", "")
    if (':' !in documentId) return null
    val path = documentId.substringAfter(':')
    return normalizeMusicFolderPath(path, includesFileName = true) ?: "/"
}
