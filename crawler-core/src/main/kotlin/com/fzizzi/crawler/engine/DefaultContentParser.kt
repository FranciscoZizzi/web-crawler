package com.fzizzi.crawler.engine

import com.fzizzi.crawler.model.HTMLContent

class DefaultContentParser(
    private val minContentLength: Int = 100
) : IContentParser {

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

        // 4. Validate well-formedness placeholder
        // TODO
        // In a real implementation this would use JSoup or similar to parse the DOM tree
        // and extract meaningful text, discarding scripts, styles, and ads
        
        return true
    }
}
