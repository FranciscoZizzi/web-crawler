package com.fzizzi.crawler.protocol

interface IDNSResolver {
    suspend fun resolve(domain: String): Result<String>
}
