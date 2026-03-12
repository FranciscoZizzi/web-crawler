package com.fzizzi.crawler.protocol

interface IMessageDispatcher {
    suspend fun dispatch(url: String, ipAddress: String, timeoutMs: Long)
}
