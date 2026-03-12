package com.fzizzi.crawler.engine

import com.fzizzi.crawler.model.HTMLContent

interface IContentParser {
    suspend fun parseAndValidate(html: HTMLContent): Boolean
}
