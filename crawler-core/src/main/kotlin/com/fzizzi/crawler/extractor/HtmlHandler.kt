package com.fzizzi.crawler.extractor

import com.fzizzi.crawler.model.HTMLContent
import com.fzizzi.crawler.model.RawContent
import com.fzizzi.crawler.parser.ContentParser

/**
 * A ContentHandler that handles HTML content, validates it, and extracts links.
 */
class HtmlHandler(
    private val contentParser: ContentParser,
    private val linkExtractor: LinkExtractor
) : ContentHandler {
    override val id: String = "html-handler"

    override fun canHandle(contentType: String): Boolean {
        // Handle common HTML content types
        return contentType.contains("text/html", ignoreCase = true) || 
               contentType.contains("application/xhtml+xml", ignoreCase = true)
    }

    override suspend fun handle(content: RawContent): HandlerResult {
        // Convert RawContent to HTMLContent for existing logic
        val htmlContent = HTMLContent(content.url, content.text, content.hash)
        
        val isValid = contentParser.parseAndValidate(htmlContent)
        if (!isValid) return HandlerResult()

        val links = linkExtractor.extract(htmlContent)
        
        return HandlerResult(
            discoveredLinks = links,
            extractedMetadata = mapOf("html_hash" to content.hash)
        )
    }
}
