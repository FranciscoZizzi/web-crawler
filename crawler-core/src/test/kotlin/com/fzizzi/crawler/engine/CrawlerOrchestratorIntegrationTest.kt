package com.fzizzi.crawler.engine

import com.fzizzi.crawler.downloader.*
import com.fzizzi.crawler.extractor.HtmlHandler
import com.fzizzi.crawler.frontier.DefaultURLFrontier
import com.fzizzi.crawler.parser.DefaultContentParser
import com.fzizzi.crawler.storage.MemoryContentStorage
import com.fzizzi.crawler.storage.MemoryURLStorage
import com.sun.net.httpserver.HttpServer
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class CrawlerOrchestratorIntegrationTest {

    companion object {
        private lateinit var server: HttpServer
        var serverPort: Int = 0
        private val executor = Executors.newCachedThreadPool()

        @JvmStatic
        @BeforeAll
        fun startServer() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            
            // Seed page: contains links
            server.createContext("/seed") { exchange ->
                val response = """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Seed Page</title></head>
                    <body>
                        <p>This is the seed page, long enough to pass the parser.</p>
                        <a href="http://127.0.0.1:$serverPort/page1">Page 1</a>
                        <a href="http://127.0.0.1:$serverPort/page2">Page 2</a>
                    </body>
                    </html>
                """.trimIndent()
                exchange.sendResponseHeaders(200, response.length.toLong())
                exchange.responseBody.use { os -> os.write(response.toByteArray()) }
            }
            
            // Extracted page 1
            server.createContext("/page1") { exchange ->
                val response = """
                    <!DOCTYPE html>
                    <html><body><p>This is page 1, distinct content to not be duplicate.</p></body></html>
                """.trimIndent()
                exchange.sendResponseHeaders(200, response.length.toLong())
                exchange.responseBody.use { os -> os.write(response.toByteArray()) }
            }

            server.executor = executor
            server.start()
            serverPort = server.address.port
        }

        @JvmStatic
        @AfterAll
        fun stopServer() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private lateinit var scope: CoroutineScope
    
    // Default implementations
    private lateinit var urlFrontier: DefaultURLFrontier
    private lateinit var dnsResolver: CachingDNSResolver
    private lateinit var downloadDispatcher: DefaultDownloadDispatcher
    private lateinit var robotsCache: RobotsCache
    private lateinit var downloader: DefaultDownloader
    private lateinit var contentParser: DefaultContentParser
    private lateinit var urlFilter: DefaultURLFilter

    // Defaults for Storage & Extractor
    private lateinit var contentSeen: MemoryContentStorage
    private lateinit var urlSeen: MemoryURLStorage

    private lateinit var orchestrator: CrawlerOrchestrator

    @BeforeEach
    fun setUp() {
        mockkStatic(InetAddress::class)
        val mockAddress = mockk<InetAddress>()
        every { mockAddress.hostAddress } returns "127.0.0.1"
        every { InetAddress.getByName("127.0.0.1") } returns mockAddress
        
        scope = CoroutineScope(Dispatchers.Default)

        urlFrontier = DefaultURLFrontier(numFrontQueues = 1, numBackQueues = 1)
        dnsResolver = CachingDNSResolver(scope)
        downloadDispatcher = DefaultDownloadDispatcher(scope, numWorkers = 2)
        robotsCache = RobotsCache()
        downloader = DefaultDownloader(downloadDispatcher, robotsCache)
        contentParser = DefaultContentParser(minContentLength = 10) // Small min length to pass test pages
        urlFilter = DefaultURLFilter()

        contentSeen = MemoryContentStorage()
        urlSeen = MemoryURLStorage()

        val htmlHandler = HtmlHandler(contentParser)

        orchestrator = CrawlerOrchestrator(
            urlFrontier = urlFrontier,
            htmlDownloader = downloader,
            dnsResolver = dnsResolver,
            handlers = listOf(htmlHandler),
            contentStorage = contentSeen,
            urlFilters = listOf(urlFilter),
            urlStorage = urlSeen,
        )
    }

    @Test
    fun `test full integration crawl loop`() = runTest {
        val seedUrl = "http://127.0.0.1:$serverPort/seed"
        // Actual implementations will take care of linking without mocks.
        // We only start with the seed url. Orchestrator should fetch it, parse it, extract 2 links, 
        // add those to frontier, and fetch those as well until empty.
        orchestrator.start(listOf(seedUrl))
        
        // Verify State changes directly via size checks or internal sets (Skipped since Sets are private, just rely on execution finishing and assertions)
        
        // Let orchestrator finish background work. Wait, the orchestrator returns when frontier is empty.
        
        // Verify DNS lookup happened at least once per domain
        verify(atLeast = 1) { InetAddress.getByName("127.0.0.1") }
        
        // Asserts: The frontier should be totally empty at the end.
        assertTrue(urlFrontier.isEmpty())
        
        unmockkAll()
        scope.cancel()
    }
}
