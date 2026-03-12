package com.fzizzi.crawler.protocol

import com.fzizzi.crawler.model.HTMLContent

interface IContentSeen {
    suspend fun isSeen(content: HTMLContent): Boolean
    suspend fun add(content: HTMLContent)
}
