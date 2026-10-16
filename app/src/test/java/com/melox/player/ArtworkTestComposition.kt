package com.melox.player

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

internal suspend fun CoroutineScope.withArtworkComposition(
    content: @Composable () -> Unit,
    test: suspend ArtworkTestComposition.() -> Unit,
) {
    val fixture = ArtworkTestComposition(this)
    try {
        fixture.composition.setContent(content)
        fixture.test()
    } finally {
        fixture.close()
    }
}

internal class ArtworkTestComposition(scope: CoroutineScope) {
    private val clock = BroadcastFrameClock()
    private val recomposer = Recomposer(scope.coroutineContext + clock)
    private val runner = scope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
    val composition = Composition(object : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }, recomposer)

    suspend fun frame(millis: Long) {
        repeat(6) {
            Snapshot.sendApplyNotifications()
            yield()
            clock.sendFrame(millis * 1_000_000L)
            yield()
        }
    }

    suspend fun close() {
        composition.dispose()
        recomposer.close()
        runner.cancelAndJoin()
    }
}
