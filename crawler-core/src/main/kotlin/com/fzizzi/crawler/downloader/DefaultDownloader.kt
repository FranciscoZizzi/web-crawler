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

class RobotsCache(
    private val cacheTtlMs: Long = 24 * 60 * 60 * 1000L,  // 24 h
    private val userAgent: String = "*"
) {
    private data class CacheEntry(val disallowed: List<String>, val allowed: List<String>, val fetchedAtMs: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun isAllowed(url: String, domain: String): Boolean {
        val path = try {
            URI(url).path.let { if (it.isEmpty()) "/" else it }
        } catch (e: Exception) {
            "/"
        }

        val entry = getOrFetch(domain)

        // Longest-match wins: find the most specific rule that applies.
        // An Allow beats a Disallow of the same or shorter length.
        val bestDisallow = entry.disallowed.filter { path.startsWith(it) }.maxByOrNull { it.length }
        val bestAllow    = entry.allowed.filter { path.startsWith(it) }.maxByOrNull { it.length }

        return when {
            bestDisallow == null -> true  // no disallow rule matches → allowed
            bestAllow    == null -> false // disallow matches, no allow override → blocked
            else -> bestAllow.length >= bestDisallow.length // allow wins if at least as specific
        }
    }

    private suspend fun getOrFetch(domain: String): CacheEntry {
        val existing = cache[domain]
        if (existing != null && System.currentTimeMillis() - existing.fetchedAtMs < cacheTtlMs) {
            return existing
        }
        val fresh = fetchAndParse(domain)
        cache[domain] = fresh
        return fresh
    }

    private suspend fun fetchAndParse(domain: String): CacheEntry = withContext(Dispatchers.IO) {
        val disallowed = mutableListOf<String>()
        val allowed    = mutableListOf<String>()

        try {
            // Try HTTPS, fall back to HTTP if the connection itself fails (e.g. no TLS)
            val conn: java.net.HttpURLConnection = run {
                try {
                    val c = java.net.URL("https://$domain/robots.txt").openConnection() as java.net.HttpURLConnection
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; FzizziBot/1.0)")
                    c.connectTimeout = 3_000
                    c.readTimeout    = 3_000
                    c.connect()      // actually attempt the TLS handshake here
                    c
                } catch (e: Exception) {
                    // HTTPS failed (SSL, connection refused, etc.) — retry over plain HTTP
                    val c = java.net.URL("http://$domain/robots.txt").openConnection() as java.net.HttpURLConnection
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; FzizziBot/1.0)")
                    c.connectTimeout = 3_000
                    c.readTimeout    = 3_000
                    c
                }
            }

            if (conn.responseCode != 200) {
                // 404 / 410 → no restrictions; 5xx → fail-open
                return@withContext CacheEntry(emptyList(), emptyList(), System.currentTimeMillis())
            }

            val lines = conn.inputStream.bufferedReader().readLines()
            conn.disconnect()

            // Parse rules applicable to our user-agent or the wildcard agent
            var applicable = false
            for (rawLine in lines) {
                val line = rawLine.substringBefore('#').trim()
                when {
                    line.isBlank() -> applicable = false  // blank line ends a record
                    line.startsWith("User-agent:", ignoreCase = true) -> {
                        val agent = line.removePrefix("User-agent:").trim()
                        applicable = agent == "*" || agent.equals(userAgent, ignoreCase = true)
                    }
                    applicable && line.startsWith("Disallow:", ignoreCase = true) -> {
                        val p = line.removePrefix("Disallow:").trim()
                        if (p.isNotEmpty()) disallowed.add(p)
                    }
                    applicable && line.startsWith("Allow:", ignoreCase = true) -> {
                        val p = line.removePrefix("Allow:").trim()
                        if (p.isNotEmpty()) allowed.add(p)
                    }
                    // Crawl-delay and Sitemap are intentionally ignored
                }
            }
        } catch (e: Exception) {
            // Network error, malformed URL, etc. — fail-open (allow everything)
        }

        CacheEntry(disallowed, allowed, System.currentTimeMillis())
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
