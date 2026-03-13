package com.fzizzi.crawler.engine

import com.fzizzi.crawler.downloader.DNSResolver
import com.fzizzi.crawler.downloader.HTMLDownloader
import com.fzizzi.crawler.frontier.URLFrontier
import com.fzizzi.crawler.extractor.LinkExtractor
import com.fzizzi.crawler.parser.ContentParser
import com.fzizzi.crawler.storage.ContentStorage
import com.fzizzi.crawler.storage.URLStorage
import com.fzizzi.crawler.logging.CrawlerLogger
import com.fzizzi.crawler.logging.LogCategory
import com.fzizzi.crawler.logging.LogLevel
import com.fzizzi.crawler.logging.NoOpLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay

class CrawlerOrchestrator(
    private val urlFrontier: URLFrontier,
    private val htmlDownloader: HTMLDownloader,
    private val dnsResolver: DNSResolver,
    private val contentParser: ContentParser,
    private val contentStorage: ContentStorage,
    private val linkExtractor: LinkExtractor,
    private val urlFilter: URLFilter,
    private val urlStorage: URLStorage,
    private val clusterRouter: ConsistentHashRouter<String>? = null,
    private val logger: CrawlerLogger = NoOpLogger
) {
    suspend fun start(seeds: List<String>) = coroutineScope {
        // Step 1: Add seeds to Frontier
        logger.info(LogCategory.ORCHESTRATOR, "Starting crawl with ${seeds.size} seed(s): ${seeds.joinToString()}")
        urlFrontier.addAll(seeds)

        // Main multi-threaded loop representation
        while (isActive) {
            val batchUrls = mutableListOf<String>()
            val batchSize = 10
            
            for (i in 0 until batchSize) {
                val nextUrl = urlFrontier.getNext()
                if (nextUrl != null) {
                    batchUrls.add(nextUrl)
                } else {
                    break
                }
            }
            
            if (batchUrls.isEmpty()) {
                if (urlFrontier.isEmpty()) {
                    break // We evaluate if frontier is totally empty then we are done
                } else {
                    delay(100) // Sleep if waiting on workers
                    continue
                }
            }

            // Process the batch concurrently
            val deferreds = batchUrls.map { currentUrl ->
                async {
                    try {
                        // Step 3: Resolve DNS and Download
                        val domain: String = extractDomain(currentUrl)
                        
                        if (clusterRouter != null && !clusterRouter.isLocal(domain)) {
                            logger.debug(LogCategory.CLUSTER, "Skipping $currentUrl — owned by another node")
                            return@async // Skip, another cluster node owns this domain
                        }
                        
                        val ipAddressResult: Result<String> = dnsResolver.resolve(domain)
                        val ipAddress = ipAddressResult.getOrElse {
                            logger.warn(LogCategory.DNS, "DNS resolution failed for $domain: ${it.message}")
                            return@async
                        }

                        logger.debug(LogCategory.DOWNLOADER, "Downloading $currentUrl")
                        val downloadResult = htmlDownloader.download(currentUrl, ipAddress)
                        val htmlContent = downloadResult.getOrElse {
                            logger.warn(LogCategory.DOWNLOADER, "Download failed for $currentUrl: ${it.message}")
                            return@async
                        }

                        // Step 4: Parse and validate HTML
                        val isValid = contentParser.parseAndValidate(htmlContent)
                        if (!isValid) {
                            logger.debug(LogCategory.PARSER, "Content rejected for $currentUrl")
                            return@async
                        }

                        // Step 5 & 6: Check Content Seen? and store if not seen
                        if (contentStorage.isSeen(htmlContent)) {
                            logger.debug(LogCategory.STORAGE, "Duplicate content skipped for $currentUrl")
                            return@async
                        }
                        contentStorage.add(htmlContent)
                        logger.info(LogCategory.ORCHESTRATOR, "Crawled $currentUrl (${htmlContent.content.length / 1024}KB)")

                        // Step 7: Extract links
                        val extractedLinks = linkExtractor.extract(htmlContent) // TODO add extension module to allow PNG downloader, Web Monitor, etc.

                        val newUrls = mutableListOf<String>()

                        for (link in extractedLinks) {
                            // Step 8: Apply URL Filters
                            if (!urlFilter.isAllowed(link)) continue

                            // Step 9, 10 & 11: Check if URL Seen?, if not add to URL Seen? and Frontier
                            if (!urlStorage.isSeen(link)) {
                                urlStorage.add(link)
                                newUrls.add(link)
                            }
                        }

                        // Add new URLs back to the Frontier
                        if (newUrls.isNotEmpty()) { // TODO investigate how to avoid adding the same url twice
                            logger.debug(LogCategory.EXTRACTOR, "Discovered ${newUrls.size} new URL(s) from $currentUrl")
                            urlFrontier.addAll(newUrls)
                        }
                    } finally {
                        urlFrontier.markCompleted(currentUrl)
                    }
                }
            }
            deferreds.awaitAll()
        }
    }

    private fun extractDomain(url: String): String {
        return try {
            java.net.URL(url).host
        } catch (e: Exception) {
            url
        }
    }
}
