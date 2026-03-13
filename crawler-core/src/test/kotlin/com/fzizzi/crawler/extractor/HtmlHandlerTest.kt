package com.fzizzi.crawler.extractor

import com.fzizzi.crawler.model.RawContent
import com.fzizzi.crawler.parser.ContentParser
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HtmlHandlerTest {

    private val mockParser: ContentParser = mockk()
    private val handler = HtmlHandler(mockParser)

    @Test
    fun `test canHandle matches HTML mime types`() {
        assertTrue(handler.canHandle("text/html"))
        assertTrue(handler.canHandle("text/HTML"))
        assertTrue(handler.canHandle("application/xhtml+xml"))
        assertTrue(handler.canHandle("text/html; charset=UTF-8"))
        
        assertFalse(handler.canHandle("image/png"))
        assertFalse(handler.canHandle("application/pdf"))
    }

    @Test
    fun `test handle rejects invalid content`() = runTest {
        val url = "https://example.com"
        val content = RawContent(url, "text/html", "invalid".toByteArray(), "hash123")
        
        coEvery { mockParser.parseAndValidate(any()) } returns false

        val result = handler.handle(content)
        
        assertTrue(result.discoveredLinks.isEmpty())
        assertTrue(result.extractedMetadata.isEmpty())
    }

    @Test
    fun `test handle extracts links and metadata for valid content`() = runTest {
        val url = "https://example.com"
        val html = "<html><body><a href='/1'>link</a></body></html>"
        val content = RawContent(url, "text/html", html.toByteArray(), "hash123")
        val expectedLinks = listOf("https://example.com/1")
        
        coEvery { mockParser.parseAndValidate(match { it.hash == "hash123" && it.url == url }) } returns true

        val result = handler.handle(content)
        
        assertEquals(expectedLinks, result.discoveredLinks)
        assertEquals("hash123", result.extractedMetadata["html_hash"])
    }
}
