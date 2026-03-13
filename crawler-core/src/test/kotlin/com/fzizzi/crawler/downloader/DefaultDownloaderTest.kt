package com.fzizzi.crawler.downloader

import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import com.fzizzi.crawler.model.RawContent
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors

// ──── Helpers ─────────────────────────────────────────────────────────────────

/** Starts a minimal HTTP server that serves [body] for every /robots.txt request. */
private fun robotsServer(body: String): Pair<HttpServer, Int> {
    val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    srv.createContext("/robots.txt") { ex ->
        val bytes = body.trimIndent().toByteArray()
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
    srv.executor = Executors.newCachedThreadPool()
    srv.start()
    return srv to srv.address.port
}

/** Starts a server that returns [status] for every request. */
private fun statusServer(status: Int): Pair<HttpServer, Int> {
    val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    srv.createContext("/robots.txt") { ex ->
        ex.sendResponseHeaders(status, -1)
        ex.responseBody.close()
    }
    srv.executor = Executors.newCachedThreadPool()
    srv.start()
    return srv to srv.address.port
}

// ──── RobotsCache Tests ────────────────────────────────────────────────────────

/**
 * Each test gets its own ephemeral server on a random port, so no inter-test
 * cache contamination is possible (the domain key includes the port).
 */
class RobotsCacheTest {

    @Test
    fun `disallowed path is blocked`() = runTest {
        val (srv, port) = robotsServer("User-agent: *\nDisallow: /admin")
        try {
            val rc = RobotsCache()
            assertFalse(rc.isAllowed("http://127.0.0.1:$port/admin/settings", "127.0.0.1:$port"))
        } finally { srv.stop(0) }
    }

    @Test
    fun `disallowed path prefix blocks all sub-paths`() = runTest {
        val (srv, port) = robotsServer("User-agent: *\nDisallow: /private")
        try {
            val rc = RobotsCache()
            assertFalse(rc.isAllowed("http://127.0.0.1:$port/private/secret/doc", "127.0.0.1:$port"))
        } finally { srv.stop(0) }
    }

    @Test
    fun `path outside any disallow rule is allowed`() = runTest {
        val (srv, port) = robotsServer("User-agent: *\nDisallow: /admin")
        try {
            val rc = RobotsCache()
            assertTrue(rc.isAllowed("http://127.0.0.1:$port/news/article", "127.0.0.1:$port"))
        } finally { srv.stop(0) }
    }

    @Test
    fun `allow overrides shorter disallow via longest match`() = runTest {
        val (srv, port) = robotsServer("""
            User-agent: *
            Disallow: /private
            Allow: /private/public
        """)
        try {
            val rc = RobotsCache()
            // /private/public wins over /private
            assertTrue(rc.isAllowed("http://127.0.0.1:$port/private/public/doc", "127.0.0.1:$port"))
            // /private alone is still blocked
            assertFalse(rc.isAllowed("http://127.0.0.1:$port/private/hidden", "127.0.0.1:$port"))
        } finally { srv.stop(0) }
    }

    @Test
    fun `disallow slash blocks every path`() = runTest {
        val (srv, port) = robotsServer("User-agent: *\nDisallow: /")
        try {
            val rc = RobotsCache()
            assertFalse(rc.isAllowed("http://127.0.0.1:$port/any/path", "127.0.0.1:$port"))
            assertFalse(rc.isAllowed("http://127.0.0.1:$port/", "127.0.0.1:$port"))
        } finally { srv.stop(0) }
    }

    @Test
    fun `inline comments are stripped from directive lines`() = runTest {
        val (srv, port) = robotsServer("""
            User-agent: *
            Disallow: /secret # this is a comment, not part of the path
        """)
        try {
            val rc = RobotsCache()
            assertFalse(rc.isAllowed("http://127.0.0.1:$port/secret/file", "127.0.0.1:$port"))
        } finally { srv.stop(0) }
    }

    @Test
    fun `rules for a specific agent do not apply to wildcard crawl`() = runTest {
        val (srv, port) = robotsServer("""
            User-agent: OtherBot
            Disallow: /other-only

            User-agent: *
            Disallow: /admin
        """)
        try {
            val rc = RobotsCache()
            // OtherBot is blocked from /other-only, but our wildcard agent is not
            assertTrue(rc.isAllowed("http://127.0.0.1:$port/other-only/page", "127.0.0.1:$port"))
            // Wildcard Disallow still applies
            assertFalse(rc.isAllowed("http://127.0.0.1:$port/admin/page", "127.0.0.1:$port"))
        } finally { srv.stop(0) }
    }

    @Test
    fun `404 robots txt means everything is allowed`() = runTest {
        val (srv, port) = statusServer(404)
        try {
            val rc = RobotsCache()
            assertTrue(rc.isAllowed("http://127.0.0.1:$port/admin", "127.0.0.1:$port"))
            assertTrue(rc.isAllowed("http://127.0.0.1:$port/private/data", "127.0.0.1:$port"))
        } finally { srv.stop(0) }
    }

    @Test
    fun `5xx robots txt response fails open and allows all`() = runTest {
        val (srv, port) = statusServer(503)
        try {
            val rc = RobotsCache()
            assertTrue(rc.isAllowed("http://127.0.0.1:$port/page", "127.0.0.1:$port"))
        } finally { srv.stop(0) }
    }

    @Test
    fun `unreachable host fails open and allows all paths`() = runTest {
        val rc = RobotsCache()
        assertTrue(rc.isAllowed("http://nonexistent.host.invalid/page", "nonexistent.host.invalid"))
    }

    @Test
    fun `malformed URL path defaults to root and does not throw`() = runTest {
        // No disallow rule at root → allowed even with a broken URL
        val (srv, port) = robotsServer("User-agent: *\nDisallow: /admin")
        try {
            val rc = RobotsCache()
            assertTrue(rc.isAllowed("htt p://[invalid", "127.0.0.1:$port"))
        } finally { srv.stop(0) }
    }

    @Test
    fun `empty robots txt allows everything`() = runTest {
        val (srv, port) = robotsServer("")
        try {
            val rc = RobotsCache()
            assertTrue(rc.isAllowed("http://127.0.0.1:$port/admin", "127.0.0.1:$port"))
        } finally { srv.stop(0) }
    }

    @Test
    fun `cache returns consistent results across repeated calls within ttl`() = runTest {
        val (srv, port) = robotsServer("User-agent: *\nDisallow: /admin")
        try {
            val rc = RobotsCache(cacheTtlMs = 60_000L)
            val domain = "127.0.0.1:$port"
            assertFalse(rc.isAllowed("http://$domain/admin/x", domain))
            assertFalse(rc.isAllowed("http://$domain/admin/y", domain))
        } finally { srv.stop(0) }
    }

    @Test
    fun `ttl of zero re-fetches on every call but stays consistent`() = runTest {
        val (srv, port) = robotsServer("User-agent: *\nDisallow: /admin")
        try {
            val rc = RobotsCache(cacheTtlMs = 0L)
            val domain = "127.0.0.1:$port"
            assertFalse(rc.isAllowed("http://$domain/admin/x", domain))
            assertFalse(rc.isAllowed("http://$domain/admin/y", domain))
        } finally { srv.stop(0) }
    }
}

// ──── DefaultDownloader Tests ─────────────────────────────────────────────────

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
        val content = RawContent(url, "text/html", "html".toByteArray(), "hash")

        coEvery { mockRobotsCache.isAllowed(url, "example.com") } returns true

        val deferred = CompletableDeferred(Result.success(content))
        coEvery { mockDispatcher.dispatch(url, ip, any()) } returns deferred

        val result = downloader.download(url, ip)

        assertTrue(result.isSuccess)
        assertEquals(content, result.getOrNull())
    }

    @Test
    fun `test download timeout is correctly converted to failure`() = runTest {
        val downloader = DefaultDownloader(mockDispatcher, mockRobotsCache, timeoutMs = 10L)

        val url = "https://example.com/slow"
        val ip = "1.2.3.4"

        coEvery { mockRobotsCache.isAllowed(url, "example.com") } returns true

        val deferred = CompletableDeferred<Result<RawContent>>()
        coEvery { mockDispatcher.dispatch(url, ip, any()) } returns deferred

        val result = downloader.download(url, ip)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("timed out") == true)
    }
}
