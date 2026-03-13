package com.fzizzi.crawler.model

import com.fzizzi.crawler.model.HTMLContent

data class CrawlEvent(
    val url: String,
    val referrerUrl: String?,         // null for seed URLs
    val html: HTMLContent,            // raw content + hash; re-parseable downstream
    val extractedLinks: List<String>, // all outgoing links found on this page
    val ipAddress: String,
    val contentSizeBytes: Int = html.content.length,
    val crawledAtMs: Long = System.currentTimeMillis()
)
