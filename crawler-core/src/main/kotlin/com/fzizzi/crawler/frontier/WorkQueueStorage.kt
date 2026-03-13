package com.fzizzi.crawler.frontier

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Abstraction for queue storage used by the URLFrontier.
 *
 * Implementations can range from local in-memory queues to distributed ones (e.g., Redis Lists).
 * See DESIGN.md for distributed implementation details.
 */
interface WorkQueueStorage {
    /** Enqueues a [url] to the queue identified by [queueId]. */
    suspend fun offer(queueId: String, url: String)

    /** Dequeues and returns the next URL from [queueId], or null if empty. */
    suspend fun poll(queueId: String): String?

    /** Returns true if [queueId] contains no elements. */
    suspend fun isEmpty(queueId: String): Boolean

    /** Returns the number of elements across *all* managed queues. */
    suspend fun totalSize(): Int
}

/** Local in-memory implementation backed by [ConcurrentLinkedQueue]. */
class MemoryWorkQueueStorage : WorkQueueStorage {
    private val queues = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    private fun queue(id: String) = queues.getOrPut(id) { ConcurrentLinkedQueue() }

    override suspend fun offer(queueId: String, url: String) { queue(queueId).offer(url) }
    override suspend fun poll(queueId: String): String? = queue(queueId).poll()
    override suspend fun isEmpty(queueId: String): Boolean = queue(queueId).isEmpty()
    override suspend fun totalSize(): Int = queues.values.sumOf { it.size }
}
