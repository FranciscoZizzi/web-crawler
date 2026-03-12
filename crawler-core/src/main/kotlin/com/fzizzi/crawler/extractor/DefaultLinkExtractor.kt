package com.fzizzi.crawler.extractor

import com.fzizzi.crawler.model.HTMLContent
import org.jsoup.Jsoup

class DefaultLinkExtractor : LinkExtractor {
    override suspend fun extract(content: HTMLContent): List<String> {
        return try {
            val doc = Jsoup.parse(content.content, content.url)
            doc.select("a[href]").map { it.attr("abs:href") }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
