package com.fzizzi.crawler.protocol

import com.fzizzi.crawler.model.HTMLContent

interface IHTMLDownloader {
    suspend fun download(url: String, ipAddress: String?): Result<HTMLContent>
}
