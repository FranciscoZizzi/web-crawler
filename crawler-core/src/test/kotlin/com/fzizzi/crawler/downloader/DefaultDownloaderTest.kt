package com.fzizzi.crawler.downloader

import com.fzizzi.crawler.model.HTMLContent
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RobotsCacheTest {

    @Test
    fun `test robotsCache allows everything by default`() = runTest {
        val cache = RobotsCache()
        
        // Default implementation returns empty list of disallowed paths
        assertTrue(cache.isAllowed("https://example.com/foo/bar", "example.com"))
        assertTrue(cache.isAllowed("https://example.com/admin", "example.com"))
    }
    
    @Test
    fun `test robotsCache handles unparseable URI safely`() = runTest {
        val cache = RobotsCache()
        
        // This won't throw exception, but will default path to "/" and still allow by default empty robots.txt rules
        assertTrue(cache.isAllowed("htt p://[invalid_url", "example.com"))
    }
}

class DefaultDownloaderTest {

    private val mockDispatcher: DownloadDispatcher = mockk()
    private val mockRobotsCache: RobotsCache = mockk()

    @Test
    fun `test download fails when ip is null`() = runTest {
        val downloader = DefaultDownloader(mockDispatcher, mockRobotsCache)
        
        val result = downloader.download("https://example.com", null)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals("IP Address is required", result.exceptionOrNull()?.message)
    }

    @Test
    fun `test download fails when disallowed by robotsCache`() = runTest {
        val downloader = DefaultDownloader(mockDispatcher, mockRobotsCache)
        
        coEvery { mockRobotsCache.isAllowed("https://example.com", "example.com") } returns false

        val result = downloader.download("https://example.com", "1.2.3.4")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals("URL is blocked by robots.txt", result.exceptionOrNull()?.message)
    }

    @Test
    fun `test successful download dispatches correctly`() = runTest {
        val downloader = DefaultDownloader(mockDispatcher, mockRobotsCache)
        
        val url = "https://example.com/page"
        val ip = "1.2.3.4"
        val content = HTMLContent(url, "html", "hash")
        
        coEvery { mockRobotsCache.isAllowed(url, "example.com") } returns true
        
        val deferred = CompletableDeferred(Result.success(content))
        coEvery { mockDispatcher.dispatch(url, ip, any()) } returns deferred
        
        val result = downloader.download(url, ip)
        
        assertTrue(result.isSuccess)
        assertEquals(content, result.getOrNull())
    }
    
    @Test
    fun `test download timeout is correctly converted to failure`() = runTest {
        // Here we configure the downloader to have a very short timeout
        val downloader = DefaultDownloader(mockDispatcher, mockRobotsCache, timeoutMs = 10L)
        
        val url = "https://example.com/slow"
        val ip = "1.2.3.4"
        
        coEvery { mockRobotsCache.isAllowed(url, "example.com") } returns true
        
        // Dispatch returns a deferred that never completes, forcing a timeout
        val deferred = CompletableDeferred<Result<HTMLContent>>()
        coEvery { mockDispatcher.dispatch(url, ip, any()) } returns deferred
        
        val result = downloader.download(url, ip)
        
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("timed out") == true)
    }
}
