package com.melox.player.optimization

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.melox.player.model.PlaybackMode
import com.melox.player.playback.PlaybackQueueProjection
import com.melox.player.playback.PlaybackSleepTimer
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PlaybackOptimizationTest {
    @Test
    fun positionSamplesDoNotRebuildQueueMetadata() {
        val source = PlayerSource()
        val projection = PlaybackQueueProjection()
        val queue = projection.queue(source.player, PlaybackMode.ORDER)
        repeat(100) {
            source.position += 500L
            assertSame(queue, projection.queue(source.player, PlaybackMode.ORDER))
        }
        assertEquals(2, source.metadataReads)
        source.items = source.items.reversed()
        source.timeline = QueueTimeline()
        val reversed = projection.queue(source.player, PlaybackMode.ORDER)
        assertEquals(listOf("two", "one"), reversed.map { it.mediaId })
        assertEquals(4, source.metadataReads)
        val random = projection.queue(source.player, PlaybackMode.RANDOM)
        assertEquals(setOf(PlaybackMode.RANDOM), random.map { it.playbackMode }.toSet())
        assertSame(random, projection.queue(source.player, PlaybackMode.RANDOM))
        assertEquals(6, source.metadataReads)
    }

    @Test
    fun aPlayerReplacementOrMetadataTimelineChangeRefreshesTheProjection() {
        val projection = PlaybackQueueProjection()
        val source = PlayerSource()
        val first = projection.queue(source.player, PlaybackMode.ORDER)
        source.items = source.items.map { it.buildUpon().setMediaMetadata(
            it.mediaMetadata.buildUpon().setTitle("Updated").build()).build() }
        source.timeline = QueueTimeline()
        val updated = projection.queue(source.player, PlaybackMode.ORDER)
        assertEquals("Updated", updated.first().title)
        assertNotSame(first, updated)
        val replacement = PlayerSource()
        assertEquals("one", projection.queue(replacement.player, PlaybackMode.ORDER).first().title)
        assertEquals(2, replacement.metadataReads)
    }

    @Test
    fun timerExpiryKeepsItsDeadlineWhenTheUiScopeIsReleased() = runBlocking {
        val serviceScope = CoroutineScope(coroutineContext + SupervisorJob())
        val uiScope = CoroutineScope(coroutineContext + SupervisorJob())
        val source = PlayerSource()
        val ticks = Channel<Long>(Channel.UNLIMITED)
        val resume = Channel<Unit>(Channel.UNLIMITED)
        var now = 0L
        val closed = Channel<Pair<Boolean, Int?>>(Channel.UNLIMITED)
        val timer = PlaybackSleepTimer(serviceScope, { source.player }, {}, { extended, index ->
            closed.trySend(extended to index)
        }, { now }, { ticks.send(it); resume.receive() })
        try {
            timer.start(10)
            withTimeout(2_000L) { assertEquals(1_000L, ticks.receive()) }
            uiScope.cancel()
            assertTrue(timer.state.active)
            now = 10_000L
            resume.send(Unit)
            withTimeout(2_000L) { assertEquals(false to null, closed.receive()) }
            assertFalse(timer.state.active)
        } finally { uiScope.cancel(); serviceScope.cancel() }
    }

    @Test
    fun extendedTimerPreservesAutomaticAndRepeatBoundaryDecisions() = runBlocking {
        for (reason in listOf(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)) {
            val fixture = TimerFixture(CoroutineScope(coroutineContext + SupervisorJob()))
            try {
                fixture.enterExtension()
                fixture.timer.onMediaItemTransition(reason)
                assertEquals(true to if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) 1 else null,
                    fixture.closed.single())
                assertFalse(fixture.timer.state.active)
            } finally { fixture.scope.cancel() }
        }
    }

    @Test
    fun manualTransitionAndPauseInterruptExtensionWithoutClosingPlayback() = runBlocking {
        for (pause in listOf(false, true)) {
            val fixture = TimerFixture(CoroutineScope(coroutineContext + SupervisorJob()))
            try {
                fixture.enterExtension()
                if (pause) {
                    fixture.source.ready = false
                    fixture.timer.onPlayerEvents(fixture.source.player)
                } else fixture.timer.onMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
                assertFalse(fixture.timer.state.active)
                assertEquals(1, fixture.timer.state.interruptionNotice)
                assertTrue(fixture.closed.isEmpty())
                fixture.timer.acknowledgeInterruption()
                assertEquals(0, fixture.timer.state.interruptionNotice)
            } finally { fixture.scope.cancel() }
        }
    }

    @Test
    fun extensionUsesPlaybackSpeedAndCanFinishAtTheMediaEnd() = runBlocking {
        val fixture = TimerFixture(CoroutineScope(coroutineContext + SupervisorJob()))
        try {
            fixture.source.speed = 0.5f
            fixture.enterExtension()
            assertEquals(18, fixture.timer.state.extensionSeconds)
            fixture.source.position = fixture.source.duration
            fixture.resume.send(Unit)
            repeat(10) { yield() }
            assertEquals(listOf(true to 1), fixture.closed)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun cancellationAndInvalidStartsDoNotFireTheCloseAction() = runBlocking {
        val fixture = TimerFixture(CoroutineScope(coroutineContext + SupervisorJob()))
        try {
            fixture.timer.start(20)
            val active = fixture.timer.state
            fixture.timer.start(0)
            fixture.timer.start(86_400)
            assertEquals(active, fixture.timer.state)
            fixture.timer.cancel()
            repeat(10) { yield() }
            assertFalse(fixture.timer.state.active)
            assertTrue(fixture.closed.isEmpty())
        } finally { fixture.scope.cancel() }
    }

    private class TimerFixture(val scope: CoroutineScope) {
        val source = PlayerSource()
        val ticks = Channel<Long>(Channel.UNLIMITED)
        val resume = Channel<Unit>(Channel.UNLIMITED)
        val closed = mutableListOf<Pair<Boolean, Int?>>()
        var now = 0L
        val timer = PlaybackSleepTimer(scope, { source.player }, {}, { extended, index ->
            closed += extended to index
        }, { now }, { ticks.send(it); resume.receive() })
        suspend fun enterExtension() {
            timer.autoExtend = true
            timer.start(1)
            withTimeout(2_000L) { assertEquals(1_000L, ticks.receive()) }
            now = 1_000L
            resume.send(Unit)
            withTimeout(2_000L) { assertEquals(250L, ticks.receive()) }
            assertTrue(timer.state.active)
            assertTrue(timer.state.extending)
        }
    }

    private class PlayerSource {
        var timeline: Timeline = QueueTimeline()
        var items = listOf("one", "two").map { name ->
            MediaItem.Builder().setMediaId(name)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build()).build()
        }
        var metadataReads = 0
        var position = 1_000L
        var duration = 10_000L
        var ready = true
        var speed = 1f
        val player = Proxy.newProxyInstance(Player::class.java.classLoader,
            arrayOf(Player::class.java)) { proxy, method, args ->
            when (method.name) {
                "getCurrentTimeline" -> timeline
                "getMediaItemCount" -> items.size
                "getMediaItemAt" -> { metadataReads++; items[args!![0] as Int] }
                "getDuration" -> duration
                "getCurrentPosition" -> position
                "getCurrentMediaItemIndex" -> 1
                "getPlayWhenReady", "isPlaying" -> ready
                "getPlaybackState" -> Player.STATE_READY
                "getPlaybackParameters" -> PlaybackParameters(speed)
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "PlayerSource"
                else -> error("Unexpected player call: ${method.name}")
            }
        } as Player
    }

    private class QueueTimeline : Timeline() {
        override fun getWindowCount() = 2
        override fun getPeriodCount() = 2
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            error("Queue projection must not read window contents")
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
            error("Queue projection must not read period contents")
        override fun getIndexOfPeriod(uid: Any) = -1
        override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
    }
}
