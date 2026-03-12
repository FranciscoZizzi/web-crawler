package com.fzizzi.crawler.downloader

import com.fzizzi.crawler.model.HTMLContent
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.hours

class CachingDNSResolver(
    private val backgroundScope: CoroutineScope
) : IDNSResolver {
    
    // In-memory DNS cache
    private val dnsCache = ConcurrentHashMap<String, String>()

    init {
        // Periodic background updates to refresh the cache
        backgroundScope.launch {
            while (isActive) {
                delay(1.hours)
                refreshCache()
            }
        }
    }

    override suspend fun resolve(domain: String): Result<String> {
        // Attempt to get from cache first to avoid synchronous blocking call
        val cachedIp = dnsCache[domain]
        if (cachedIp != null) {
            return Result.success(cachedIp)
        }

        // If not in cache, resolve and cache it
        return withContext(Dispatchers.IO) {
            try {
                val address = InetAddress.getByName(domain).hostAddress // TODO use IDNSResolver
                dnsCache[domain] = address
                Result.success(address)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    private suspend fun refreshCache() = withContext(Dispatchers.IO) {
        // Iterate through known domains and refresh IPs
        dnsCache.keys().toList().forEach { domain ->
            try {
                val address = InetAddress.getByName(domain).hostAddress
                dnsCache[domain] = address
            } catch (e: Exception) {
                // Keep old IP on temporary failure
            }
        }
    }
}

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
    private val dispatcher: IDownloadDispatcher,
    private val robotsCache: RobotsCache,
    private val timeoutMs: Long = 5000L
) : IHTMLDownloader {

    override suspend fun download(url: String, ipAddress: String?): Result<HTMLContent> {
        if (ipAddress == null) {
            return Result.failure(IllegalArgumentException("IP Address is required"))
        }

        val domain = extractDomain(url)
        
        val isAllowed = robotsCache.isAllowed(url, domain)
        if (!isAllowed) {
            return Result.failure(IllegalStateException("URL is blocked by robots.txt"))
        }

        return try {
            withTimeout(timeoutMs) {
                val deferredResult = dispatcher.dispatch(url, ipAddress, timeoutMs)
                
                deferredResult.await()
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("Downloader timed out after $timeoutMs ms", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun extractDomain(url: String): String {
        return try {
            val withoutProtocol = url.substringAfter("://")
            withoutProtocol.substringBefore("/").substringBefore(":")
        } catch (e: Exception) {
            url
        }
    }
}
// TODO: idk where to put this but remember to make sure there is room to handle files, check the issues that can happen with url formatting and such
