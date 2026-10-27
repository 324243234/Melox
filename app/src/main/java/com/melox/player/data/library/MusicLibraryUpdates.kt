package com.melox.player.data.library

import com.melox.player.model.MusicTrack

/** Main-thread edits that must survive an older scan result or rollback. */
internal class MusicLibraryUpdates {
    var revision = 0L
        private set
    private var nextRequest = 0L
    private val requests = mutableMapOf<String, Long>()
    private val deletedUris = mutableSetOf<String>()
    private val tags = mutableMapOf<String, TagUpdate>()

    fun delete(contentUri: String) {
        revision += 1L
        deletedUris += contentUri
        requests.remove(contentUri)
        tags.remove(contentUri)
    }

    fun beginTagRefresh(contentUri: String): Long = (++nextRequest).also {
        requests[contentUri] = it
    }

    fun acceptTagRefresh(track: MusicTrack, request: Long): Boolean {
        if (track.contentUri in deletedUris || requests[track.contentUri] != request) return false
        requests.remove(track.contentUri)
        revision += 1L
        tags[track.contentUri] = TagUpdate(revision, track)
        return true
    }

    fun clear() {
        revision += 1L
        requests.clear()
        tags.clear()
    }

    fun reconcile(tracks: List<MusicTrack>, sinceRevision: Long): List<MusicTrack> {
        var changedTags = false
        val retained = tracks.filterNot { it.contentUri in deletedUris }.map { track ->
            val update = tags[track.contentUri]
                ?.takeIf { it.revision > sinceRevision && it.track.id == track.id }
                ?: return@map track
            val latest = update.track
            changedTags = true
            track.copy(
                title = latest.title,
                artist = latest.artist,
                album = latest.album,
                albumArtist = latest.albumArtist,
                year = latest.year,
                trackNumber = latest.trackNumber,
                discNumber = latest.discNumber,
                titleSectionKey = latest.titleSectionKey,
                titleSortKey = latest.titleSortKey,
                dateModifiedEpochSeconds = latest.dateModifiedEpochSeconds,
                fileSizeBytes = latest.fileSizeBytes,
            )
        }
        return if (changedTags) retained.sortedWith(
            compareBy<MusicTrack>(MusicTrack::titleSortKey).thenBy(MusicTrack::contentUri),
        ) else retained
    }

    private data class TagUpdate(val revision: Long, val track: MusicTrack)
}
