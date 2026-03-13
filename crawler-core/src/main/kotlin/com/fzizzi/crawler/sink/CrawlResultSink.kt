package com.fzizzi.crawler.sink

import com.fzizzi.crawler.model.CrawlEvent

interface CrawlResultSink {
    suspend fun accept(event: CrawlEvent)
}

object NoOpSink : CrawlResultSink {
    override suspend fun accept(event: CrawlEvent) = Unit
}
