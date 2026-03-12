package com.fzizzi.crawler.frontier

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

// 1. Front Queues (Priority)
interface Prioritizer {
    fun getPriority(url: String): Int
}

class DefaultPrioritizer(private val maxPriority: Int) : Prioritizer {
    override fun getPriority(url: String): Int {
        // Simple heuristic: domains like .gov or .edu get higher priority
        return if (url.contains(".gov") || url.contains(".edu")) {
            maxPriority - 1
        } else {
            0
        }
    }
}

class FrontQueueSelector(private val queues: List<ConcurrentLinkedQueue<String>>) {
    fun selectNext(): String? {
        // Select from higher priority to lower priority
        for (i in queues.indices.reversed()) {
            val url = queues[i].poll()
            if (url != null) return url
        }
        return null
    }
}

// 2. Back Queues (Politeness)
class BackQueueRouter(private val numQueues: Int) {
    private val mappingTable = ConcurrentHashMap<String, Int>()
    private val nextQueueIndex = AtomicInteger(0)

    fun getQueueIndex(url: String): Int {
        val host = try {
            URI(url).host ?: url // TODO check if makes sense to just use url if host null
        } catch (e: Exception) { // Maybe Extract logic out of this and pass the host directly, include host normalization like lowercasing, stripping ports, etc.
            url // This is before DNS resolving so the IP and matching DNS name could be in different queues #uncool
        }
        return mappingTable.computeIfAbsent(host) {
            nextQueueIndex.getAndIncrement().mod(numQueues) // .mod instead of % to avoid issues with possible overflow
        }
    }
}

// 4. Freshness
interface RecrawlStrategy {
    fun shouldRecrawl(url: String): Boolean
}

class DefaultRecrawlStrategy : RecrawlStrategy {
    override fun shouldRecrawl(url: String): Boolean {
        // Placeholder for update history validation logic
        return false
    }
}

// Advanced URL Frontier implementation
class DefaultURLFrontier(
    private val numFrontQueues: Int = 3,
    private val numBackQueues: Int = 10,
    private val prioritizer: Prioritizer = DefaultPrioritizer(numFrontQueues),
    private val recrawlStrategy: RecrawlStrategy = DefaultRecrawlStrategy()
) : URLFrontier {

    private val frontQueues = List(numFrontQueues) { ConcurrentLinkedQueue<String>() }
    private val frontSelector = FrontQueueSelector(frontQueues)

    private val backQueues = List(numBackQueues) { ConcurrentLinkedQueue<String>() }
    private val backRouter = BackQueueRouter(numBackQueues)

    // Politeness mappings: tracks if a worker is currently processing a queue
    private val activeQueues = ConcurrentHashMap<Int, Boolean>()
    
    // Total pending URLs count
    private val size = AtomicInteger(0)

    override suspend fun add(url: String) {
        val priority = prioritizer.getPriority(url).coerceIn(0, numFrontQueues - 1)
        frontQueues[priority].offer(url)
        size.incrementAndGet()
        routeFrontToBack()
    }

    override suspend fun addAll(urls: List<String>) {
        urls.forEach { add(it) }
    }

    private fun routeFrontToBack() {
        val maxDrainSize = 1000
        var count = 0
        
        var nextUrl = frontSelector.selectNext()
        while (nextUrl != null && count < maxDrainSize) {
            val backQueueIndex = backRouter.getQueueIndex(nextUrl)
            backQueues[backQueueIndex].offer(nextUrl)
            count++
            
            if (count < maxDrainSize) {
                nextUrl = frontSelector.selectNext()
            }
        }
    }

    // 3. Politeness Logic: Guarantee "one page at a time" downloading for a host mapped to its back queue
    override suspend fun getNext(): String? {
        routeFrontToBack()

        // Sync on the active queues lock structure to avoid thread collision over pulling from free queue
        synchronized(this) {
            for (i in 0 until numBackQueues) {
                if (activeQueues[i] != true && backQueues[i].isNotEmpty()) {
                    val url = backQueues[i].poll()
                    if (url != null) {
                        size.decrementAndGet()
                        // Queue assigned to current worker thread to preserve politeness rule
                        activeQueues[i] = true 
                        return url
                    }
                }
            }
        }
        return null
    }

    override suspend fun markCompleted(url: String) {
        val backQueueIndex = backRouter.getQueueIndex(url)
        synchronized(this) {
            activeQueues[backQueueIndex] = false
        }
        
        // Example check: using Recrawl Strategy upon completion to determine if refetch needed later
        if (recrawlStrategy.shouldRecrawl(url)) {
            // Can be added to a scheduler/delay map or simply back to queue
        }
    }

    override suspend fun isEmpty(): Boolean {
        return size.get() == 0
    }
}
