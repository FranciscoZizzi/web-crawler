package com.fzizzi.crawler.engine

import com.fzizzi.crawler.downloader.DNSResolver
import com.fzizzi.crawler.downloader.HTMLDownloader
import com.fzizzi.crawler.frontier.URLFrontier
import com.fzizzi.crawler.extractor.LinkExtractor
import com.fzizzi.crawler.logging.CrawlerLogger
import com.fzizzi.crawler.logging.LogCategory
import com.fzizzi.crawler.logging.NoOpLogger
import com.fzizzi.crawler.model.CrawlEvent
import com.fzizzi.crawler.parser.ContentParser
import com.fzizzi.crawler.sink.CrawlResultSink
import com.fzizzi.crawler.sink.NoOpSink
import com.fzizzi.crawler.storage.ContentStorage
import com.fzizzi.crawler.storage.URLStorage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val sink: CrawlResultSink = NoOpSink,
    private val sinkChannelCapacity: Int = 500,
    private val logger: CrawlerLogger = NoOpLogger
) {
    suspend fun start(seeds: List<String>) = coroutineScope {
        logger.info(LogCategory.ORCHESTRATOR, "Starting crawl with ${seeds.size} seed(s): ${seeds.joinToString()}")
        urlFrontier.addAll(seeds)

        // Bounded channel decouples the fast crawler from the potentially slow sink.
        // The orchestrator fire-and-forgets events; a dedicated coroutine drains them.
        val eventChannel = Channel<CrawlEvent>(sinkChannelCapacity)

        // Sink consumer — runs independently, never blocks the crawl loop
        val sinkJob = launch {
            for (event in eventChannel) {
                try {
                    sink.accept(event)
                } catch (e: Exception) {
                    logger.error(LogCategory.ORCHESTRATOR, "Sink failed for ${event.url}", e)
                }
            }
        }

        // Track referrer for discovered links (seeds have no referrer, so they're simply absent from the map)
        val referrerMap = java.util.concurrent.ConcurrentHashMap<String, String>()
        // seeds are intentionally not added — absence == seed

        while (isActive) {
            val batchUrls = mutableListOf<String>()
            val batchSize = 10

            for (i in 0 until batchSize) {
                val nextUrl = urlFrontier.getNext()
                if (nextUrl != null) batchUrls.add(nextUrl) else break
            }

            if (batchUrls.isEmpty()) {
                if (urlFrontier.isEmpty()) break else { delay(100); continue }
            }

            val deferreds = batchUrls.map { currentUrl ->
                async {
                    try {
                        val domain = extractDomain(currentUrl)

                        if (clusterRouter != null && !clusterRouter.isLocal(domain)) {
                            logger.debug(LogCategory.CLUSTER, "Skipping $currentUrl — owned by another node")
                            return@async
                        }

                        val ipAddress = dnsResolver.resolve(domain).getOrElse {
                            logger.warn(LogCategory.DNS, "DNS resolution failed for $domain: ${it.message}")
                            return@async
                        }

                        logger.debug(LogCategory.DOWNLOADER, "Downloading $currentUrl")
                        val htmlContent = htmlDownloader.download(currentUrl, ipAddress).getOrElse {
                            logger.warn(LogCategory.DOWNLOADER, "Download failed for $currentUrl: ${it.message}")
                            return@async
                        }

                        val isValid = contentParser.parseAndValidate(htmlContent)
                        if (!isValid) {
                            logger.debug(LogCategory.PARSER, "Content rejected for $currentUrl")
                            return@async
                        }

                        if (contentStorage.isSeen(htmlContent)) {
                            logger.debug(LogCategory.STORAGE, "Duplicate content skipped for $currentUrl")
                            return@async
                        }
                        contentStorage.markSeen(htmlContent)
                        logger.info(LogCategory.ORCHESTRATOR, "Crawled $currentUrl (${htmlContent.content.length / 1024}KB)")

                        val extractedLinks = linkExtractor.extract(htmlContent) // TODO add extension module to allow PNG downloader, Web Monitor, etc.

                        val newUrls = mutableListOf<String>()
                        for (link in extractedLinks) {
                            if (!urlFilter.isAllowed(link)) continue
                            if (!urlStorage.isSeen(link)) {
                                urlStorage.add(link)
                                newUrls.add(link)
                                referrerMap[link] = currentUrl // track who discovered this link
                            }
                        }

                        if (newUrls.isNotEmpty()) { // TODO investigate how to avoid adding the same url twice
                            logger.debug(LogCategory.EXTRACTOR, "Discovered ${newUrls.size} new URL(s) from $currentUrl")
                            urlFrontier.addAll(newUrls)
                        }

                        // Emit to sink via channel — fire-and-forget, never blocks crawl
                        val event = CrawlEvent(
                            url = currentUrl,
                            referrerUrl = referrerMap[currentUrl],
                            html = htmlContent,
                            extractedLinks = newUrls,
                            ipAddress = ipAddress
                        )
                        eventChannel.send(event)

                    } finally {
                        urlFrontier.markCompleted(currentUrl)
                        referrerMap.remove(currentUrl)
                    }
                }
            }
            deferreds.awaitAll()
        }

        eventChannel.close()  // signal sink consumer to finish draining
        sinkJob.join()         // wait for all events to be processed before returning
    }

    private fun extractDomain(url: String): String {
        return try {
            java.net.URL(url).host
        } catch (e: Exception) {
            url
        }
    }
}
