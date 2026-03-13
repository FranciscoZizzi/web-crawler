package com.fzizzi.crawler.downloader

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class DefaultDownloadDispatcherTest {

    companion object {
        private lateinit var server: HttpServer
        private var serverPort: Int = 0
        private val executor = Executors.newCachedThreadPool()

        @JvmStatic
        @BeforeAll
        fun startServer() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            
            // Create a simple context that returns a known HTML body
            server.createContext("/success") { exchange ->
                val response = "<html><body>Success</body></html>"
                exchange.sendResponseHeaders(200, response.length.toLong())
                exchange.responseBody.use { os ->
                    os.write(response.toByteArray())
                }
            }
            
            // Create a slow endpoint to test timeouts
            server.createContext("/timeout") { exchange ->
                Thread.sleep(1500) // Sleeps before responding
                val response = "<html><body>Timeout</body></html>"
                exchange.sendResponseHeaders(200, response.length.toLong())
                exchange.responseBody.use { os ->
                    os.write(response.toByteArray())
                }
            }

            // Create an error endpoint
            server.createContext("/error") { exchange ->
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
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

    @Test
    fun `test dispatch successful http get`() = runTest {
        val scope = CoroutineScope(Dispatchers.Default)
        val dispatcher = DefaultDownloadDispatcher(scope, numWorkers = 2)
        val url = "http://localhost:$serverPort/success"

        val deferredResult = dispatcher.dispatch(url, "127.0.0.1", 2000)
        val result = deferredResult.await()

        assertTrue(result.isSuccess)
        val content = result.getOrNull()
        assertNotNull(content)
        assertEquals(url, content?.url)
        assertEquals("<html><body>Success</body></html>", content?.text)
        
        scope.cancel()
    }

    @Test
    fun `test dispatch times out`() = runTest {
        val scope = CoroutineScope(Dispatchers.Default)
        val dispatcher = DefaultDownloadDispatcher(scope, numWorkers = 2)
        val url = "http://localhost:$serverPort/timeout"

        // Setting timeout to 500ms, but endpoint takes 1500ms
        val deferredResult = dispatcher.dispatch(url, "127.0.0.1", 500)
        val result = deferredResult.await()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is java.net.SocketTimeoutException)
        
        scope.cancel()
    }

    @Test
    fun `test dispatch handles http errors`() = runTest {
        val scope = CoroutineScope(Dispatchers.Default)
        val dispatcher = DefaultDownloadDispatcher(scope, numWorkers = 2)
        val url = "http://localhost:$serverPort/error"

        val deferredResult = dispatcher.dispatch(url, "127.0.0.1", 2000)
        val result = deferredResult.await()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("code 500") == true)
        
        scope.cancel()
    }
}
