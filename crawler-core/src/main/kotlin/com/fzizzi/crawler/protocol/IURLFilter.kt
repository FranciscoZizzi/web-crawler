package com.fzizzi.crawler.protocol

interface IURLFilter {
    suspend fun isAllowed(url: String): Boolean
}
