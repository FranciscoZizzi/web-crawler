package com.fzizzi.crawler.storage

interface URLStorage {
    suspend fun isSeen(url: String): Boolean
    suspend fun markSeen(url: String)
}
