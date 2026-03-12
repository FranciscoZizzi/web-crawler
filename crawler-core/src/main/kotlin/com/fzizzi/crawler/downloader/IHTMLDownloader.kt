package com.fzizzi.crawler.downloader

import com.fzizzi.crawler.model.HTMLContent

interface IHTMLDownloader {
    suspend fun download(url: String, ipAddress: String?): Result<HTMLContent>
}
