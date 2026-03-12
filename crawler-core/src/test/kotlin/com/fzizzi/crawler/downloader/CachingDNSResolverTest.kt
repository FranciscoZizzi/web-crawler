package com.fzizzi.crawler.downloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import io.mockk.mockkStatic
import io.mockk.every
import io.mockk.unmockkAll
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress

class CachingDNSResolverTest {

    @BeforeEach
    fun setup() {
        mockkStatic(InetAddress::class)
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `test resolve returns IP and caches it`() = runTest {
        val mockAddress = mockk<InetAddress>()
        every { mockAddress.hostAddress } returns "192.168.0.1"
        every { InetAddress.getByName("example.com") } returns mockAddress

        val scope = CoroutineScope(Dispatchers.Default)
        val resolver = CachingDNSResolver(scope) // Use real-time scope to prevent virtual time infinite loops


        val result1 = resolver.resolve("example.com")
        assertTrue(result1.isSuccess)
        assertEquals("192.168.0.1", result1.getOrNull())

        // Resolve again to test cache hit
        val result2 = resolver.resolve("example.com")
        assertTrue(result2.isSuccess)
        assertEquals("192.168.0.1", result2.getOrNull())

        // Verify the static method was only called once, implying the second time it used cache
        verify(exactly = 1) { InetAddress.getByName("example.com") }
        
        scope.cancel()
    }

    @Test
    fun `test resolve handles unknown host gracefully`() = runTest {
        every { InetAddress.getByName("invalid.domain") } throws java.net.UnknownHostException("invalid.domain")
        
        val scope = CoroutineScope(Dispatchers.Default)
        val resolver = CachingDNSResolver(scope)

        val result = resolver.resolve("invalid.domain")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is java.net.UnknownHostException)
        
        scope.cancel()
    }
}
