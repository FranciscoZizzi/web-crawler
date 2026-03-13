package com.fzizzi.crawler.storage

import com.fzizzi.crawler.model.RawContent

interface ContentStorage {
    suspend fun isSeen(content: RawContent): Boolean
    suspend fun markSeen(content: RawContent)
}
