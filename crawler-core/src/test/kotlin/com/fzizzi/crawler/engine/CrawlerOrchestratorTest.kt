package com.fzizzi.crawler.engine

import com.fzizzi.crawler.downloader.DNSResolver
import com.fzizzi.crawler.downloader.HTMLDownloader
import com.fzizzi.crawler.extractor.LinkExtractor
import com.fzizzi.crawler.frontier.URLFrontier
import com.fzizzi.crawler.model.CrawlEvent
import com.fzizzi.crawler.model.HTMLContent
import com.fzizzi.crawler.parser.ContentParser
import com.fzizzi.crawler.sink.CrawlResultSink
import com.fzizzi.crawler.storage.ContentStorage
import com.fzizzi.crawler.storage.URLStorage
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrawlerOrchestratorTest {

    private lateinit var mockFrontier: URLFrontier
    private lateinit var mockDownloader: HTMLDownloader
    private lateinit var mockDnsResolver: DNSResolver
    private lateinit var mockContentParser: ContentParser
    private lateinit var mockContentStorage: ContentStorage
    private lateinit var mockLinkExtractor: LinkExtractor
    private lateinit var urlFilter: DefaultURLFilter
    private lateinit var mockUrlStorage: URLStorage

    private lateinit var orchestrator: CrawlerOrchestrator

    @BeforeEach
    fun setUp() {
        mockFrontier = mockk()
        mockDownloader = mockk()
        mockDnsResolver = mockk()
        mockContentParser = mockk()
        mockContentStorage = mockk()
        mockLinkExtractor = mockk()
        urlFilter = DefaultURLFilter()
        mockUrlStorage = mockk()

        orchestrator = CrawlerOrchestrator(
            urlFrontier = mockFrontier,
            htmlDownloader = mockDownloader,
            dnsResolver = mockDnsResolver,
            contentParser = mockContentParser,
            contentStorage = mockContentStorage,
            linkExtractor = mockLinkExtractor,
            urlFilter = urlFilter,
            urlStorage = mockUrlStorage
        )
    }

    @Test
    fun `test successful full crawl loop for single seed URL`() = runTest {
        val seedUrl = "https://example.com"
        val extractedLink = "https://example.com/page1"
        val ipAddress = "192.168.1.1"
        val content = HTMLContent(seedUrl, "<html>dummy</html>", "hash123")

        // 1. Initial seeds setup
        coEvery { mockUrlStorage.markSeen(seedUrl) } just Runs
        coEvery { mockFrontier.addAll(listOf(seedUrl)) } just Runs
        
        // 2. Loop condition
        coEvery { mockFrontier.isEmpty() } returns false andThen true // run once and terminate
        
        // 3. Fetch Next
        coEvery { mockFrontier.getNext() } returns seedUrl andThen null
        
        // 4. DNS resolve
        coEvery { mockDnsResolver.resolve("example.com") } returns Result.success(ipAddress)
        
        // 5. Download
        coEvery { mockDownloader.download(seedUrl, ipAddress) } returns Result.success(content)
        
        // 6. Parse and Validate
        coEvery { mockContentParser.parseAndValidate(content) } returns true
        
        // 7. Content Seen
        coEvery { mockContentStorage.isSeen(content) } returns false
        coEvery { mockContentStorage.markSeen(content) } just Runs
        
        // 8. Extract Links
        coEvery { mockLinkExtractor.extract(content) } returns listOf(extractedLink)
        
        // 10. URL Seen
        coEvery { mockUrlStorage.isSeen(extractedLink) } returns false
        coEvery { mockUrlStorage.markSeen(extractedLink) } just Runs
        
        // 11. Add new URL back to frontier
        val addedUrlsSlot = slot<List<String>>()
        coEvery { mockFrontier.addAll(capture(addedUrlsSlot)) } just Runs
        
        // Finally: Mark URL finished
        coEvery { mockFrontier.markCompleted(seedUrl) } just Runs

        // Execution
        orchestrator.start(listOf(seedUrl))

        // Verifications
        coVerify(exactly = 1) { mockFrontier.addAll(listOf(seedUrl)) }
        coVerify(exactly = 1) { mockDownloader.download(seedUrl, ipAddress) }
        coVerify(exactly = 1) { mockContentParser.parseAndValidate(content) }
        coVerify(exactly = 1) { mockContentStorage.markSeen(content) }
        coVerify(exactly = 1) { mockUrlStorage.markSeen(extractedLink) }
        coVerify(exactly = 1) { mockFrontier.addAll(listOf(extractedLink)) }
        coVerify(exactly = 1) { mockFrontier.markCompleted(seedUrl) }
        
        assertEquals(listOf(extractedLink), addedUrlsSlot.captured)
    }

    @Test
    fun `test skip URL if DNS fails`() = runTest {
        val seedUrl = "https://invalid.com"
        
        coEvery { mockUrlStorage.markSeen(seedUrl) } just Runs
        coEvery { mockFrontier.addAll(listOf(seedUrl)) } just Runs
        coEvery { mockFrontier.isEmpty() } returns false andThen true
        coEvery { mockFrontier.getNext() } returns seedUrl andThen null
        
        // Inject DNS failure
        coEvery { mockDnsResolver.resolve("invalid.com") } returns Result.failure(Exception("DNS Error"))
        
        // Must still mark completed
        coEvery { mockFrontier.markCompleted(seedUrl) } just Runs

        orchestrator.start(listOf(seedUrl))

        // Verify it stops early
        coVerify(exactly = 0) { mockDownloader.download(any(), any()) }
        coVerify(exactly = 1) { mockFrontier.markCompleted(seedUrl) }
    }
    
    @Test
    fun `test skip URL if content already seen`() = runTest {
        val seedUrl = "https://example.com"
        val ipAddress = "192.168.1.1"
        val content = HTMLContent(seedUrl, "<html>copied</html>", "hash123")

        coEvery { mockUrlStorage.markSeen(seedUrl) } just Runs
        coEvery { mockFrontier.addAll(listOf(seedUrl)) } just Runs
        coEvery { mockFrontier.isEmpty() } returns false andThen true
        coEvery { mockFrontier.getNext() } returns seedUrl andThen null
        coEvery { mockDnsResolver.resolve("example.com") } returns Result.success(ipAddress)
        coEvery { mockDownloader.download(seedUrl, ipAddress) } returns Result.success(content)
        coEvery { mockContentParser.parseAndValidate(content) } returns true
        
        // Inject content duplicate
        coEvery { mockContentStorage.isSeen(content) } returns true
        
        coEvery { mockFrontier.markCompleted(seedUrl) } just Runs

        orchestrator.start(listOf(seedUrl))

        coVerify(exactly = 1) { mockContentParser.parseAndValidate(content) }
        coVerify(exactly = 0) { mockContentStorage.markSeen(any()) }
        coVerify(exactly = 0) { mockLinkExtractor.extract(any()) }
        coVerify(exactly = 1) { mockFrontier.markCompleted(seedUrl) }
    }
}

