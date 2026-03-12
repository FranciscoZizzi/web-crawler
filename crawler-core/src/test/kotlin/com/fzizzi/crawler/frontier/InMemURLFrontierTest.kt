package com.fzizzi.crawler.frontier

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InMemURLFrontierTest {

    @Test
    fun `test add and getNext FIFO behavior`() = runTest {
        val frontier = InMemURLFrontier()
        assertTrue(frontier.isEmpty())

        frontier.add("https://example.com/1")
        frontier.add("https://example.com/2")
        
        assertFalse(frontier.isEmpty())
        
        assertEquals("https://example.com/1", frontier.getNext())
        assertEquals("https://example.com/2", frontier.getNext())
        assertNull(frontier.getNext())
        assertTrue(frontier.isEmpty())
    }

    @Test
    fun `test addAll handles duplicates`() = runTest {
        val frontier = InMemURLFrontier()
        frontier.addAll(listOf("https://a.com", "https://b.com", "https://a.com"))

        assertEquals("https://a.com", frontier.getNext())
        assertEquals("https://b.com", frontier.getNext())
        assertNull(frontier.getNext())
    }

    @Test
    fun `test empty frontier returns null`() = runTest {
        val frontier = InMemURLFrontier()
        assertNull(frontier.getNext())
    }
}
