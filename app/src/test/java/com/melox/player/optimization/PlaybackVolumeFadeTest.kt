package com.melox.player.optimization

import androidx.media3.common.Player
import com.melox.player.playback.PlaybackVolumeFade
import com.melox.player.playback.isForcedPlaybackPause
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

class PlaybackVolumeFadeTest {
    private val forcedReasons = listOf(
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM,
        Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG,
    )

    @Test
    fun onlyUserAndRemotePausesMayFade() {
        for (reason in forcedReasons) {
            assertTrue(isForcedPlaybackPause(false, reason))
            assertFalse(isForcedPlaybackPause(true, reason))
        }
        assertFalse(isForcedPlaybackPause(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST))
        assertFalse(isForcedPlaybackPause(false, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE))
    }

    @Test
    fun appPauseKeepsPlayingUntilTheExistingFadeCompletes() = withFade {
        fade.requestPlaybackIntent(false)
        awaitFrame()
        assertTrue(ready)
        assertTrue(fade.hasPendingPause)
        advance(125L)
        awaitFrame()
        assertEquals(0.5f, volume, 0.001f)
        assertTrue(actions.isEmpty())
        advance(125L)
        assertFalse(ready)
        assertFalse(fade.hasPendingPause)
        assertEquals(listOf("pause"), actions)
        assertEquals(1f, volume, 0f)
    }

    @Test
    fun userAndRemoteControlPausesRetainTheirFade() {
        for (reason in listOf(Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)) withFade {
            report(false, reason)
            awaitFrame()
            assertTrue(ready)
            assertTrue(fade.hasPendingPause)
            assertEquals(listOf("play"), actions)
            advance(250L)
            assertFalse(ready)
            assertFalse(fade.hasPendingPause)
            assertEquals(listOf("play", "pause"), actions)
        }
    }

    @Test
    fun everyForcedPauseStaysPausedWithoutStartingAFade() {
        for (reason in forcedReasons) withFade {
            report(false, reason)
            advance(1_000L)
            assertFalse(ready)
            assertFalse(fade.hasPendingPause)
            assertTrue(actions.isEmpty())
            assertEquals(1f, volume, 0f)
        }
    }

    @Test
    fun forcedPauseCancelsAnAppFadeOutWithoutALatePauseOrResume() {
        for (reason in forcedReasons) withFade {
            fade.requestPlaybackIntent(false)
            awaitFrame()
            advance(125L)
            awaitFrame()
            report(false, reason)
            assertFalse(fade.hasPendingPause)
            assertEquals(1f, volume, 0f)
            advance(1_000L)
            assertFalse(ready)
            assertTrue(actions.isEmpty())
            assertEquals(1f, volume, 0f)
        }
    }

