package com.fzizzi.crawler.frontier

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DefaultURLFrontierTest {

    @Test
    fun `test prioritizer favors gov and edu`() {
        val prioritizer = DefaultPrioritizer(3) // maxPriority = 3
        assertEquals(2, prioritizer.getPriority("https://test.gov/page"))
        assertEquals(2, prioritizer.getPriority("https://university.edu"))
        assertEquals(0, prioritizer.getPriority("https://example.com"))
    }

    @Test
    fun `test back router groups matching hosts to same queue`() {
        val router = BackQueueRouter(10)
        
        val queue1 = router.getQueueIndex("https://example.com/page1")
        val queue2 = router.getQueueIndex("https://example.com/page2")
        val queue3 = router.getQueueIndex("https://another.com/page")
        
        assertEquals(queue1, queue2)
        assertNotEquals(queue1, queue3)
    }

    @Test
    fun `test politeness mechanism blocks concurrent host processing`() = runTest {
        val frontier = DefaultURLFrontier(numFrontQueues = 3, numBackQueues = 10)
        
        // Add two URLs from the SAME domain
        frontier.add("https://example.com/page1")
        frontier.add("https://example.com/page2")
        
        // The first getNext() should work
        val firstUrl = frontier.getNext()
        assertEquals("https://example.com/page1", firstUrl)
        
        // The second getNext() should return null (or block/empty) because the queue is locked
        val secondUrlAttempt = frontier.getNext()
        assertNull(secondUrlAttempt)
        
        // Only after marking the first one completed, we can get the second one
        frontier.markCompleted(firstUrl!!)
        
        val secondUrlSuccess = frontier.getNext()
        assertEquals("https://example.com/page2", secondUrlSuccess)
    }

    @Test
    fun `test interleaving hosts allows concurrent processing`() = runTest {
        val frontier = DefaultURLFrontier(numFrontQueues = 3, numBackQueues = 10)
        
        // Add two URLs from DIFFERENT domains
        frontier.add("https://example.com/page")
        frontier.add("https://another.com/page")
        
        // Bot should be retrievable since they map to different queues (statistically very likely with atomic incremetor)
        val url1 = frontier.getNext()
        val url2 = frontier.getNext()
        
        assertNotNull(url1)
        assertNotNull(url2)
        assertNotEquals(url1, url2)
    }
}
