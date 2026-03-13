package com.fzizzi.crawler.frontier

import java.util.concurrent.ConcurrentHashMap

interface QueueLockManager {
    suspend fun acquire(backQueueIndex: Int): Boolean

    suspend fun release(backQueueIndex: Int)
}

class MemoryQueueLockManager : QueueLockManager {
    private val locks = ConcurrentHashMap<Int, Boolean>()

    override suspend fun acquire(backQueueIndex: Int): Boolean =
        locks.putIfAbsent(backQueueIndex, true) == null

    override suspend fun release(backQueueIndex: Int) {
        locks.remove(backQueueIndex)
    }
}
