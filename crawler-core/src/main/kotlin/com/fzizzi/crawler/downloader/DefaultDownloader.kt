package com.fzizzi.crawler.downloader

import com.fzizzi.crawler.logging.CrawlerLogger
import com.fzizzi.crawler.logging.LogCategory
import com.fzizzi.crawler.logging.NoOpLogger
import com.fzizzi.crawler.model.HTMLContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class RobotsCache {
    // domain -> List of disallowed paths
    private val cache = ConcurrentHashMap<String, List<String>>()

    suspend fun isAllowed(url: String, domain: String): Boolean {
        val path = try {
            URI(url).path.takeIf { it.isNotEmpty() } ?: "/"
        } catch (e: Exception) {
            "/" // TODO see if it's the correct way to handle, do the same with any try catch block
        }

        var disallowedPaths = cache[domain]
        if (disallowedPaths == null) {
            disallowedPaths = fetchRobotsTxt(domain)
            cache[domain] = disallowedPaths
        }

        return disallowedPaths.none { path.startsWith(it) }
    }

    private suspend fun fetchRobotsTxt(domain: String): List<String> = withContext(Dispatchers.IO) {
        // TODO implement
        // Placeholder implementation for fetching and parsing robots.txt
        // In a real implementation this would make an HTTP request to http://$domain/robots.txt
        val disallowed = mutableListOf<String>()
        // Default to allow everything for this prototype
        disallowed
    }
}

class DefaultDownloader(
    private val dispatcher: DownloadDispatcher,
    private val robotsCache: RobotsCache,
    private val timeoutMs: Long = 5000L,
    private val logger: CrawlerLogger = NoOpLogger
) : HTMLDownloader {

    override suspend fun download(url: String, ipAddress: String?): Result<HTMLContent> {
        if (ipAddress == null) {
            return Result.failure(IllegalArgumentException("IP Address is required"))
        }

        val domain = extractDomain(url)
        
        val isAllowed = robotsCache.isAllowed(url, domain)
        if (!isAllowed) {
            logger.info(LogCategory.DOWNLOADER, "Blocked by robots.txt: $url")
            return Result.failure(IllegalStateException("URL is blocked by robots.txt"))
        }

        return try {
            withTimeout(timeoutMs) {
                val deferredResult = dispatcher.dispatch(url, ipAddress, timeoutMs)
                
                deferredResult.await()
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn(LogCategory.DOWNLOADER, "Timeout after ${timeoutMs}ms for $url")
            Result.failure(Exception("Downloader timed out after $timeoutMs ms", e))
        } catch (e: Exception) {
            logger.error(LogCategory.DOWNLOADER, "Unexpected error downloading $url", e)
            Result.failure(e)
        }
    }
    
    private fun extractDomain(url: String): String {
        return try {
            java.net.URL(url).authority
        } catch (e: Exception) {
            url
        }
    }
}
// TODO: idk where to put this but remember to make sure there is room to handle files, check the issues that can happen with url formatting and such
