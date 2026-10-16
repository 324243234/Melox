package com.melox.player

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.melox.player.data.playlist.loadPlaylistArtwork
import com.melox.player.model.LocalPlaylist
import com.melox.player.model.MusicTrack
import com.melox.player.model.PlaylistTrackEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistArtworkBitmapTest {
    @Test
    fun separateBitmapsWithEqualPixelsUseOnlyOneTileAndBackfillLaterColors() = runBlocking {
        val bitmaps = listOf(Color.RED, Color.RED, Color.BLUE, Color.GREEN).map { color ->
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        }
        try {
            val playlist = LocalPlaylist(
                id = "playlist",
                name = "Playlist",
                createdAtEpochMillis = 0,
                updatedAtEpochMillis = 0,
                entries = bitmaps.indices.map { index ->
                    PlaylistTrackEntry("entry-$index", 0, track(index.toLong()))
                },
            )
            val selected = loadPlaylistArtwork(
                playlist,
                loadArtwork = { bitmaps[it.id.toInt()] },
                sameArtwork = Bitmap::sameAs,
            )
            assertEquals(3, selected.size)
            assertSame(bitmaps[0], selected[0])
            assertSame(bitmaps[2], selected[1])
            assertSame(bitmaps[3], selected[2])
        } finally {
            bitmaps.forEach(Bitmap::recycle)
        }
    }

    private fun track(id: Long) = MusicTrack(
        id = id,
        title = "Track $id",
        artist = null,
        album = "Album $id",
        durationMs = 1,
        dateAddedEpochSeconds = 0,
        dateModifiedEpochSeconds = 0,
        fileName = "$id.mp3",
        fileSizeBytes = 1,
        contentUri = "content://music/$id",
        titleSectionKey = "T",
        titleSortKey = "track $id",
    )
}
