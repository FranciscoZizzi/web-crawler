package com.fzizzi.crawler.model

data class RawContent (
    val url: String,
    val contentType: String,
    val bytes: ByteArray,
    val hash: String,
    val metadata: Map<String, String> = emptyMap()
) {
    val text: String by lazy { String(bytes) }
}