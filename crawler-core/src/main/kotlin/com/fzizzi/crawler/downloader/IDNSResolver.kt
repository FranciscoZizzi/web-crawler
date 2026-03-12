package com.fzizzi.crawler.downloader

interface IDNSResolver {
    suspend fun resolve(domain: String): Result<String>
}
