package com.fzizzi.crawler.protocol

import com.fzizzi.crawler.model.HTMLContent
import kotlinx.coroutines.Deferred

interface IDownloadDispatcher {
    suspend fun dispatch(url: String, ipAddress: String, timeoutMs: Long): Deferred<Result<HTMLContent>>
}
