package com.fzizzi.crawler.storage

import com.fzizzi.crawler.model.RawContent
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class MemoryContentStorage : ContentStorage {
    private val seenHashes = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    override suspend fun isSeen(content: RawContent): Boolean {
        return seenHashes.contains(content.hash)
    }

    override suspend fun markSeen(content: RawContent) {
        seenHashes.add(content.hash)
    }
}
