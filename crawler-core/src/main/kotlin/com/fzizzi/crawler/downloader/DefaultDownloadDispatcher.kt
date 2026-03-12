package com.fzizzi.crawler.downloader

import com.fzizzi.crawler.protocol.IDownloadDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import com.fzizzi.crawler.model.HTMLContent
import java.net.URL
import java.net.HttpURLConnection
import java.security.MessageDigest

// Simple data class to represent a download job
data class DownloadJob(
    val url: String,
    val ipAddress: String,
    val timeoutMs: Long,
    val result: CompletableDeferred<Result<HTMLContent>>
)

class DefaultDownloadDispatcher(
    private val workerScope: CoroutineScope,
    private val numWorkers: Int = 10
) : IDownloadDispatcher {

    // Using a Channel as a thread-safe queue for the workers
    private val jobChannel = Channel<DownloadJob>(Channel.UNLIMITED)

    init {
        // Start the worker coroutines
        for (i in 0 until numWorkers) {
            workerScope.launch {
                workerLoop(i)
            }
        }
    }

    private suspend fun workerLoop(workerId: Int) {
        // Each worker continuously processes jobs from the channel
        for (job in jobChannel) {
            try {
                // Actual HTTP request
                val content = withContext(Dispatchers.IO) {
                    val urlConn = URL(job.url).openConnection() as HttpURLConnection
                    urlConn.requestMethod = "GET"
                    urlConn.connectTimeout = job.timeoutMs.toInt()
                    urlConn.readTimeout = job.timeoutMs.toInt()
                    
                    val responseCode = urlConn.responseCode
                    if (responseCode in 200..299) {
                        val body = urlConn.inputStream.bufferedReader().use { it.readText() }
                        // Hash body to avoid checking dupes using the entire raw string length
                        val hashBytes = MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
                        val hash = hashBytes.joinToString("") { "%02x".format(it) }
                        
                        HTMLContent(job.url, body, hash)
                    } else {
                        throw Exception("HTTP Failed with code $responseCode")
                    }
                }
                job.result.complete(Result.success(content))
            } catch (e: Exception) {
                job.result.complete(Result.failure(e))
            }
        }
    }

    override suspend fun dispatch(url: String, ipAddress: String, timeoutMs: Long): Deferred<Result<HTMLContent>> {
        // Send the job to the channel for an available worker to pick up
        val deferred = CompletableDeferred<Result<HTMLContent>>()
        val job = DownloadJob(url, ipAddress, timeoutMs, deferred)
        jobChannel.send(job)
        return deferred
    }
}
