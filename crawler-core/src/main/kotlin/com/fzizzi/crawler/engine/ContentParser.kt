package com.fzizzi.crawler.engine

import com.fzizzi.crawler.model.HTMLContent

interface ContentParser {
    suspend fun parseAndValidate(html: HTMLContent): Boolean
}