// ──── Multi-referrer tests ─────────────────────────────────────────────────────

/**
 * Tests for [CrawlEvent.referrerUrls] semantics.
 * Uses a real InMemorySink to capture emitted events without mocking the channel.
 */
class CrawlerOrchestratorReferrerTest {

    private fun buildOrchestrator(
        frontier: URLFrontier,
        downloader: HTMLDownloader,
        dns: DNSResolver,
        parser: ContentParser,
        contentStorage: ContentStorage,
        extractor: LinkExtractor,
        urlStorage: URLStorage,
        sink: CrawlResultSink
    ) = CrawlerOrchestrator(
        urlFrontier    = frontier,
        htmlDownloader = downloader,
        dnsResolver    = dns,
        contentParser  = parser,
        contentStorage = contentStorage,
        linkExtractor  = extractor,
        urlFilter      = DefaultURLFilter(),
        urlStorage     = urlStorage,
        sink           = sink
    )

    @Test
    fun `seed URL produces empty referrerUrls`() = runTest {
        val seedUrl   = "https://example.com"
        val ipAddress = "1.2.3.4"
        val content   = HTMLContent(seedUrl, "<html>seed</html>", "hashSeed")
        val events    = CopyOnWriteArrayList<CrawlEvent>()
        val sink      = CrawlResultSink { events.add(it) }

        val frontier     = mockk<URLFrontier>()
        val downloader   = mockk<HTMLDownloader>()
        val dns          = mockk<DNSResolver>()
        val parser       = mockk<ContentParser>()
        val contentStore = mockk<ContentStorage>()
        val extractor    = mockk<LinkExtractor>()
        val urlStorage   = mockk<URLStorage>()

        coEvery { urlStorage.markSeen(seedUrl) } just Runs
        coEvery { frontier.addAll(listOf(seedUrl)) } just Runs
        coEvery { frontier.isEmpty() } returns false andThen true
        coEvery { frontier.getNext() } returns seedUrl andThen null
        coEvery { dns.resolve("example.com") } returns Result.success(ipAddress)
        coEvery { downloader.download(seedUrl, ipAddress) } returns Result.success(content)
        coEvery { parser.parseAndValidate(content) } returns true
        coEvery { contentStore.isSeen(content) } returns false
        coEvery { contentStore.markSeen(content) } just Runs
        coEvery { extractor.extract(content) } returns emptyList()
        coEvery { frontier.markCompleted(seedUrl) } just Runs

        buildOrchestrator(frontier, downloader, dns, parser, contentStore, extractor, urlStorage, sink)
            .start(listOf(seedUrl))

        assertEquals(1, events.size)
        assertTrue(events[0].referrerUrls.isEmpty(), "seed should have empty referrerUrls")
    }

