package com.fzizzi.crawler.downloader

import com.fzizzi.crawler.model.HTMLContent

interface HTMLDownloader {
    suspend fun download(url: String, ipAddress: String?): Result<HTMLContent>
}
