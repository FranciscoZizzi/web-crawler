package com.fzizzi.crawler.parser

import com.fzizzi.crawler.model.HTMLContent
import org.jsoup.Jsoup

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
