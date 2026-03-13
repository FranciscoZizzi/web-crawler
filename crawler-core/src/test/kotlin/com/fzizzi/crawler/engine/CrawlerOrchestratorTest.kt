package com.fzizzi.crawler.engine

import com.fzizzi.crawler.downloader.DNSResolver
import com.fzizzi.crawler.downloader.HTMLDownloader
import com.fzizzi.crawler.extractor.ContentHandler
import com.fzizzi.crawler.extractor.HandlerResult
import com.fzizzi.crawler.frontier.URLFrontier
import com.fzizzi.crawler.model.CrawlEvent
import com.fzizzi.crawler.model.RawContent
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
    private lateinit var mockContentStorage: ContentStorage
    private lateinit var mockHandler: ContentHandler
    private lateinit var urlFilter: DefaultURLFilter
    private lateinit var mockUrlStorage: URLStorage

    private lateinit var orchestrator: CrawlerOrchestrator

    @BeforeEach
    fun setUp() {
        mockFrontier = mockk()
        mockDownloader = mockk()
        mockDnsResolver = mockk()
        mockContentStorage = mockk()
        mockHandler = mockk()
        urlFilter = DefaultURLFilter()
        mockUrlStorage = mockk()

        orchestrator = CrawlerOrchestrator(
            urlFrontier = mockFrontier,
            htmlDownloader = mockDownloader,
            dnsResolver = mockDnsResolver,
            handlers = listOf(mockHandler),
            contentStorage = mockContentStorage,
            urlFilter = urlFilter,
            urlStorage = mockUrlStorage
        )
    }

    @Test
    fun `test successful full crawl loop for single seed URL`() = runTest {
        val seedUrl = "https://example.com"
        val extractedLink = "https://example.com/page1"
        val ipAddress = "192.168.1.1"
        val content = RawContent(seedUrl, "text/html", "<html>dummy</html>".toByteArray(), "hash123")

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
        
        // 7. Content Seen
        coEvery { mockContentStorage.isSeen(content) } returns false
        coEvery { mockContentStorage.markSeen(content) } just Runs
        
        // 8. Handler logic
        every { mockHandler.id } returns "test-handler"
        every { mockHandler.canHandle("text/html") } returns true
        coEvery { mockHandler.handle(content) } returns HandlerResult(listOf(extractedLink), mapOf("test" to true))
        
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
        val content = RawContent(seedUrl, "text/html", "<html>copied</html>".toByteArray(), "hash123")

        coEvery { mockUrlStorage.markSeen(seedUrl) } just Runs
        coEvery { mockFrontier.addAll(listOf(seedUrl)) } just Runs
        coEvery { mockFrontier.isEmpty() } returns false andThen true
        coEvery { mockFrontier.getNext() } returns seedUrl andThen null
        coEvery { mockDnsResolver.resolve("example.com") } returns Result.success(ipAddress)
        coEvery { mockDownloader.download(seedUrl, ipAddress) } returns Result.success(content)
        
        // Inject content duplicate
        coEvery { mockContentStorage.isSeen(content) } returns true
        
        coEvery { mockFrontier.markCompleted(seedUrl) } just Runs

        orchestrator.start(listOf(seedUrl))

        coVerify(exactly = 0) { mockContentStorage.markSeen(any()) }
        coVerify(exactly = 0) { mockHandler.handle(any()) }
        coVerify(exactly = 1) { mockFrontier.markCompleted(seedUrl) }
    }
}

// ──── Multi-referrer tests ─────────────────────────────────────────────────────

class CrawlerOrchestratorReferrerTest {

    private fun buildOrchestrator(
        frontier: URLFrontier,
        downloader: HTMLDownloader,
        dns: DNSResolver,
        handlers: List<ContentHandler>,
        contentStorage: ContentStorage,
        urlStorage: URLStorage,
        sink: CrawlResultSink
    ) = CrawlerOrchestrator(
        urlFrontier    = frontier,
        htmlDownloader = downloader,
        dnsResolver    = dns,
        handlers       = handlers,
        contentStorage = contentStorage,
        urlFilter      = DefaultURLFilter(),
        urlStorage     = urlStorage,
        sink           = sink
    )

