package com.fzizzi.crawler.extractor

import com.fzizzi.crawler.model.HTMLContent

interface LinkExtractor {
    suspend fun extract(content: HTMLContent): List<String>
}
