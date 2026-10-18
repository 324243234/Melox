package com.melox.player

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import com.melox.player.ui.component.library.ArtworkLoadResult
import com.melox.player.ui.component.library.rememberArtworkLoad
import com.melox.player.ui.component.library.rememberRetainedArtworkValue
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
    fun confirmedMissingArtworkClearsTheRetainedImageBeforeAnotherReload() = runBlocking {
        val result = mutableStateOf<ArtworkLoadResult<String?>?>(ArtworkLoadResult("old-cover"))
        var rendered: String? = null
        val missingFrames = mutableListOf<String?>()
        withArtworkComposition(content = {
            val current = result.value
            val value = rememberRetainedArtworkValue(current, contentKey = "song")
            SideEffect {
                rendered = value
                if (current != null && current.value == null) missingFrames += value
            }
        }) {
            frame(0)
            assertEquals("old-cover", rendered)
            result.value = null
            frame(16)
            assertEquals("old-cover", rendered)
            result.value = ArtworkLoadResult(null)
            frame(32)
            assertNull(rendered)
            assertEquals(listOf<String?>(null), missingFrames.distinct())
            result.value = null
            frame(48)
            assertNull(rendered)
        }
    }

    @Test
    fun aNewSongCannotInheritTheRetainedImageWhileItsRequestIsPending() = runBlocking {
        val song = mutableStateOf("a")
        val result = mutableStateOf<ArtworkLoadResult<String?>?>(ArtworkLoadResult("image-a"))
        var rendered: String? = null
        withArtworkComposition(content = {
            val value = rememberRetainedArtworkValue(result.value, contentKey = song.value)
            SideEffect { rendered = value }
        }) {
            frame(0)
            assertEquals("image-a", rendered)
            song.value = "b"
            result.value = null
            frame(16)
            assertNull(rendered)
            result.value = ArtworkLoadResult("image-b")
            frame(32)
            assertEquals("image-b", rendered)
        }
    }

    @Test
    fun aCompletedReplacementBecomesTheRetainedImageForTheFollowingReload() = runBlocking {
        val result = mutableStateOf<ArtworkLoadResult<String?>?>(ArtworkLoadResult("low"))
        var rendered: String? = null
        withArtworkComposition(content = {
            val value = rememberRetainedArtworkValue(result.value, contentKey = "song")
            SideEffect { rendered = value }
        }) {
            frame(0)
            result.value = null
            frame(16)
            assertEquals("low", rendered)
            result.value = ArtworkLoadResult("high")
            frame(32)
            assertEquals("high", rendered)
            result.value = null
            frame(48)
            assertEquals("high", rendered)
        }
    }

    @Test
    fun disablingArtworkClearsRetentionBeforeItIsEnabledAgain() = runBlocking {
        val result = mutableStateOf<ArtworkLoadResult<String?>?>(ArtworkLoadResult("old"))
        val enabled = mutableStateOf(true)
        var rendered: String? = null
        withArtworkComposition(content = {
            val value = rememberRetainedArtworkValue(result.value, "song", enabled.value)
            SideEffect { rendered = value }
        }) {
            frame(0)
            assertEquals("old", rendered)
            enabled.value = false
            frame(16)
            assertNull(rendered)
            result.value = null
            enabled.value = true
            frame(32)
            assertNull(rendered)
        }
    }

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
