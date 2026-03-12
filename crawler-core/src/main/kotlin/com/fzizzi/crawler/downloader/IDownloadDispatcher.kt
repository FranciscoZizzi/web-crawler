package com.fzizzi.crawler.downloader

import com.fzizzi.crawler.model.HTMLContent
import kotlinx.coroutines.Deferred

interface IDownloadDispatcher {
    suspend fun dispatch(url: String, ipAddress: String, timeoutMs: Long): Deferred<Result<HTMLContent>>
}
