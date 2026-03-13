package com.fzizzi.crawler.storage

import com.fzizzi.crawler.model.HTMLContent

interface ContentStorage {
    suspend fun isSeen(content: HTMLContent): Boolean
    suspend fun markSeen(content: HTMLContent)
}
