package com.fzizzi.crawler.storage

interface IURLSeen {
    suspend fun isSeen(url: String): Boolean
    suspend fun add(url: String)
}
