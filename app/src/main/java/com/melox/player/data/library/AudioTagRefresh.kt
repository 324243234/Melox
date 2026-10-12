package com.melox.player.data.library

import com.melox.player.model.MusicTrack

/** Updates editable tags without changing the audio stream or its identity. */
internal fun MusicTrack.withRefreshedTags(tags: LocalAudioTags): MusicTrack {
    val sortKeys = createMusicSortKeys(tags.title)
    return copy(
        title = tags.title,
        artist = tags.artist,
        album = tags.album,
        albumArtist = tags.albumArtist,
        year = tags.year,
        trackNumber = tags.trackNumber,
        discNumber = tags.discNumber,
        titleSectionKey = sortKeys.section,
        titleSortKey = sortKeys.value,
    )
}
