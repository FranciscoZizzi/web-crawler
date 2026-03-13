package com.fzizzi.crawler.downloader

import com.fzizzi.crawler.model.RawContent

interface HTMLDownloader {
    suspend fun download(url: String, ipAddress: String?): Result<RawContent>
}
