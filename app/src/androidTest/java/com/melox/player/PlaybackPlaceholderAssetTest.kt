package com.melox.player

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.melox.player.playback.loadPlaybackArtworkData
import com.melox.player.ui.component.library.PLACEHOLDER_ARTWORK_RES_ID
import com.melox.player.ui.component.library.loadPlaceholderArtworkBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackPlaceholderAssetTest {
    @Test
    fun dayAndNightLoadTheSameOpaquePlaceholder() = runBlocking {
        val light = loadPlaceholderArtworkBitmap(themedContext(false), PLACEHOLDER_ARTWORK_RES_ID, 512)!!
        val dark = loadPlaceholderArtworkBitmap(themedContext(true), PLACEHOLDER_ARTWORK_RES_ID, 512)!!
        assertTrue(light.sameAs(dark))
        assertOpaquePixels(light)
    }

    @Test
    fun systemMediaPlaceholderIsOpaqueAndIndependentOfTheme() {
        val light = themedContext(false).loadPlaybackArtworkData("")!!
        val dark = themedContext(true).loadPlaybackArtworkData("")!!
        assertArrayEquals(light, dark)
        assertArrayEquals(
            byteArrayOf(0xff.toByte(), 0xd8.toByte()),
            light.copyOf(2),
        )
        val bitmap = BitmapFactory.decodeByteArray(light, 0, light.size)!!
        assertOpaquePixels(bitmap)
        bitmap.recycle()
    }

    private fun assertOpaquePixels(bitmap: Bitmap) {
        assertTrue(Color.alpha(bitmap.getPixel(0, 0)) == 255)
        assertTrue(Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 4)) == 255)
    }

    private fun themedContext(dark: Boolean): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return context.createConfigurationContext(configuration)
    }
}
