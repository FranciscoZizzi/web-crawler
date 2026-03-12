package com.fzizzi.crawler.protocol

interface IURLSeen {
    suspend fun isSeen(url: String): Boolean
    suspend fun add(url: String)
}
