package com.fzizzi.crawler.downloader

interface DNSResolver {
    suspend fun resolve(domain: String): Result<String>
}