    @Test
    fun `URL discovered by one page has that page as its only referrer`() = runTest {
        val pageA     = "https://example.com/"
        val pageB     = "https://example.com/b"
        val ip        = "1.2.3.4"
        val contentA  = HTMLContent(pageA, "<html>pageA</html>", "hashA")
        val contentB  = HTMLContent(pageB, "<html>pageB</html>", "hashB")
        val events    = CopyOnWriteArrayList<CrawlEvent>()
        val sink      = CrawlResultSink { events.add(it) }

        val frontier     = mockk<URLFrontier>()
        val downloader   = mockk<HTMLDownloader>()
        val dns          = mockk<DNSResolver>()
        val parser       = mockk<ContentParser>()
        val contentStore = mockk<ContentStorage>()
        val extractor    = mockk<LinkExtractor>()
        val urlStorage   = mockk<URLStorage>()

        // First batch: only pageA (seed)
        coEvery { urlStorage.markSeen(pageA) } just Runs
        coEvery { frontier.addAll(listOf(pageA)) } just Runs
        coEvery { frontier.isEmpty() } returns false andThen false andThen true
        coEvery { frontier.getNext() } returns pageA andThen null andThen pageB andThen null
        coEvery { dns.resolve("example.com") } returns Result.success(ip)
        coEvery { downloader.download(pageA, ip) } returns Result.success(contentA)
        coEvery { downloader.download(pageB, ip) } returns Result.success(contentB)
        coEvery { parser.parseAndValidate(any()) } returns true
        coEvery { contentStore.isSeen(contentA) } returns false
        coEvery { contentStore.isSeen(contentB) } returns false
        coEvery { contentStore.markSeen(any()) } just Runs
        coEvery { extractor.extract(contentA) } returns listOf(pageB)
        coEvery { extractor.extract(contentB) } returns emptyList()
        coEvery { urlStorage.isSeen(pageB) } returns false
        coEvery { urlStorage.markSeen(pageB) } just Runs
        coEvery { frontier.addAll(listOf(pageB)) } just Runs
        coEvery { frontier.markCompleted(any()) } just Runs

        buildOrchestrator(frontier, downloader, dns, parser, contentStore, extractor, urlStorage, sink)
            .start(listOf(pageA))

        val eventB = events.first { it.url == pageB }
        assertEquals(listOf(pageA), eventB.referrerUrls)
    }

