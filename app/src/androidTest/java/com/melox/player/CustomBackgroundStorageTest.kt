package com.melox.player

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.melox.player.data.repository.CustomBackgroundRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomBackgroundStorageTest {
    @Test
    fun importedCopySurvivesSourceRemovalAndSupportsBlurAndReplacement() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "background-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
        val isolatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        try {
            val source = File(directory, "source.png")
            val sourceBitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            Canvas(sourceBitmap).apply {
                drawColor(Color.BLUE)
                drawRect(320f, 0f, 640f, 480f, Paint().apply { color = Color.RED })
            }
            source.outputStream().use { sourceBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            sourceBitmap.recycle()
            val repository = CustomBackgroundRepository(isolatedContext)
            val firstId = requireNotNull(repository.importImage(Uri.fromFile(source)))
            val secondId = requireNotNull(repository.importImage(Uri.fromFile(source)))
            assertNotEquals(firstId, secondId)
            val discardedId = requireNotNull(repository.importImage(Uri.fromFile(source)))
            repository.deleteImage(discardedId)
            assertTrue(source.isFile)
            assertNull(repository.loadImage(discardedId, null))
            assertTrue(source.delete())
            val restartedRepository = CustomBackgroundRepository(isolatedContext)
            val clear = requireNotNull(restartedRepository.loadImage(firstId, null))
            assertEquals(640, clear.width)
            assertEquals(480, clear.height)
            assertEquals(Color.BLUE, clear.getPixel(0, 0))
            clear.recycle()
            for (percent in listOf(0, 1, 50, 100)) {
                val blurred = requireNotNull(restartedRepository.loadImage(firstId, percent))
                val maxEdge = com.melox.player.data.repository.customBackgroundBlurInputMaxEdge(percent)
                assertTrue(blurred.width <= maxEdge && blurred.height <= maxEdge)
                if (percent <= 1) assertEquals(640, blurred.width)
                if (percent == 0) assertEquals(Color.BLUE, blurred.getPixel(0, 0))
                if (percent >= 50) assertNotEquals(Color.BLUE, blurred.getPixel(blurred.width / 2 - 1, blurred.height / 2))
                blurred.recycle()
            }
            repository.deleteImage(firstId)
            assertNull(repository.loadImage(firstId, null))
            val second = repository.loadImage(secondId, null)
            assertNotNull(second)
            second?.recycle()
            assertNull(repository.importImage(Uri.fromFile(File(directory, "missing.png"))))
            assertNull(repository.loadImage("../source", null))
        } finally {
            directory.deleteRecursively()
        }
    }
}
