package com.fzizzi.crawler.engine

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DefaultURLFilterTest {

    private val filter = DefaultURLFilter(maxLength = 100, maxPathRepetitions = 2)

    @Test
    fun `test allow normal urls`() = runTest {
        assertTrue(filter.isAllowed("https://example.com/about"))
        assertTrue(filter.isAllowed("https://example.com/products/123/edit"))
        assertTrue(filter.isAllowed("https://example.com/"))
    }

    @Test
    fun `test reject over max length`() = runTest {
        val longString = "a".repeat(150)
        val url = "https://example.com/$longString"
        assertFalse(filter.isAllowed(url))
    }

    @Test
    fun `test reject repeating single segment`() = runTest {
        // Repeated 3 times, max is 2
        val url = "https://example.com/foo/foo/foo"
        assertFalse(filter.isAllowed(url))
    }

    @Test
    fun `test reject repeating pattern of segments`() = runTest {
        // Pattern a/b repeated 3 times: /a/b/a/b/a/b
        val url = "https://example.com/a/b/a/b/a/b"
        assertFalse(filter.isAllowed(url))
    }

    @Test
    fun `test allow non-repeating mixed patterns`() = runTest {
        // Even if some segments repeat, if they don't exceed threshold contiguously, it's fine
        val url = "https://example.com/a/b/c/a/b/d/a/b"
        assertTrue(filter.isAllowed(url))
    }
}
