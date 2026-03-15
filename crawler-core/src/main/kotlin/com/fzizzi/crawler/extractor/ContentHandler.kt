package com.fzizzi.crawler.extractor

import com.fzizzi.crawler.model.RawContent

interface ContentHandler {
    fun canHandle(contentType: String): Boolean

    suspend fun handle(content: RawContent): HandlerResult
}

data class HandlerResult(
    val discoveredLinks: List<String> = emptyList(),
    val extractedMetadata: Map<String, Any> = emptyMap()
)
