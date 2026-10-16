package com.melox.player.ui.component.library

import kotlinx.coroutines.CompletableDeferred

/** Leaves one decode slot available to the current player while lists load artwork. */
internal class ArtworkDecodeLimiter {
    private val lock = Any()
    private val waiting = ArrayDeque<Request>()
    private var running = 0
    private var backgroundRunning = 0

    inner class Request internal constructor(internal var foreground: Boolean) {
        internal val ready = CompletableDeferred<Unit>()
        internal var submitted = false
        internal var started = false
        internal var countsAsBackground = false

        fun promote() = synchronized(lock) {
            foreground = true
            startWaitingRequests()
        }
    }

    fun request(foreground: Boolean): Request = Request(foreground)

    suspend fun <T> decode(foreground: Boolean, block: suspend () -> T): T =
        decode(request(foreground), block)

    suspend fun <T> decode(request: Request, block: suspend () -> T): T {
        synchronized(lock) {
            check(!request.submitted)
            request.submitted = true
            waiting.addLast(request)
            startWaitingRequests()
        }
        try {
            request.ready.await()
            return block()
        } finally {
            synchronized(lock) {
                if (request.started) {
                    running--
                    if (request.countsAsBackground) backgroundRunning--
                } else {
                    waiting.remove(request)
                }
                startWaitingRequests()
            }
        }
    }

    /** Called only while holding [lock]; promotion never restarts an active decode. */
    private fun startWaitingRequests() {
        while (running < 3) {
            val next = waiting.firstOrNull { it.foreground }
                ?: waiting.firstOrNull()?.takeIf { backgroundRunning < 2 }
                ?: return
            waiting.remove(next)
            next.started = true
            next.countsAsBackground = !next.foreground
            running++
            if (next.countsAsBackground) backgroundRunning++
            next.ready.complete(Unit)
        }
    }
}
