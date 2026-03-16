package com.fzizzi.crawler.extractor

import com.fzizzi.crawler.extractor.exceptions.InvalidHtmlContentException
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

    override suspend fun handle(content: RawContent): Result<HandlerResult> {
        val htmlContent = HTMLContent(content.url, content.text, content.hash)

        contentParser.parseAndValidate(htmlContent).onFailure { e -> return Result.failure(e) }

        val links = extractLinks(htmlContent)

        return Result.success(
            HandlerResult(
                discoveredLinks = links,
                extractedMetadata = mapOf("html_hash" to content.hash)
            )
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
    suspend fun parseAndValidate(html: HTMLContent): Result<Unit>
}

class DefaultContentParser(
    private val minContentLength: Int = 100
) : ContentParser {

    override suspend fun parseAndValidate(html: HTMLContent): Result<Unit> {
        val content = html.content

        if (content.length < minContentLength) {
            return Result.failure(InvalidHtmlContentException("Content too short for url: ${html.url}. Length is ${content.length}, should be at least $minContentLength"))
        }

        if (!content.contains("<html", ignoreCase = true) &&
            !content.contains("<!doctype html", ignoreCase = true)) {
            return Result.failure(InvalidHtmlContentException("Invalid html for url: ${html.url}"))
        }

        try {
            val doc = Jsoup.parse(content)
            val bodyText = doc.body().text() ?: ""
            if (bodyText.length < minContentLength) {
                return Result.failure(InvalidHtmlContentException("Content too short for url: ${html.url}. Length is ${bodyText.length}, should be at least $minContentLength"))
            }
        } catch (e: Exception) {
            return Result.failure(InvalidHtmlContentException("Error parsing html for url: ${html.url}: ${e.message}"))
        }

        return Result.success(Unit);
    }
}