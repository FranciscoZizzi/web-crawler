package com.fzizzi.crawler.extractor

import com.fzizzi.crawler.model.HTMLContent
import com.fzizzi.crawler.model.RawContent
import org.jsoup.Jsoup

class HtmlHandler(
    private val contentParser: ContentParser,
) : ContentHandler {
    override val id: String = "html-handler"

    override fun canHandle(contentType: String): Boolean {
        return contentType.contains("text/html", ignoreCase = true) ||
               contentType.contains("application/xhtml+xml", ignoreCase = true)
    }

    override suspend fun handle(content: RawContent): HandlerResult {
        val htmlContent = HTMLContent(content.url, content.text, content.hash)
        
        val isValid = contentParser.parseAndValidate(htmlContent)
        if (!isValid) return HandlerResult()

        val links = extractLinks(htmlContent)
        
        return HandlerResult(
            discoveredLinks = links,
            extractedMetadata = mapOf("html_hash" to content.hash)
        )
    }

    private fun extractLinks(content: HTMLContent): List<String> {
        return try {
            val doc = Jsoup.parse(content.content, content.url)
            doc.select("a[href]").map { it.attr("abs:href") }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

}

interface ContentParser {
    suspend fun parseAndValidate(html: HTMLContent): Boolean
}

class DefaultContentParser(
    private val minContentLength: Int = 100
) : ContentParser {

    override suspend fun parseAndValidate(html: HTMLContent): Boolean {
        val content = html.content

        if (content.length < minContentLength) {
            return false
        }

        if (!content.contains("<html", ignoreCase = true) &&
            !content.contains("<!doctype html", ignoreCase = true)) {
            return false
        }

        if (content.contains("<meta name=\"robots\" content=\"noindex\"", ignoreCase = true)) {
            return false
        }

        try {
            val doc = Jsoup.parse(content)
            val bodyText = doc.body().text() ?: ""
            if (bodyText.length < minContentLength) {
                return false
            }
        } catch (e: Exception) {
            return false
        }

        return true
    }
}