package com.melox.player

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import com.melox.player.ui.component.library.ArtworkLoadResult
import com.melox.player.ui.component.library.rememberArtworkLoad
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkLoadStateTest {
    @Test
    fun newRequestCannotInheritThePreviousSongsCompletedImage() = runBlocking {
        val key = mutableStateOf("a")
        val pending = CompletableDeferred<String>()
        var rendered: String? = null
        withArtworkComposition(content = {
            val request = key.value
            val result = rememberArtworkLoad(request) {
                if (request == "a") "image-a" else pending.await()
            }
            SideEffect { rendered = result?.value }
        }) {
            frame(0)
            assertEquals("image-a", rendered)
            key.value = "b"
            frame(16)
            assertNull(rendered)
            pending.complete("image-b")
            frame(32)
            assertEquals("image-b", rendered)
        }
    }

    @Test
    fun newRequestsMemoryHitIsAvailableBeforeItsSuspendingLoadCompletes() = runBlocking {
        val key = mutableStateOf("a")
        val pending = CompletableDeferred<String>()
        var rendered: String? = null
        withArtworkComposition(content = {
            val request = key.value
            val result = rememberArtworkLoad(request, ArtworkLoadResult("cached-$request")) {
                pending.await()
            }
            SideEffect { rendered = result?.value }
        }) {
            frame(0)
            assertEquals("cached-a", rendered)
            key.value = "b"
            frame(16)
            assertEquals("cached-b", rendered)
        }
    }

    @Test
    fun cancelledDecodeFinishingAfterTheNewImageCannotPublish() = runBlocking {
        val key = mutableStateOf("a")
        val first = CompletableDeferred<String>()
        val second = CompletableDeferred<String>()
        var rendered: String? = null
        withArtworkComposition(content = {
            val request = key.value
            val result = rememberArtworkLoad(request) {
                withContext(NonCancellable) {
                    if (request == "a") first.await() else second.await()
                }
            }
            SideEffect { rendered = result?.value }
        }) {
            frame(0)
            key.value = "b"
            frame(16)
            second.complete("image-b")
            frame(32)
            assertEquals("image-b", rendered)
            first.complete("late-a")
            frame(48)
            assertEquals("image-b", rendered)
        }
    }

    @Test
    fun returningToTheSameKeyDoesNotReviveItsFirstCancelledRequest() = runBlocking {
        val key = mutableStateOf("a")
        val requests = mutableListOf<Pair<String, CompletableDeferred<String>>>()
        var rendered: String? = null
        withArtworkComposition(content = {
            val request = key.value
            val result = rememberArtworkLoad(request) {
                val pending = CompletableDeferred<String>()
                requests += request to pending
                withContext(NonCancellable) { pending.await() }
            }
            SideEffect { rendered = result?.value }
        }) {
            frame(0)
            key.value = "b"
            frame(16)
            key.value = "a"
            frame(32)
            assertEquals(listOf("a", "b", "a"), requests.map { it.first })
            requests[2].second.complete("current-a")
            frame(48)
            assertEquals("current-a", rendered)
            requests[0].second.complete("obsolete-a")
            requests[1].second.complete("obsolete-b")
            frame(64)
            assertEquals("current-a", rendered)
        }
    }

    @Test
    fun confirmedMissingArtworkIsDifferentFromAnUnfinishedRequest() = runBlocking {
        val pending = CompletableDeferred<String?>()
        var rendered: ArtworkLoadResult<String?>? = null
        withArtworkComposition(content = {
            val result = rememberArtworkLoad("missing") { pending.await() }
            SideEffect { rendered = result }
        }) {
            frame(0)
            assertNull(rendered)
            pending.complete(null)
            frame(16)
            assertNotNull(rendered)
            assertNull(rendered?.value)
        }
    }
}
