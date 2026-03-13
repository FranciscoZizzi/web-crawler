package com.fzizzi.crawler.model

data class CrawlEvent(
    val url: String,
    val referrerUrls: List<String>,    // empty for seed URLs; may have multiple when discovered from several pages
    val html: HTMLContent,             // raw content + hash; re-parseable downstream
    val extractedLinks: List<String>,  // all outgoing links found on this page
    val ipAddress: String,
    val contentSizeBytes: Int = html.content.length,
    val crawledAtMs: Long = System.currentTimeMillis()
) {
    /** Convenience accessor for callers that only need a single referrer. */
    val referrerUrl: String? get() = referrerUrls.firstOrNull()
}
