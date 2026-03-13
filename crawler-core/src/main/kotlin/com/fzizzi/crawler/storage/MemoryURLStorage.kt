package com.fzizzi.crawler.storage

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class MemoryURLStorage : URLStorage {
    private val seenSet = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    override suspend fun isSeen(url: String): Boolean {
        return seenSet.contains(url)
    }

    override suspend fun markSeen(url: String) {
        seenSet.add(url)
    }
}
