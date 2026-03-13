package com.fzizzi.crawler.frontier

import java.util.concurrent.ConcurrentHashMap

/**
 * Manages mutual exclusion for back-queue slots in the URLFrontier.
 *
 * Ensures only one coroutine (or, in distributed mode, one node) processes
 * a given domain bucket at a time — the core of the politeness guarantee.
 *
 * See DESIGN.md for distributed implementation details.
 */
interface QueueLockManager {
    /**
     * Attempts to acquire the lock for [backQueueIndex].
     * @return true if the lock was obtained, false if it was already held.
     */
    suspend fun acquire(backQueueIndex: Int): Boolean

    /** Releases the lock for [backQueueIndex]. */
    suspend fun release(backQueueIndex: Int)
}

/**
 * Local in-memory implementation using [ConcurrentHashMap.putIfAbsent] for atomicity.
 *
 * [putIfAbsent] is a single atomic CAS operation on ConcurrentHashMap, so no explicit
 * synchronization block is needed — if two threads race, only one will observe a null return.
 */
class MemoryQueueLockManager : QueueLockManager {
    private val locks = ConcurrentHashMap<Int, Boolean>()

    override suspend fun acquire(backQueueIndex: Int): Boolean =
        locks.putIfAbsent(backQueueIndex, true) == null

    override suspend fun release(backQueueIndex: Int) {
        locks.remove(backQueueIndex)
    }
}