    @Test
    fun `seed URL produces empty referrerUrls`() = runTest {
        val seedUrl   = "https://example.com"
        val ipAddress = "1.2.3.4"
        val content   = RawContent(seedUrl, "text/html", "<html>seed</html>".toByteArray(), "hashSeed")
        val events    = CopyOnWriteArrayList<CrawlEvent>()
        val sink      = CrawlResultSink { events.add(it) }

        val frontier     = mockk<URLFrontier>()
        val downloader   = mockk<HTMLDownloader>()
        val dns          = mockk<DNSResolver>()
        val contentStore = mockk<ContentStorage>()
        val handler      = mockk<ContentHandler>()
        val urlStorage   = mockk<URLStorage>()

        coEvery { urlStorage.markSeen(seedUrl) } just Runs
        coEvery { frontier.addAll(listOf(seedUrl)) } just Runs
        coEvery { frontier.isEmpty() } returns false andThen true
        coEvery { frontier.getNext() } returns seedUrl andThen null
        coEvery { dns.resolve("example.com") } returns Result.success(ipAddress)
        coEvery { downloader.download(seedUrl, ipAddress) } returns Result.success(content)
        coEvery { contentStore.isSeen(content) } returns false
        coEvery { contentStore.markSeen(content) } just Runs
        every { handler.canHandle(any()) } returns false
        coEvery { frontier.markCompleted(seedUrl) } just Runs

        buildOrchestrator(frontier, downloader, dns, listOf(handler), contentStore, urlStorage, sink)
            .start(listOf(seedUrl))

        assertEquals(1, events.size)
        assertTrue(events[0].referrerUrls.isEmpty(), "seed should have empty referrerUrls")
    }

    @Test
    fun `URL discovered by one page has that page as its only referrer`() = runTest {
        val pageA     = "https://example.com/"
        val pageB     = "https://example.com/b"
        val ip        = "1.2.3.4"
        val contentA  = RawContent(pageA, "text/html", "<html>pageA</html>".toByteArray(), "hashA")
        val contentB  = RawContent(pageB, "text/html", "<html>pageB</html>".toByteArray(), "hashB")
        val events    = CopyOnWriteArrayList<CrawlEvent>()
        val sink      = CrawlResultSink { events.add(it) }

        val frontier     = mockk<URLFrontier>()
        val downloader   = mockk<HTMLDownloader>()
        val dns          = mockk<DNSResolver>()
        val contentStore = mockk<ContentStorage>()
        val handler      = mockk<ContentHandler>()
        val urlStorage   = mockk<URLStorage>()

        coEvery { urlStorage.markSeen(pageA) } just Runs
        coEvery { frontier.addAll(listOf(pageA)) } just Runs
        coEvery { frontier.isEmpty() } returns false andThen false andThen true
        coEvery { frontier.getNext() } returns pageA andThen null andThen pageB andThen null
        coEvery { dns.resolve("example.com") } returns Result.success(ip)
        coEvery { downloader.download(pageA, ip) } returns Result.success(contentA)
        coEvery { downloader.download(pageB, ip) } returns Result.success(contentB)
        coEvery { contentStore.isSeen(any()) } returns false
        coEvery { contentStore.markSeen(any()) } just Runs
        
        every { handler.canHandle(any()) } returns true
        coEvery { handler.handle(contentA) } returns HandlerResult(listOf(pageB))
        coEvery { handler.handle(contentB) } returns HandlerResult(emptyList())

        coEvery { urlStorage.isSeen(pageB) } returns false
        coEvery { urlStorage.markSeen(pageB) } just Runs
        coEvery { frontier.addAll(listOf(pageB)) } just Runs
        coEvery { frontier.markCompleted(any()) } just Runs

        buildOrchestrator(frontier, downloader, dns, listOf(handler), contentStore, urlStorage, sink)
            .start(listOf(pageA))

        val eventB = events.first { it.url == pageB }
        assertEquals(listOf(pageA), eventB.referrerUrls)
    }

