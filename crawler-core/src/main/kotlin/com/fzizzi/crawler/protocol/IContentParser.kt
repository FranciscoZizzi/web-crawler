package com.fzizzi.crawler.protocol

import com.fzizzi.crawler.model.HTMLContent

interface IContentParser {
    suspend fun parseAndValidate(html: HTMLContent): Boolean
}
