package com.fzizzi.crawler.engine

import com.fzizzi.crawler.downloader.DNSResolver
import com.fzizzi.crawler.downloader.HTMLDownloader
import com.fzizzi.crawler.extractor.LinkExtractor
import com.fzizzi.crawler.frontier.URLFrontier
import com.fzizzi.crawler.model.HTMLContent
import com.fzizzi.crawler.parser.ContentParser
import com.fzizzi.crawler.storage.ContentSeen
import com.fzizzi.crawler.storage.URLSeen
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CrawlerOrchestratorTest {

    private lateinit var mockFrontier: URLFrontier
    private lateinit var mockDownloader: HTMLDownloader
    private lateinit var mockDnsResolver: DNSResolver
    private lateinit var mockContentParser: ContentParser
    private lateinit var mockContentSeen: ContentSeen
    private lateinit var mockLinkExtractor: LinkExtractor
    private lateinit var urlFilter: DefaultURLFilter
    private lateinit var mockUrlSeen: URLSeen

    private lateinit var orchestrator: CrawlerOrchestrator

    @BeforeEach
    fun setUp() {
        mockFrontier = mockk()
        mockDownloader = mockk()
        mockDnsResolver = mockk()
        mockContentParser = mockk()
        mockContentSeen = mockk()
        mockLinkExtractor = mockk()
        urlFilter = DefaultURLFilter()
        mockUrlSeen = mockk()

        orchestrator = CrawlerOrchestrator(
            urlFrontier = mockFrontier,
            htmlDownloader = mockDownloader,
            dnsResolver = mockDnsResolver,
            contentParser = mockContentParser,
            contentSeen = mockContentSeen,
            linkExtractor = mockLinkExtractor,
            urlFilter = urlFilter,
            urlSeen = mockUrlSeen
        )
    }

    @Test
    fun `test successful full crawl loop for single seed URL`() = runTest {
        val seedUrl = "https://example.com"
        val extractedLink = "https://example.com/page1"
        val ipAddress = "192.168.1.1"
        val content = HTMLContent(seedUrl, "<html>dummy</html>", "hash123")

        // 1. Initial seeds setup
        coEvery { mockFrontier.addAll(listOf(seedUrl)) } just Runs
        
        // 2. Loop condition
        coEvery { mockFrontier.isEmpty() } returns false andThen true // run once and terminate
        
        // 3. Fetch Next
        coEvery { mockFrontier.getNext() } returns seedUrl
        
        // 4. DNS resolve
        coEvery { mockDnsResolver.resolve("example.com") } returns Result.success(ipAddress)
        
        // 5. Download
        coEvery { mockDownloader.download(seedUrl, ipAddress) } returns Result.success(content)
        
        // 6. Parse and Validate
        coEvery { mockContentParser.parseAndValidate(content) } returns true
        
        // 7. Content Seen
        coEvery { mockContentSeen.isSeen(content) } returns false
        coEvery { mockContentSeen.add(content) } just Runs
        
        // 8. Extract Links
        coEvery { mockLinkExtractor.extract(content) } returns listOf(extractedLink)
        
        // 9. URL Filter (Using actual implementation, which will approve 'https://example.com/page1')
        
        // 10. URL Seen
        coEvery { mockUrlSeen.isSeen(extractedLink) } returns false
        coEvery { mockUrlSeen.add(extractedLink) } just Runs
        
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
        coVerify(exactly = 1) { mockContentSeen.add(content) }
        coVerify(exactly = 1) { mockUrlSeen.add(extractedLink) }
        coVerify(exactly = 1) { mockFrontier.addAll(listOf(extractedLink)) }
        coVerify(exactly = 1) { mockFrontier.markCompleted(seedUrl) }
        
        assertEquals(listOf(extractedLink), addedUrlsSlot.captured)
    }

    @Test
    fun `test skip URL if DNS fails`() = runTest {
        val seedUrl = "https://invalid.com"
        
        coEvery { mockFrontier.addAll(listOf(seedUrl)) } just Runs
        coEvery { mockFrontier.isEmpty() } returns false andThen true
        coEvery { mockFrontier.getNext() } returns seedUrl
        
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

        coEvery { mockFrontier.addAll(listOf(seedUrl)) } just Runs
        coEvery { mockFrontier.isEmpty() } returns false andThen true
        coEvery { mockFrontier.getNext() } returns seedUrl
        coEvery { mockDnsResolver.resolve("example.com") } returns Result.success(ipAddress)
        coEvery { mockDownloader.download(seedUrl, ipAddress) } returns Result.success(content)
        coEvery { mockContentParser.parseAndValidate(content) } returns true
        
        // Inject content duplicate
        coEvery { mockContentSeen.isSeen(content) } returns true
        
        coEvery { mockFrontier.markCompleted(seedUrl) } just Runs

        orchestrator.start(listOf(seedUrl))

        coVerify(exactly = 1) { mockContentParser.parseAndValidate(content) }
        coVerify(exactly = 0) { mockContentSeen.add(any()) }
        coVerify(exactly = 0) { mockLinkExtractor.extract(any()) }
        coVerify(exactly = 1) { mockFrontier.markCompleted(seedUrl) }
    }
}
