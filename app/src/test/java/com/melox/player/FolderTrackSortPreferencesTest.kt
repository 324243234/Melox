package com.melox.player

import com.melox.player.data.library.MusicSortConfig
import com.melox.player.data.library.MusicSortField
import com.melox.player.data.repository.decodeFolderTrackSortConfigs
import com.melox.player.data.repository.encodeFolderTrackSortConfigs
import com.melox.player.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderTrackSortPreferencesTest {
    @Test
    fun everySortFieldAndDirectionSurvivesStorageWithIndependentFolderIdentities() {
        val configs = buildMap {
            for (field in MusicSortField.entries) {
                for (descending in listOf(false, true)) {
                    put("/storage/emulated/0/music/${field.name}/$descending", MusicSortConfig(field, descending))
                }
            }
        }
        assertEquals(configs, decodeFolderTrackSortConfigs(encodeFolderTrackSortConfigs(configs)))
        val changed = configs + (configs.keys.first() to MusicSortConfig(MusicSortField.DURATION, true))
        assertEquals(changed, decodeFolderTrackSortConfigs(encodeFolderTrackSortConfigs(changed)))
    }

    @Test
    fun unknownFolderAndPathsContainingDelimitersRetainTheirIdentities() {
        val configs = mapOf(
            "" to MusicSortConfig(MusicSortField.FILE_NAME, true),
            "/storage/abcd-1234/music/album|live" to MusicSortConfig(MusicSortField.DATE_ADDED),
            "/storage/emulated/0/music/album|live" to MusicSortConfig(MusicSortField.ARTIST, true),
        )
        assertEquals(configs, decodeFolderTrackSortConfigs(encodeFolderTrackSortConfigs(configs)))
    }

    @Test
    fun missingOrMalformedPreferencesLeaveTitleAscendingAvailable() {
        val restored = decodeFolderTrackSortConfigs(
            setOf("ARTIST|true|/valid", "FUTURE|false|/unknown", "TITLE|maybe|/broken", "TITLE|false", "bad"),
        )
        assertEquals(mapOf("/valid" to MusicSortConfig(MusicSortField.ARTIST, true)), restored)
        assertEquals(MusicSortConfig(), restored["/unknown"] ?: MusicSortConfig())
        assertEquals(emptyMap<String, MusicSortConfig>(), decodeFolderTrackSortConfigs(emptySet()))
        assertEquals(emptyMap<String, MusicSortConfig>(), AppSettings().folderTrackSortConfigs)
    }
}
