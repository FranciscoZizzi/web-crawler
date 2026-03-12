package com.fzizzi.crawler.protocol

import com.fzizzi.crawler.model.HTMLContent

interface ILinkExtractor {
    suspend fun extract(content: HTMLContent): List<String>
}
