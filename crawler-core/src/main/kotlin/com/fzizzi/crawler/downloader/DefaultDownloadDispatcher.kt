package com.fzizzi.crawler.downloader

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
    private val numWorkers: Int = 10,
    private val maxSizeBytes: Int = 10 * 1024 * 1024
) : DownloadDispatcher {

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
                    val originalUrl = URL(job.url)
                    val isHttps = originalUrl.protocol.equals("https", ignoreCase = true)
                    
                    // For HTTPS we must use the hostname — TLS SNI & cert validation require it.
                    // For plain HTTP we can connect directly to the resolved IP to bypass OS-level DNS.
                    val urlConn = if (isHttps) {
                        originalUrl.openConnection() as HttpURLConnection
                    } else {
                        val port = if (originalUrl.port == -1) originalUrl.defaultPort else originalUrl.port
                        URL(originalUrl.protocol, job.ipAddress, port, originalUrl.file).openConnection() as HttpURLConnection
                    }
                    urlConn.requestMethod = "GET"
                    
                    // Force the Host header so the remote server knows which virtual host we want
                    val hostHeader = if (originalUrl.port == -1) originalUrl.host else "${originalUrl.host}:${originalUrl.port}"
                    urlConn.setRequestProperty("Host", hostHeader)
                    urlConn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; FzizziBot/1.0; +https://github.com/fzizzi/web-crawler)")
                    
                    urlConn.connectTimeout = job.timeoutMs.toInt()
                    urlConn.readTimeout = job.timeoutMs.toInt()
                    
                    val responseCode = urlConn.responseCode
                    if (responseCode in 200..299) {
                        // Guard against massive streams by reading up to the limit natively
                        val contentLength = urlConn.contentLengthLong
                        if (contentLength > maxSizeBytes) {
                            throw Exception("Content length $contentLength exceeds limit of $maxSizeBytes bytes")
                        }
                        
                        val bodyBytes = urlConn.inputStream.use { input ->
                            val bytes = input.readNBytes(maxSizeBytes)
                            if (input.read() != -1) { // Stream still has more bytes despite reaching cap
                                throw Exception("Stream exceeded maximum allowed size of $maxSizeBytes bytes")
                            }
                            bytes
                        }
                        
                        val body = String(bodyBytes, Charsets.UTF_8)
                        
                        // Hash body to avoid checking dupes using the entire raw string length
                        val hashBytes = MessageDigest.getInstance("SHA-256").digest(bodyBytes)
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
