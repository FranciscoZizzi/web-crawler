package com.fzizzi.crawler.storage

import com.fzizzi.crawler.model.HTMLContent

interface ContentSeen {
    suspend fun isSeen(content: HTMLContent): Boolean
    suspend fun add(content: HTMLContent)
}
