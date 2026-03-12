package com.fzizzi.crawler.engine

interface IURLFilter {
    suspend fun isAllowed(url: String): Boolean
}
