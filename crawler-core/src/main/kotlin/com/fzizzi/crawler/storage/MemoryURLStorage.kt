package com.fzizzi.crawler.storage

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class MemoryURLStorage : URLStorage {
    private val seenUrls = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    override suspend fun isSeen(url: String): Boolean {
        return seenUrls.contains(url)
    }

    override suspend fun markSeen(url: String) {
        seenUrls.add(url)
    }
}
