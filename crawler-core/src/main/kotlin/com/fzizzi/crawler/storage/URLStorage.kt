package com.fzizzi.crawler.storage

interface URLStorage {
    suspend fun isSeen(url: String): Boolean
    suspend fun add(url: String)
}