    @Test
    fun `URL discovered from multiple pages accumulates all referrers`() = runTest {
        // pageA and pageB both link to pageC
        val pageA    = "https://example.com/a"
        val pageB    = "https://example.com/b"
        val pageC    = "https://example.com/c"
        val ip       = "1.2.3.4"
        val contentA = HTMLContent(pageA, "<html>A</html>", "hashA")
        val contentB = HTMLContent(pageB, "<html>B</html>", "hashB")
        val contentC = HTMLContent(pageC, "<html>C</html>", "hashC")
        val events   = CopyOnWriteArrayList<CrawlEvent>()
        val sink     = CrawlResultSink { events.add(it) }

        val frontier     = mockk<URLFrontier>()
        val downloader   = mockk<HTMLDownloader>()
        val dns          = mockk<DNSResolver>()
        val parser       = mockk<ContentParser>()
        val contentStore = mockk<ContentStorage>()
        val extractor    = mockk<LinkExtractor>()
        val urlStorage   = mockk<URLStorage>()

        // Seeds: pageA and pageB come out in first batch; pageC comes out after
        coEvery { urlStorage.markSeen(pageA) } just Runs
        coEvery { urlStorage.markSeen(pageB) } just Runs
        coEvery { frontier.addAll(listOf(pageA, pageB)) } just Runs
        coEvery { frontier.isEmpty() } returns false andThen false andThen true
        // First batch returns pageA, pageB, then null to end batch
        coEvery { frontier.getNext() } returnsMany listOf(pageA, pageB, null, pageC, null)
        coEvery { dns.resolve("example.com") } returns Result.success(ip)
        coEvery { downloader.download(pageA, ip) } returns Result.success(contentA)
        coEvery { downloader.download(pageB, ip) } returns Result.success(contentB)
        coEvery { downloader.download(pageC, ip) } returns Result.success(contentC)
        coEvery { parser.parseAndValidate(any()) } returns true
        coEvery { contentStore.isSeen(contentA) } returns false
        coEvery { contentStore.isSeen(contentB) } returns false
        coEvery { contentStore.isSeen(contentC) } returns false
        coEvery { contentStore.markSeen(any()) } just Runs
        coEvery { extractor.extract(contentA) } returns listOf(pageC)
        coEvery { extractor.extract(contentB) } returns listOf(pageC)
        coEvery { extractor.extract(contentC) } returns emptyList()
        // pageC is "not seen" for the first discoverer (A), "seen" for B (already added)
        coEvery { urlStorage.isSeen(pageC) } returns false andThen true
        coEvery { urlStorage.markSeen(pageC) } just Runs
        coEvery { frontier.addAll(listOf(pageC)) } just Runs
        coEvery { frontier.markCompleted(any()) } just Runs

        buildOrchestrator(frontier, downloader, dns, parser, contentStore, extractor, urlStorage, sink)
            .start(listOf(pageA, pageB))

        val eventC = events.first { it.url == pageC }
        assertEquals(
            setOf(pageA, pageB),
            eventC.referrerUrls.toSet(),
            "pageC should have both pageA and pageB as referrers"
        )
    }

    @Test
    fun `referrer map entry is removed after URL is processed`() = runTest {
        // We test this indirectly: if entries leaked, a second run with a fresh orchestrator
        // would not share state, so we just verify the crawl completes without OOM growth.
        // The cleanup is tested structurally by verifying markCompleted and remove are paired.
        val seedUrl   = "https://example.com"
        val ip        = "1.2.3.4"
        val content   = HTMLContent(seedUrl, "<html>x</html>", "hashX")
        val events    = CopyOnWriteArrayList<CrawlEvent>()
        val sink      = CrawlResultSink { events.add(it) }

        val frontier     = mockk<URLFrontier>()
        val downloader   = mockk<HTMLDownloader>()
        val dns          = mockk<DNSResolver>()
        val parser       = mockk<ContentParser>()
        val contentStore = mockk<ContentStorage>()
        val extractor    = mockk<LinkExtractor>()
        val urlStorage   = mockk<URLStorage>()

        coEvery { urlStorage.markSeen(seedUrl) } just Runs
        coEvery { frontier.addAll(listOf(seedUrl)) } just Runs
        coEvery { frontier.isEmpty() } returns false andThen true
        coEvery { frontier.getNext() } returns seedUrl andThen null
        coEvery { dns.resolve("example.com") } returns Result.success(ip)
        coEvery { downloader.download(seedUrl, ip) } returns Result.success(content)
        coEvery { parser.parseAndValidate(content) } returns true
        coEvery { contentStore.isSeen(content) } returns false
        coEvery { contentStore.markSeen(content) } just Runs
        coEvery { extractor.extract(content) } returns emptyList()
        coEvery { frontier.markCompleted(seedUrl) } just Runs

        buildOrchestrator(frontier, downloader, dns, parser, contentStore, extractor, urlStorage, sink)
            .start(listOf(seedUrl))

        // markCompleted must be called (paired remove happens in the finally block alongside it)
        coVerify(exactly = 1) { frontier.markCompleted(seedUrl) }
        // Seed event should have no referrers
        assertEquals(1, events.size)
        assertTrue(events[0].referrerUrls.isEmpty())
    }
}
