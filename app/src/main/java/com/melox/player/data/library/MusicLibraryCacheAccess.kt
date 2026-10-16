package com.melox.player.data.library

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coordinates every Repository instance accessing the same snapshot file. */
internal class MusicLibraryCacheAccess {
    data class ReadRevision(val request: Long, val write: Long)
    private val mutex = Mutex()
    private val revision = AtomicLong()
    private var writeRevision = 0L

    fun nextWrite(): Long = revision.incrementAndGet()

    suspend fun <T> read(block: suspend () -> T): Pair<ReadRevision, T> = mutex.withLock {
        val readingRevision = ReadRevision(revision.get(), writeRevision)
        readingRevision to block()
    }

    /** A superseded write is already owned by a newer request. */
    suspend fun write(request: Long, block: suspend () -> Boolean): Boolean = mutex.withLock {
        request != revision.get() || mutate(block)
    }

    suspend fun writeBack(read: ReadRevision, block: suspend () -> Boolean): Boolean = mutex.withLock {
        read.request != revision.get() || read.write != writeRevision || mutate(block)
    }

    private suspend fun mutate(block: suspend () -> Boolean): Boolean = try {
        block()
    } finally {
        writeRevision += 1L
    }

    companion object {
        private val files = ConcurrentHashMap<String, MusicLibraryCacheAccess>()

        fun forPath(path: String): MusicLibraryCacheAccess =
            files.computeIfAbsent(path) { MusicLibraryCacheAccess() }
    }
}
