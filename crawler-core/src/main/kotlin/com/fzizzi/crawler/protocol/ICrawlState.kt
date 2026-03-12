package com.fzizzi.crawler.protocol

interface ICrawlState {
    suspend fun saveFrontierState(urls: List<String>)
    suspend fun saveSeenUrls(urls: Set<String>)
    
    suspend fun loadFrontierState(): List<String>
    suspend fun loadSeenUrls(): Set<String>
    
    suspend fun clearState()
}
