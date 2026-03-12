package com.fzizzi.crawler.engine

import com.fzizzi.crawler.downloader.DNSResolver
import com.fzizzi.crawler.downloader.HTMLDownloader
import com.fzizzi.crawler.frontier.URLFrontier
import com.fzizzi.crawler.extractor.LinkExtractor
import com.fzizzi.crawler.parser.ContentParser
import com.fzizzi.crawler.storage.ContentSeen
import com.fzizzi.crawler.storage.URLSeen
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield

class CrawlerOrchestrator(
    private val urlFrontier: URLFrontier,
    private val htmlDownloader: HTMLDownloader,
    private val dnsResolver: DNSResolver,
    private val contentParser: ContentParser,
    private val contentSeen: ContentSeen,
    private val linkExtractor: LinkExtractor,
    private val urlFilter: URLFilter,
    private val urlSeen: URLSeen
) {
    suspend fun start(seeds: List<String>) = coroutineScope {
        // Step 1: Add seeds to Frontier
        urlFrontier.addAll(seeds)

        // Main single-threaded-loop representation
        while (isActive && !urlFrontier.isEmpty()) {
            // Step 2: Fetch next URL from Frontier
            val currentUrl: String = urlFrontier.getNext() ?: break

            try {
                // Step 3: Resolve DNS and Download
            val domain: String = extractDomain(currentUrl)
            val ipAddressResult: Result<String> = dnsResolver.resolve(domain)
            val ipAddress = ipAddressResult.getOrNull() ?: continue // TODO handle error

            val downloadResult = htmlDownloader.download(currentUrl, ipAddress)
            val htmlContent = downloadResult.getOrNull() ?: continue // TODO handle error

            // Step 4: Parse and validate HTML
            val isValid = contentParser.parseAndValidate(htmlContent)
            if (!isValid) {
                // TODO handle error
                continue
            }

            // Step 5 & 6: Check Content Seen? and store if not seen
            if (contentSeen.isSeen(htmlContent)) {
                continue
            }
            contentSeen.add(htmlContent)

            // Step 7: Extract links
            val extractedLinks = linkExtractor.extract(htmlContent)

            val newUrls = mutableListOf<String>()

            for (link in extractedLinks) {
                // Step 8: Apply URL Filters
                if (!urlFilter.isAllowed(link)) {
                    continue
                }

                // Step 9, 10 & 11: Check if URL Seen?, if not add to URL Seen? and Frontier
                if (!urlSeen.isSeen(link)) {
                    urlSeen.add(link)
                    newUrls.add(link)
                }
            }

            // Add new URLs back to the Frontier (Step 11 continued)
            if (newUrls.isNotEmpty()) {
                urlFrontier.addAll(newUrls)
            }
            } finally {
                urlFrontier.markCompleted(currentUrl)
            }
            
            yield()
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
