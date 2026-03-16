package com.fzizzi.crawler.engine

import com.fzizzi.crawler.downloader.DNSResolver
import com.fzizzi.crawler.downloader.HTMLDownloader
import com.fzizzi.crawler.extractor.ContentHandler
import com.fzizzi.crawler.extractor.HandlerResult
import com.fzizzi.crawler.frontier.URLFrontier
import com.fzizzi.crawler.logging.CrawlerLogger
import com.fzizzi.crawler.logging.LogCategory
import com.fzizzi.crawler.logging.NoOpLogger
import com.fzizzi.crawler.model.CrawlEvent
import com.fzizzi.crawler.sink.CrawlResultSink
import com.fzizzi.crawler.sink.NoOpSink
import com.fzizzi.crawler.storage.ContentStorage
import com.fzizzi.crawler.storage.URLStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class CrawlerOrchestrator(
    private val urlFrontier: URLFrontier,
    private val htmlDownloader: HTMLDownloader,
    private val dnsResolver: DNSResolver,
    private val handlers: List<ContentHandler>,
    private val contentStorage: ContentStorage,
    private val urlFilters: List<URLFilter>,
    private val urlStorage: URLStorage,
    private val sink: CrawlResultSink = NoOpSink,
    private val sinkChannelCapacity: Int = 500,
    private val logger: CrawlerLogger = NoOpLogger
) {
    suspend fun start(seeds: List<String>) = coroutineScope {
        logger.info(LogCategory.ORCHESTRATOR, "Starting crawl with ${seeds.size} seed(s): ${seeds.joinToString()}")
        seeds.forEach { urlStorage.markSeen(it) }
        urlFrontier.addAll(seeds)

        val eventChannel = Channel<CrawlEvent>(sinkChannelCapacity)

        val sinkJob = launchSinkJob(eventChannel)

        val referrerMap = ConcurrentHashMap<String, MutableSet<String>>()

        while (isActive) {
            val batchSize = 10
            val batchUrls = getNextUrls(batchSize)

            if (batchUrls.isEmpty()) {
                if (urlFrontier.isEmpty()) break else { delay(100); continue }
            }

            val deferreds = batchUrls.map { currentUrl ->
                async {
                    processUrl(referrerMap, eventChannel, currentUrl)

                    urlFrontier.markCompleted(currentUrl)
                    referrerMap.remove(currentUrl)
                }
            }
            deferreds.awaitAll()
        }

        eventChannel.close()
        sinkJob.join()
    }

    private suspend fun processUrl(
        referrerMap: ConcurrentHashMap<String, MutableSet<String>>,
        eventChannel: Channel<CrawlEvent>,
        currentUrl: String
    ) {
        val domain = extractDomain(currentUrl) ?: return

        val ipAddress = dnsResolver.resolve(domain).getOrElse {
            logger.warn(LogCategory.DNS, "DNS resolution failed for $domain: ${it.message}")
            return
        }

        logger.debug(LogCategory.DOWNLOADER, "Downloading $currentUrl")

        val content = htmlDownloader.download(currentUrl, ipAddress).getOrElse {
            logger.warn(LogCategory.DOWNLOADER, "Download failed for $currentUrl: ${it.message}")
            return
        }

        val urlSeen = contentStorage.isSeen(content).getOrElse {
            logger.error(LogCategory.STORAGE, "Failed to query URL Storage for $currentUrl: ${it.message}")
            true
        }

        if (urlSeen) {
            logger.debug(LogCategory.STORAGE, "Duplicate content skipped for $currentUrl")
            return
        }

        contentStorage.markSeen(content)

        logger.info(LogCategory.ORCHESTRATOR, "Crawled $currentUrl (${content.bytes.size / 1024}KB)")

        val activeHandlers = handlers.filter { it.canHandle(content.contentType) }

        val discoveredLinks = mutableListOf<String>()

        val aggregatedMetadata = mutableMapOf<String, Any>()

        for (handler in activeHandlers) {
            val handlerResult = handler.handle(content)
                .onFailure { e ->
                    logger.error(LogCategory.EXTRACTOR, "Handler ${handler.id} failed for $currentUrl", e)
                }
                .getOrNull() ?: continue

            discoveredLinks.addAll(handlerResult.discoveredLinks)
            aggregatedMetadata.putAll(handlerResult.extractedMetadata)
        }

        val newUrls = mutableListOf<String>()

        for (link in discoveredLinks) {
            if (urlFilters.any { !it.isAllowed(link) }) continue
            if (!urlStorage.isSeen(link)) {
                urlStorage.markSeen(link)
                newUrls.add(link)
            }
            if (link != currentUrl) {
                referrerMap.compute(link) { _, existing ->
                    (existing ?: Collections.newSetFromMap(ConcurrentHashMap())).also { it.add(currentUrl) }
                }
            }
        }

        if (newUrls.isNotEmpty()) {
            logger.debug(LogCategory.EXTRACTOR, "Discovered ${newUrls.size} new URL(s) from $currentUrl")
            urlFrontier.addAll(newUrls)
        }

        val event = CrawlEvent(
            url = currentUrl,
            referrerUrls = referrerMap[currentUrl]?.toList() ?: emptyList(),
            rawContent = content,
            extractedLinks = newUrls,
            ipAddress = ipAddress,
            metadata = aggregatedMetadata
        )

        eventChannel.send(event)
    }

    private suspend fun getNextUrls(batchSize: Int): List<String> {
        val batchUrls = mutableListOf<String>()
        for (i in 0 until batchSize) {
            val nextUrl = urlFrontier.getNext()
            if (nextUrl != null) batchUrls.add(nextUrl) else break
        }
        return batchUrls
    }

    private fun CoroutineScope.launchSinkJob(eventChannel: Channel<CrawlEvent>) =
        launch {
            for (event in eventChannel) {
                try {
                    sink.accept(event)
                } catch (e: Exception) {
                    logger.error(LogCategory.ORCHESTRATOR, "Sink failed for ${event.url}", e)
                }
            }
        }

    private fun extractDomain(url: String): String? {
        return try {
            java.net.URL(url).host
        } catch (e: Exception) {
            null
        }
    }
}
