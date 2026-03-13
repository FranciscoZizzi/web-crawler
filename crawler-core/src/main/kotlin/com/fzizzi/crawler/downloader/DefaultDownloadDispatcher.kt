package com.fzizzi.crawler.downloader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import com.fzizzi.crawler.model.HTMLContent
import com.fzizzi.crawler.model.RawContent
import java.net.URL
import java.net.HttpURLConnection
import java.security.MessageDigest

data class DownloadJob(
    val url: String,
    val ipAddress: String,
    val timeoutMs: Long,
    val result: CompletableDeferred<Result<RawContent>>
)

class DefaultDownloadDispatcher(
    private val workerScope: CoroutineScope,
    private val numWorkers: Int = 10,
    private val maxSizeBytes: Int = 10 * 1024 * 1024
) : DownloadDispatcher {

    private val jobChannel = Channel<DownloadJob>(Channel.UNLIMITED)

    init {
        for (i in 0 until numWorkers) {
            workerScope.launch {
                workerLoop(i)
            }
        }
    }

    private suspend fun workerLoop(workerId: Int) {
        for (job in jobChannel) {
            try {
                val content = withContext(Dispatchers.IO) {
                    val originalUrl = URL(job.url)
                    val isHttps = originalUrl.protocol.equals("https", ignoreCase = true)
                    
                    val urlConn = if (isHttps) {
                        originalUrl.openConnection() as HttpURLConnection
                    } else {
                        val port = if (originalUrl.port == -1) originalUrl.defaultPort else originalUrl.port
                        URL(originalUrl.protocol, job.ipAddress, port, originalUrl.file).openConnection() as HttpURLConnection
                    }
                    urlConn.requestMethod = "GET"
                    
                    val hostHeader = if (originalUrl.port == -1) originalUrl.host else "${originalUrl.host}:${originalUrl.port}"
                    urlConn.setRequestProperty("Host", hostHeader)
                    urlConn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; FzizziBot/1.0; +https://github.com/fzizzi/web-crawler)")
                    
                    urlConn.connectTimeout = job.timeoutMs.toInt()
                    urlConn.readTimeout = job.timeoutMs.toInt()
                    
                    val responseCode = urlConn.responseCode
                    if (responseCode in 200..299) {
                        val contentLength = urlConn.contentLengthLong
                        if (contentLength > maxSizeBytes) {
                            throw Exception("Content length $contentLength exceeds limit of $maxSizeBytes bytes")
                        }
                        
                        val bodyBytes = urlConn.inputStream.use { input ->
                            val bytes = input.readNBytes(maxSizeBytes)
                            if (input.read() != -1) {
                                throw Exception("Stream exceeded maximum allowed size of $maxSizeBytes bytes")
                            }
                            bytes
                        }
                        
                        val hashBytes = MessageDigest.getInstance("SHA-256").digest(bodyBytes)
                        val hash = hashBytes.joinToString("") { "%02x".format(it) }

                        val contentType = urlConn.contentType ?: "application/octet-stream"
                        val headers = urlConn.headerFields
                            .filterKeys { it != null }
                            .mapValues { it.value.joinToString(", ") }

                        RawContent(job.url, contentType, bodyBytes, hash, headers)
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

    override suspend fun dispatch(url: String, ipAddress: String, timeoutMs: Long): Deferred<Result<RawContent>> {
        val deferred = CompletableDeferred<Result<RawContent>>()
        val job = DownloadJob(url, ipAddress, timeoutMs, deferred)
        jobChannel.send(job)
        return deferred
    }
}
