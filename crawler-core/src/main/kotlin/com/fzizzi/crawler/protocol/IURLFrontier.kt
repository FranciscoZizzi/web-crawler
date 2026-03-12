package com.fzizzi.crawler.protocol

interface IURLFrontier {
    suspend fun add(url: String)
    suspend fun addAll(urls: List<String>)
    suspend fun getNext(): String?
    suspend fun markCompleted(url: String) {}
    suspend fun isEmpty(): Boolean
}
