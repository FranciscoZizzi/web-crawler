package com.fzizzi.crawler.parser

import com.fzizzi.crawler.model.HTMLContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DefaultContentParserTest {

    private val parser = DefaultContentParser(minContentLength = 50)

    @Test
    fun `test reject too short content`() = runTest {
        val shortContent = HTMLContent("url", "<html><body>hi</body></html>", "hash")
        assertFalse(parser.parseAndValidate(shortContent))
    }

    @Test
    fun `test reject missing html tag`() = runTest {
        val longText = "a".repeat(100)
        val missingHtml = HTMLContent("url", "Some random long text without proper markup: $longText", "hash")
        assertFalse(parser.parseAndValidate(missingHtml))
    }

    @Test
    fun `test reject noindex metadata`() = runTest {
        val htmlContent = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="robots" content="noindex">
            </head>
            <body>
                <p>This is a long enough text but it should not be indexed.</p>
                <p>Because it contains the noindex directive.</p>
            </body>
            </html>
        """.trimIndent()
        
        val content = HTMLContent("url", htmlContent, "hash")
        assertFalse(parser.parseAndValidate(content))
    }

    @Test
    fun `test allow valid html`() = runTest {
        val htmlContent = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Valid Page</title>
            </head>
            <body>
                <p>This is a long enough valid html page.</p>
                <p>It contains multiple lines to pass the minimum length check.</p>
                <p>And it does not have the noindex metadata tag so it should pass.</p>
            </body>
            </html>
        """.trimIndent()
        
        val content = HTMLContent("url", htmlContent, "hash")
        assertTrue(parser.parseAndValidate(content))
    }
}
