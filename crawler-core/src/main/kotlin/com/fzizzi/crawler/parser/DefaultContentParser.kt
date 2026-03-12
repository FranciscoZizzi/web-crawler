package com.fzizzi.crawler.parser

import com.fzizzi.crawler.model.HTMLContent
import org.jsoup.Jsoup

class DefaultContentParser(
    private val minContentLength: Int = 100
) : ContentParser {

    override suspend fun parseAndValidate(html: HTMLContent): Boolean {
        val content = html.content

        // 1. Validate minimal data length (filter out "noise" / empty pages)
        if (content.length < minContentLength) {
            return false
        }

        // 2. Validate it's actually HTML (basic check)
        if (!content.contains("<html", ignoreCase = true) && 
            !content.contains("<!doctype html", ignoreCase = true)) {
            return false
        }

        // 3. Check for specific noise signatures (e.g., pure advertisement pages placeholder)
        if (content.contains("<meta name=\"robots\" content=\"noindex\"", ignoreCase = true)) {
            return false
        }

        // 4. Validate well-formedness using JSoup
        try {
            val doc = Jsoup.parse(content)
            val bodyText = doc.body()?.text() ?: ""
            if (bodyText.length < minContentLength) {
                return false
            }
        } catch (e: Exception) {
            return false
        }
        
        return true
    }
}