    @Test
    fun forcedPauseCancelsTheTemporaryResumeForARemoteFadeOut() = withFade {
        report(false, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
        awaitFrame()
        assertEquals(listOf("play"), actions)
        report(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
        advance(1_000L)
        assertFalse(ready)
        assertFalse(fade.hasPendingPause)
        assertEquals(listOf("play"), actions)
        assertEquals(1f, volume, 0f)
    }

    @Test
    fun forcedPauseCancelsBothAppAndRemoteFadeIns() {
        for (appRequest in listOf(true, false)) withFade {
            report(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
            if (appRequest) fade.requestPlaybackIntent(true)
            else report(true, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            awaitFrame()
            advance(125L)
            awaitFrame()
            assertEquals(0.5f, volume, 0.001f)
            report(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
            val actionsAtPause = actions.toList()
            advance(1_000L)
            assertFalse(ready)
            assertEquals(actionsAtPause, actions)
            assertEquals(1f, volume, 0f)
        }
    }

    @Test
    fun reasonOnlyPausesCannotRestartPlayback() = withFade {
        report(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        report(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
        report(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        advance(1_000L)
        assertFalse(ready)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun forcedPauseClearsAnUnreportedInternalPauseBeforeTheNextPlay() = withFade {
        fade.requestPlaybackIntent(false)
        awaitFrame()
        notifyChanges = false
        advance(250L)
        assertFalse(ready)
        report(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        notifyChanges = true
        report(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        awaitFrame()
        assertEquals(0f, volume, 0f)
        advance(250L)
        assertTrue(ready)
        assertEquals(1f, volume, 0f)
    }

    @Test
    fun forcedPauseClearsAnUnreportedFadeResumeBeforeTheNextPlay() = withFade {
        report(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        notifyChanges = false
        fade.requestPlaybackIntent(true)
        awaitFrame()
        report(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
        notifyChanges = true
        report(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        awaitFrame()
        advance(250L)
        assertTrue(ready)
        assertEquals(1f, volume, 0f)
    }

    @Test
    fun reversingAnAppPauseCancelsThePendingPause() = withFade {
        fade.requestPlaybackIntent(false)
        awaitFrame()
        advance(125L)
        awaitFrame()
        fade.requestPlaybackIntent(true)
        awaitFrame()
        assertFalse(fade.hasPendingPause)
        advance(250L)
        assertTrue(ready)
        assertTrue(actions.isEmpty())
        assertEquals(1f, volume, 0f)
    }

    @Test
    fun disablingAFadeFinishesItsPendingPauseImmediately() = withFade {
        fade.requestPlaybackIntent(false)
        awaitFrame()
        fade.setEnabled(false)
        advance(1_000L)
        assertFalse(ready)
        assertFalse(fade.hasPendingPause)
        assertEquals(listOf("pause"), actions)
        assertEquals(1f, volume, 0f)
    }

    @Test
    fun disabledFadesAndBufferingPausesNeverTemporarilyResume() = withFade {
        fade.setEnabled(false)
        report(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        report(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        fade.setEnabled(true)
        playbackState = Player.STATE_BUFFERING
        report(false, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
        advance(1_000L)
        assertFalse(ready)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun duckingMultipliesTheFadeAndFocusPauseCancelsPendingMotion() = withFade {
        fade.setFocusVolumeMultiplier(0.2f)
        fade.requestPlaybackIntent(false)
        awaitFrame()
        advance(125L)
        awaitFrame()
        assertEquals(0.1f, volume, 0.001f)
        fade.pauseImmediately()
        advance(1_000L)
        assertFalse(ready)
        assertFalse(fade.hasPendingPause)
        assertEquals(listOf("pause"), actions)
        assertEquals(0.2f, volume, 0f)
        fade.setFocusVolumeMultiplier(1f)
        assertEquals(1f, volume, 0f)
    }

    @Test
    fun releaseCancelsAnUnfinishedPauseAndRestoresVolume() = withFade {
        fade.requestPlaybackIntent(false)
        awaitFrame()
        advance(125L)
        awaitFrame()
        fade.release()
        advance(1_000L)
        assertTrue(ready)
        assertTrue(actions.isEmpty())
        assertEquals(1f, volume, 0f)
    }

    private fun withFade(test: suspend Fixture.() -> Unit) {
        runBlocking {
            val fixture = Fixture(this)
            try { fixture.test() } finally { fixture.close() }
        }
    }

    private class Fixture(parent: CoroutineScope) {
        private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob())
        private val frames = Channel<Unit>(Channel.UNLIMITED)
        private val resume = Channel<Unit>(Channel.UNLIMITED)
        private var now = 0L
        var ready = true
        var playbackState = Player.STATE_READY
        var volume = 1f
        var notifyChanges = true
        val actions = mutableListOf<String>()
        private val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader,
            arrayOf(Player::class.java)) { proxy, method, args ->
            when (method.name) {
                "getPlayWhenReady" -> ready
                "getPlaybackState" -> playbackState
                "getVolume" -> volume
                "setVolume" -> { volume = args!![0] as Float; null }
                "play", "pause" -> {
                    actions += method.name
                    ready = method.name == "play"
                    if (notifyChanges) fade.onPlayWhenReadyChanged(ready,
                        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                    null
                }
                "prepare" -> { actions += "prepare"; playbackState = Player.STATE_BUFFERING; null }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FadePlayer"
                else -> error("Unexpected player call: ${method.name}")
            }
        } as Player
        val fade: PlaybackVolumeFade = PlaybackVolumeFade(player, scope, { now }, {
            frames.send(Unit)
            resume.receive()
        }).apply { setEnabled(true) }

        fun report(playWhenReady: Boolean, reason: Int) {
            ready = playWhenReady
            fade.onPlayWhenReadyChanged(playWhenReady, reason)
        }

        suspend fun awaitFrame() = withTimeout(2_000L) { frames.receive() }

        suspend fun advance(elapsedMs: Long) {
            now += elapsedMs
            resume.send(Unit)
            yield()
        }

        fun close() { scope.cancel() }
    }
}
