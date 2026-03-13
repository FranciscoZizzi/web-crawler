package com.fzizzi.crawler.downloader

import com.fzizzi.crawler.model.RawContent
import kotlinx.coroutines.Deferred

interface DownloadDispatcher {
    suspend fun dispatch(url: String, ipAddress: String, timeoutMs: Long): Deferred<Result<RawContent>>
}