    @Test
    fun `URL discovered from multiple pages accumulates all referrers`() = runTest {
        val pageA    = "https://example.com/a"
        val pageB    = "https://example.com/b"
        val pageC    = "https://example.com/c"
        val ip       = "1.2.3.4"
        val contentA = RawContent(pageA, "text/html", "<html>A</html>".toByteArray(), "hashA")
        val contentB = RawContent(pageB, "text/html", "<html>B</html>".toByteArray(), "hashB")
        val contentC = RawContent(pageC, "text/html", "<html>C</html>".toByteArray(), "hashC")
        val events   = CopyOnWriteArrayList<CrawlEvent>()
        val sink     = CrawlResultSink { events.add(it) }

        val frontier     = mockk<URLFrontier>()
        val downloader   = mockk<HTMLDownloader>()
        val dns          = mockk<DNSResolver>()
        val contentStore = mockk<ContentStorage>()
        val handler      = mockk<ContentHandler>()
        val urlStorage   = mockk<URLStorage>()

        coEvery { urlStorage.markSeen(pageA) } just Runs
        coEvery { urlStorage.markSeen(pageB) } just Runs
        coEvery { frontier.addAll(listOf(pageA, pageB)) } just Runs
        coEvery { frontier.isEmpty() } returns false andThen false andThen true
        coEvery { frontier.getNext() } returnsMany listOf(pageA, pageB, null, pageC, null)
        coEvery { dns.resolve("example.com") } returns Result.success(ip)
        coEvery { downloader.download(pageA, ip) } returns Result.success(contentA)
        coEvery { downloader.download(pageB, ip) } returns Result.success(contentB)
        coEvery { downloader.download(pageC, ip) } returns Result.success(contentC)
        coEvery { contentStore.isSeen(any()) } returns false
        coEvery { contentStore.markSeen(any()) } just Runs
        
        every { handler.canHandle(any()) } returns true
        coEvery { handler.handle(contentA) } returns HandlerResult(listOf(pageC))
        coEvery { handler.handle(contentB) } returns HandlerResult(listOf(pageC))
        coEvery { handler.handle(contentC) } returns HandlerResult(emptyList())

        coEvery { urlStorage.isSeen(pageC) } returns false andThen true
        coEvery { urlStorage.markSeen(pageC) } just Runs
        coEvery { frontier.addAll(listOf(pageC)) } just Runs
        coEvery { frontier.markCompleted(any()) } just Runs

        buildOrchestrator(frontier, downloader, dns, listOf(handler), contentStore, urlStorage, sink)
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
        val seedUrl   = "https://example.com"
        val ip        = "1.2.3.4"
        val content   = RawContent(seedUrl, "text/html", "<html>x</html>".toByteArray(), "hashX")
        val events    = CopyOnWriteArrayList<CrawlEvent>()
        val sink      = CrawlResultSink { events.add(it) }

        val frontier     = mockk<URLFrontier>()
        val downloader   = mockk<HTMLDownloader>()
        val dns          = mockk<DNSResolver>()
        val contentStore = mockk<ContentStorage>()
        val handler      = mockk<ContentHandler>()
        val urlStorage   = mockk<URLStorage>()

        coEvery { urlStorage.markSeen(seedUrl) } just Runs
        coEvery { frontier.addAll(listOf(seedUrl)) } just Runs
        coEvery { frontier.isEmpty() } returns false andThen true
        coEvery { frontier.getNext() } returns seedUrl andThen null
        coEvery { dns.resolve("example.com") } returns Result.success(ip)
        coEvery { downloader.download(seedUrl, ip) } returns Result.success(content)
        coEvery { contentStore.isSeen(content) } returns false
        coEvery { contentStore.markSeen(content) } just Runs
        every { handler.canHandle(any()) } returns false
        coEvery { frontier.markCompleted(seedUrl) } just Runs

        buildOrchestrator(frontier, downloader, dns, listOf(handler), contentStore, urlStorage, sink)
            .start(listOf(seedUrl))

        coVerify(exactly = 1) { frontier.markCompleted(seedUrl) }
        assertEquals(1, events.size)
        assertTrue(events[0].referrerUrls.isEmpty())
    }
}
