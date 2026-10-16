package com.melox.player.data.library

import com.melox.player.model.MusicTrack
import java.net.URI
import java.util.Locale

internal const val INTERNAL_STORAGE_ROOT = "/storage/emulated/0"

internal data class MusicDirectory(
    val key: String,
    val path: String,
    val name: String?,
    val tracks: List<MusicTrack>,
    val children: List<String>,
    val songCount: Int,
) {
    fun asFolderGroup(nameOverride: String? = name): FolderGroup = FolderGroup(
        key = key,
        name = nameOverride,
        path = path,
        displayPath = folderDisplayPath(path),
        tracks = tracks,
    )
}

internal data class InternalStorageHierarchy(val directories: Map<String, MusicDirectory>) {
    fun closestDirectoryKey(key: String): String = internalStorageDirectoryTrail(key)
        .lastOrNull { it in directories } ?: INTERNAL_STORAGE_ROOT

    fun childDirectories(key: String): List<MusicDirectory> =
        directories[key]?.children.orEmpty().mapNotNull(directories::get)

}

internal fun internalStorageDirectoryTrail(key: String): List<String> {
    val normalized = key.trimEnd('/').lowercase(Locale.ROOT)
    if (normalized != INTERNAL_STORAGE_ROOT && !normalized.startsWith("$INTERNAL_STORAGE_ROOT/")) {
        return listOf(INTERNAL_STORAGE_ROOT)
    }
    var path = INTERNAL_STORAGE_ROOT
    return buildList {
        add(path)
        normalized.removePrefix(INTERNAL_STORAGE_ROOT).split('/').filter(String::isNotEmpty)
            .forEach { segment ->
                path += "/$segment"
                add(path)
            }
    }
}

internal fun buildInternalStorageHierarchy(tracks: List<MusicTrack>): InternalStorageHierarchy {
    data class DirectoryBuilder(
        val path: String,
        val tracks: MutableList<MusicTrack> = mutableListOf(),
        val children: MutableSet<String> = linkedSetOf(),
        var songCount: Int = 0,
    )
    val builders = linkedMapOf(INTERNAL_STORAGE_ROOT to DirectoryBuilder(INTERNAL_STORAGE_ROOT))
    val folderPaths = HashMap<Pair<String?, String>, String?>()
    tracks.forEach { track ->
        val identity = if (track.folderPath == null) null to track.contentUri else musicFolderIdentity(track)
        val path = folderPaths.getOrPut(identity) { internalStorageFolderPath(track) }
            ?: return@forEach
        var parentKey = INTERNAL_STORAGE_ROOT
        builders.getValue(parentKey).songCount += 1
        var currentPath = INTERNAL_STORAGE_ROOT
        path.removePrefix(INTERNAL_STORAGE_ROOT).split('/').filter(String::isNotEmpty).forEach { segment ->
            currentPath += "/$segment"
            val key = currentPath.lowercase(Locale.ROOT)
            builders.getValue(parentKey).children += key
            builders.getOrPut(key) { DirectoryBuilder(currentPath) }.songCount += 1
            parentKey = key
        }
        builders.getValue(parentKey).tracks += track
    }
    return InternalStorageHierarchy(builders.mapValues { (key, directory) ->
        MusicDirectory(
            key = key,
            path = directory.path,
            name = directory.path.substringAfterLast('/').takeUnless { key == INTERNAL_STORAGE_ROOT },
            tracks = directory.tracks.toList(),
            children = directory.children.sortedWith(
                compareBy<String> { createMusicSortKeys(builders.getValue(it).path.substringAfterLast('/')).value }
                    .thenBy { it },
            ),
            songCount = directory.songCount,
        )
    })
}

private fun internalStorageFolderPath(track: MusicTrack): String? {
    val resolvedPath = fullMusicFolderPath(track)
        ?: musicFolderPathFromSourceUri(track.contentUri)?.let { sourcePath ->
            fullMusicFolderPath(track.copy(folderPath = sourcePath))
        } ?: run {
        val uri = runCatching { URI(track.contentUri) }.getOrNull() ?: return null
        val segments = uri.path.orEmpty().trim('/').split('/')
        if (uri.host == "media" && segments.size >= 4 &&
            segments[0] in listOf("external_primary", "external") && segments[1] == "audio"
        ) INTERNAL_STORAGE_ROOT else return null
    }
    val normalized = normalizeMusicFolderPath(resolvedPath, includesFileName = false) ?: return null
    val root = listOf(INTERNAL_STORAGE_ROOT, "/storage/self/primary", "/mnt/sdcard", "/sdcard")
        .firstOrNull { normalized == it || normalized.startsWith("$it/") } ?: return null
    val relative = normalized.removePrefix(root)
    if (relative.split('/').any { it == "." || it == ".." }) return null
    return INTERNAL_STORAGE_ROOT + relative
}
