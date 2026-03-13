package com.fzizzi.crawler.frontier

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger


interface Prioritizer {
    fun getPriority(url: String): Int
}

class DefaultPrioritizer(private val maxPriority: Int) : Prioritizer {
    override fun getPriority(url: String): Int =
        if (url.contains(".gov") || url.contains(".edu")) maxPriority - 1 else 0
}

class BackQueueRouter(private val numQueues: Int) {
    private val mappingTable = ConcurrentHashMap<String, Int>()
    private val nextQueueIndex = AtomicInteger(0)

    fun getQueueIndex(url: String): Int {
        val host = try { URI(url).host ?: url } catch (e: Exception) { url }
        return mappingTable.computeIfAbsent(host) {
            nextQueueIndex.getAndIncrement().mod(numQueues)
        }
    }
}

interface RecrawlStrategy {
    fun shouldRecrawl(url: String): Boolean
}

class DefaultRecrawlStrategy : RecrawlStrategy {
    override fun shouldRecrawl(url: String): Boolean = false
}

class DefaultURLFrontier(
    private val numFrontQueues: Int = 3,
    private val numBackQueues: Int = 10,
    private val prioritizer: Prioritizer = DefaultPrioritizer(3),
    private val recrawlStrategy: RecrawlStrategy = DefaultRecrawlStrategy(),
    private val frontStorage: WorkQueueStorage = MemoryWorkQueueStorage(),
    private val backStorage: WorkQueueStorage = MemoryWorkQueueStorage(),
    private val lockManager: QueueLockManager = MemoryQueueLockManager()
) : URLFrontier {

    private val backRouter = BackQueueRouter(numBackQueues)
    private val totalPending = AtomicInteger(0)

    override suspend fun add(url: String) {
        val priority = prioritizer.getPriority(url).coerceIn(0, numFrontQueues - 1)
        frontStorage.offer("front-$priority", url)
        totalPending.incrementAndGet()
        routeFrontToBack()
    }

    override suspend fun addAll(urls: List<String>) {
        urls.forEach { add(it) }
    }

    private suspend fun routeFrontToBack() {
        val maxDrain = 1000
        var count = 0
        for (priority in (numFrontQueues - 1) downTo 0) {
            while (count < maxDrain) {
                val url = frontStorage.poll("front-$priority") ?: break
                val backIdx = backRouter.getQueueIndex(url)
                backStorage.offer("back-$backIdx", url)
                count++
            }
        }
    }

    override suspend fun getNext(): String? {
        routeFrontToBack()

        for (i in 0 until numBackQueues) {
            if (!lockManager.acquire(i)) continue
            val url = backStorage.poll("back-$i")
            if (url != null) {
                totalPending.decrementAndGet()
                return url
            }
            lockManager.release(i)
        }
        return null
    }

    override suspend fun markCompleted(url: String) {
        val backIdx = backRouter.getQueueIndex(url)
        lockManager.release(backIdx)
    }

    override suspend fun isEmpty(): Boolean = totalPending.get() == 0
}
