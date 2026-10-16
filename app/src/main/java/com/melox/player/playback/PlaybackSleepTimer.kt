package com.melox.player.playback

import android.os.SystemClock
import androidx.media3.common.Player
import com.melox.player.model.SleepTimerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Service-owned timer; UI controllers can disconnect without cancelling playback policy. */
internal class PlaybackSleepTimer(
    private val scope: CoroutineScope,
    private val player: () -> Player?,
    private val publish: (SleepTimerState) -> Unit,
    private val close: (extended: Boolean, expectedIndex: Int?) -> Unit,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val awaitTick: suspend (Long) -> Unit = { delay(it) },
) {
    var state = SleepTimerState()
        private set
    var autoExtend = false
    private var job: Job? = null
    private var extendedItemIndex: Int? = null

    fun start(seconds: Int) {
        if (seconds !in 1..86_399) return
        job?.cancel()
        extendedItemIndex = null
        val deadline = nowMs() + seconds * 1_000L
        update(SleepTimerState(
            active = true,
            remainingSeconds = seconds,
            interruptionNotice = state.interruptionNotice,
        ))
        job = scope.launch(start = CoroutineStart.LAZY) {
            while (isActive) {
                val tick = sleepTimerTick(deadline, nowMs()) ?: break
                update(state.copy(remainingSeconds = tick.remainingSeconds))
                awaitTick(tick.delayUntilNextSecondMs)
            }
            if (!isActive) return@launch
            val currentPlayer = player()
            val remainingMs = currentPlayer?.let { it.duration - it.currentPosition }
                ?.takeIf { it > 0L && it < 86_400_000L }
            if (!autoExtend || currentPlayer?.isPlaying != true || remainingMs == null) {
                finish()
                return@launch
            }
            extendedItemIndex = currentPlayer.currentMediaItemIndex
            while (isActive) {
                val activePlayer = player() ?: run { cancel(interrupted = true); return@launch }
                if (activePlayer.duration - activePlayer.currentPosition <= 0L ||
                    activePlayer.playbackState == Player.STATE_ENDED
                ) {
                    finish(extended = true, expectedIndex = extendedItemIndex)
                    return@launch
                }
                update(state.copy(
                    extending = true,
                    remainingSeconds = 0,
                    extensionSeconds = remainingExtensionSeconds(
                        activePlayer.duration,
                        activePlayer.currentPosition,
                        activePlayer.playbackParameters.speed,
                    ),
                ))
                awaitTick(250L)
            }
        }.also(Job::start)
    }

    fun cancel(interrupted: Boolean = false) {
        job?.cancel()
        job = null
        extendedItemIndex = null
        update(SleepTimerState(
            interruptionNotice = state.interruptionNotice + if (interrupted) 1 else 0,
        ))
    }

    fun acknowledgeInterruption() = update(state.copy(interruptionNotice = 0))

    fun onPlayerEvents(activePlayer: Player) {
        if (state.extending && !activePlayer.playWhenReady) cancel(interrupted = true)
    }

    fun onMediaItemTransition(reason: Int) {
        if (!state.extending) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        ) {
            finish(
                extended = true,
                expectedIndex = extendedItemIndex.takeIf {
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                },
            )
        } else cancel(interrupted = true)
    }

    private fun finish(extended: Boolean = false, expectedIndex: Int? = null) {
        cancel()
        close(extended, expectedIndex)
    }

    private fun update(nextState: SleepTimerState) {
        if (state == nextState) return
        state = nextState
        publish(nextState)
    }
}
