package com.fzizzi.crawler.downloader

import kotlinx.coroutines.*
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.hours

class CachingDNSResolver(
    private val backgroundScope: CoroutineScope
) : DNSResolver {

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
                val address = InetAddress.getByName(domain).hostAddress
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