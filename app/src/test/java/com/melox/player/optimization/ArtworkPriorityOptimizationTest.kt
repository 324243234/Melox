package com.melox.player.optimization

import com.melox.player.ui.component.library.ArtworkDecodeLimiter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ArtworkPriorityOptimizationTest {
    @Test
    fun aQueuedListRequestUsesTheReservedSlotWhenAPlayerJoinsIt() = runBlocking {
        val limiter = ArtworkDecodeLimiter()
        val release = CompletableDeferred<Unit>()
        val entered = Channel<String>(Channel.UNLIMITED)
        val lists = List(2) { index -> launch {
            limiter.decode(false) { entered.send("list-$index"); release.await() }
        } }
        val priority = limiter.request(false)
        var decodeCount = 0
        val queued = launch {
            limiter.decode(priority) { decodeCount++; entered.send("shared"); release.await() }
        }
        try {
            withTimeout(2_000L) { repeat(2) { assertTrue(entered.receive().startsWith("list-")) } }
            yield()
            assertEquals(0, decodeCount)
            priority.promote()
            priority.promote()
            withTimeout(2_000L) { assertEquals("shared", entered.receive()) }
            assertEquals(1, decodeCount)
        } finally {
            release.complete(Unit)
            (lists + queued).joinAll()
        }
    }

    @Test
    fun aPromotedRequestPassesOlderBackgroundWorkWithoutExceedingCapacity() = runBlocking {
        val limiter = ArtworkDecodeLimiter()
        val releases = List(3) { CompletableDeferred<Unit>() }
        val entered = Channel<String>(Channel.UNLIMITED)
        val active = releases.mapIndexed { index, release -> launch {
            limiter.decode(index == 2) { entered.send("active-$index"); release.await() }
        } }
        val background = launch { limiter.decode(false) { entered.send("background") } }
        val priority = limiter.request(false)
        val promoted = launch { limiter.decode(priority) { entered.send("promoted") } }
        try {
            withTimeout(2_000L) { repeat(3) { assertTrue(entered.receive().startsWith("active-")) } }
            yield()
            priority.promote()
            assertTrue(entered.tryReceive().isFailure)
            releases[0].complete(Unit)
            withTimeout(2_000L) { assertEquals("promoted", entered.receive()) }
            withTimeout(2_000L) { assertEquals("background", entered.receive()) }
        } finally {
            releases.forEach { it.complete(Unit) }
            (active + background + promoted).joinAll()
        }
    }

    @Test
    fun cancellingAPromotedQueuedRequestDoesNotLeakTheReservedCapacity() = runBlocking {
        val limiter = ArtworkDecodeLimiter()
        val release = CompletableDeferred<Unit>()
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val active = List(3) { index -> launch {
            limiter.decode(index == 2) { entered.send(Unit); release.await() }
        } }
        val priority = limiter.request(false)
        val cancelled = launch { limiter.decode(priority) { fail("Cancelled queued work ran") } }
        try {
            withTimeout(2_000L) { repeat(3) { entered.receive() } }
            yield()
            priority.promote()
            cancelled.cancelAndJoin()
        } finally { release.complete(Unit); active.joinAll(); cancelled.cancelAndJoin() }
        val nextRelease = CompletableDeferred<Unit>()
        val next = List(3) { index -> launch {
            limiter.decode(index == 2) { entered.send(Unit); nextRelease.await() }
        } }
        try {
            withTimeout(2_000L) { repeat(3) { entered.receive() } }
        } finally { nextRelease.complete(Unit); next.joinAll() }
    }
}
