package com.fzizzi.crawler.model

data class CrawlEvent(
    val url: String,
    val referrerUrls: List<String>,
    val rawContent: RawContent,
    val extractedLinks: List<String>,
    val ipAddress: String,
    val metadata: Map<String, Any> = emptyMap(),
    val contentSizeBytes: Int = rawContent.bytes.size,
    val crawledAtMs: Long = System.currentTimeMillis()
)

