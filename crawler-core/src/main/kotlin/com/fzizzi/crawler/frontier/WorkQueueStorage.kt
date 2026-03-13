package com.fzizzi.crawler.frontier

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

interface WorkQueueStorage {
    suspend fun offer(queueId: String, url: String)

    suspend fun poll(queueId: String): String?

    suspend fun isEmpty(queueId: String): Boolean

    suspend fun totalSize(): Int
}

class MemoryWorkQueueStorage : WorkQueueStorage {
    private val queues = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    private fun queue(id: String) = queues.getOrPut(id) { ConcurrentLinkedQueue() }

    override suspend fun offer(queueId: String, url: String) { queue(queueId).offer(url) }
    override suspend fun poll(queueId: String): String? = queue(queueId).poll()
    override suspend fun isEmpty(queueId: String): Boolean = queue(queueId).isEmpty()
    override suspend fun totalSize(): Int = queues.values.sumOf { it.size }
}
