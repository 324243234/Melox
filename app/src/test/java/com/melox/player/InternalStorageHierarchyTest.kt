package com.melox.player

import com.melox.player.data.library.MusicSortConfig
import com.melox.player.data.library.sortMusicTracks
import com.melox.player.data.library.INTERNAL_STORAGE_ROOT
import com.melox.player.data.library.buildFolderGroups
import com.melox.player.data.library.buildInternalStorageHierarchy
import com.melox.player.data.library.internalStorageDirectoryTrail
import com.melox.player.data.library.visibleMusicTracks
import com.melox.player.model.MusicTrack
import com.melox.player.ui.component.library.offsetSectionIndices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalStorageHierarchyTest {
    @Test
    fun rootShowsImmediateFoldersAndDirectMusicWhileAncestorsKeepTheirOwnTracks() {
        val rootTrack = track(1, "/")
        val artistTrack = track(2, "/Music/Artist")
        val albumTrack = track(3, "/Music/Artist/Album")
        val hierarchy = buildInternalStorageHierarchy(listOf(rootTrack, artistTrack, albumTrack))
        val root = hierarchy.directories.getValue(INTERNAL_STORAGE_ROOT)
        assertEquals(listOf(rootTrack), root.tracks)
        assertEquals(listOf(key("/Music")), root.children)
        assertEquals(3, root.songCount)
        val music = hierarchy.directories.getValue(key("/Music"))
        assertTrue(music.tracks.isEmpty())
        assertEquals(listOf(key("/Music/Artist")), music.children)
        assertEquals(listOf(artistTrack), hierarchy.directories.getValue(key("/Music/Artist")).tracks)
        assertEquals(listOf(albumTrack), hierarchy.directories.getValue(key("/Music/Artist/Album")).tracks)
        assertEquals(3, hierarchy.directories.values.sumOf { it.tracks.size })
    }

    @Test
    fun primaryAndRemovableStorageWithSameRelativePathDoNotMix() {
        val primary = track(1, "/Music/Album")
        val removable = track(2, "/Music/Album", "content://media/ABCD-1234/audio/media/2")
        val unrelated = track(3, "/Music/Album", "content://unrelated.provider/audio/3")
        val wrongPrefix = track(4, "/storage/emulated/01/Music")
        val hierarchy = buildInternalStorageHierarchy(listOf(primary, removable, unrelated, wrongPrefix))
        assertEquals(listOf(primary), hierarchy.directories.getValue(key("/Music/Album")).tracks)
        assertEquals(1, hierarchy.directories.getValue(INTERNAL_STORAGE_ROOT).songCount)
    }

    @Test
    fun primaryAliasesAndLegacyRootMusicAreSupported() {
        val aliases = listOf("/sdcard/Music", "/mnt/sdcard/Music", "/storage/self/primary/Music")
            .mapIndexed { index, path -> track(index + 1L, path, "file://$path/${index + 1}.mp3") }
        val rootTrack = track(4, null, "content://media/external/audio/media/4")
        val hierarchy = buildInternalStorageHierarchy(aliases + rootTrack)
        assertEquals(aliases, hierarchy.directories.getValue(key("/Music")).tracks)
        assertEquals(listOf(rootTrack), hierarchy.directories.getValue(INTERNAL_STORAGE_ROOT).tracks)
    }

    @Test
    fun documentUrisKeepPrimarySongsAndExcludeTheSdCard() {
        val primary = track(1, null,
            "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FAlbum%2F1.flac")
        val rootTrack = track(2, null,
            "content://com.android.externalstorage.documents/document/primary%3A2.flac")
        val removable = track(3, null,
            "content://com.android.externalstorage.documents/document/ABCD-1234%3AMusic%2F3.flac")
        val other = track(4, null,
            "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FOther%2F4.flac")
        val hierarchy = buildInternalStorageHierarchy(listOf(primary, rootTrack, removable, other))
        assertEquals(listOf(primary), hierarchy.directories.getValue(key("/Music/Album")).tracks)
        assertEquals(listOf(rootTrack), hierarchy.directories.getValue(INTERNAL_STORAGE_ROOT).tracks)
        assertEquals(listOf(other), hierarchy.directories.getValue(key("/Music/Other")).tracks)
    }

    @Test
    fun leafIdentityMatchesExistingPerFolderSongSortKeys() {
        val song = track(1, "/Music/Artist/Album")
        val flat = buildFolderGroups(listOf(song)).single()
        val hierarchy = buildInternalStorageHierarchy(listOf(song))
        val directory = hierarchy.directories.getValue(flat.key)
        assertEquals(flat, directory.asFolderGroup())
        assertEquals("Music", hierarchy.directories.getValue(key("/Music")).name)
    }

    @Test
    fun hidingAndDeletingPruneEmptyAncestorsAndRecoverToTheNearestVisibleParent() {
        val direct = track(1, "/Music")
        val hiddenSong = track(2, "/Music/Artist/Album")
        val hiddenFolderSong = track(3, "/Other/Album")
        val all = listOf(direct, hiddenSong, hiddenFolderSong)
        val visible = visibleMusicTracks(all, listOf("/Other"), setOf(hiddenSong.contentUri))
        val hierarchy = buildInternalStorageHierarchy(visible)
        assertEquals(setOf(INTERNAL_STORAGE_ROOT, key("/Music")), hierarchy.directories.keys)
        assertEquals(key("/Music"), hierarchy.closestDirectoryKey(key("/Music/Artist/Album")))
        val deleted = buildInternalStorageHierarchy(visible.filterNot { it.contentUri == direct.contentUri })
        assertEquals(INTERNAL_STORAGE_ROOT, deleted.closestDirectoryKey(key("/Music")))
        assertTrue(deleted.directories.getValue(INTERNAL_STORAGE_ROOT).children.isEmpty())
    }

    @Test
    fun songSortChangesDoNotReorderFolders() {
        val first = track(1, "/").copy(title = "Alpha", titleSortKey = "alpha")
        val last = track(2, "/").copy(title = "Zulu", titleSortKey = "zulu")
        val hierarchy = buildInternalStorageHierarchy(listOf(
            first, last, track(3, "/Bravo/Album"), track(4, "/Alpha"), track(5, "/Alpha/Album"),
        ))
        val root = hierarchy.directories.getValue(INTERNAL_STORAGE_ROOT)
        for (descending in listOf(false, true)) {
            val config = MusicSortConfig(descending = descending)
            val folders = hierarchy.childDirectories(INTERNAL_STORAGE_ROOT)
            val songs = sortMusicTracks(root.tracks, config)
            assertEquals(listOf("Alpha", "Bravo"), folders.map { it.name })
            assertEquals(if (descending) listOf("Zulu", "Alpha") else listOf("Alpha", "Zulu"),
                songs.map { it.title })
            assertEquals(listOf(first, last), root.tracks)
        }
    }

    @Test
    fun breadcrumbsAlwaysStayInsidePrimaryStorageAndExposeEachAncestor() {
        assertEquals(listOf(INTERNAL_STORAGE_ROOT, key("/Music"), key("/Music/Artist")),
            internalStorageDirectoryTrail("$INTERNAL_STORAGE_ROOT/Music/Artist/"))
        assertEquals(listOf(INTERNAL_STORAGE_ROOT), internalStorageDirectoryTrail("/storage/ABCD-1234/Music"))
        assertEquals(listOf(INTERNAL_STORAGE_ROOT), internalStorageDirectoryTrail("/storage/emulated/01/Music"))
    }

    @Test
    fun addedCardAndChildFolderRowsOffsetAlphabetTargetsWithoutChangingSongOrder() {
        val indices = linkedMapOf("A" to 0, "B" to 3)
        assertSame(indices, offsetSectionIndices(indices, 0))
        assertEquals(mapOf("A" to 1, "B" to 4), offsetSectionIndices(indices, 1))
        assertEquals(mapOf("A" to 5, "B" to 8), offsetSectionIndices(indices, 5))
        assertEquals(mapOf("A" to 0, "B" to 3), indices)
    }

    @Test
    fun largeLibraryKeepsSongsOnlyInTheirDirectDirectory() {
        val tracks = (1L..20_000L).map { track(it, "/Music/Artist/Album") }
        val hierarchy = buildInternalStorageHierarchy(tracks)
        assertEquals(4, hierarchy.directories.size)
        assertEquals(20_000, hierarchy.directories.getValue(INTERNAL_STORAGE_ROOT).songCount)
        assertEquals(20_000, hierarchy.directories.values.sumOf { it.tracks.size })
        assertEquals(tracks, hierarchy.directories.getValue(key("/Music/Artist/Album")).tracks)
    }

    private fun key(relative: String) = (INTERNAL_STORAGE_ROOT + relative).lowercase()

    private fun track(id: Long, path: String?, uri: String = "content://media/external_primary/audio/media/$id") = MusicTrack(
        id = id, title = "Song $id", artist = "Artist", album = "Album", durationMs = 60_000,
        dateAddedEpochSeconds = 0, dateModifiedEpochSeconds = 0, fileName = "$id.mp3", fileSizeBytes = 100,
        contentUri = uri, titleSectionKey = "S", titleSortKey = "song $id", folderPath = path,
    )
}
