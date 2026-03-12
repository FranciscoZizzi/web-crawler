package com.fzizzi.crawler.engine

interface URLFilter {
    suspend fun isAllowed(url: String): Boolean
}
