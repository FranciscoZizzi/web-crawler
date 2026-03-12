package com.fzizzi.crawler.parser

import com.fzizzi.crawler.model.HTMLContent

interface ContentParser {
    suspend fun parseAndValidate(html: HTMLContent): Boolean
}
