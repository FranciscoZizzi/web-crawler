package com.fzizzi.crawler.parser

import com.fzizzi.crawler.extractor.DefaultContentParser
import com.fzizzi.crawler.extractor.exceptions.InvalidHtmlContentException
import com.fzizzi.crawler.model.HTMLContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class DefaultContentParserTest {

    private val minContentLength = 50
    private val parser = DefaultContentParser(minContentLength)

    @Test
    fun `test reject too short content`() = runTest {
        val url = "url"
        val content = "<html><body>hi</body></html>"
        val shortContent = HTMLContent(url, content, "hash")
        assertFailsWith<InvalidHtmlContentException>(
            "Content too short for url: $url. Length is ${content.length}, should be at least $minContentLength"
        ) { parser.parseAndValidate(shortContent).onFailure { throw it } }
    }

    @Test
    fun `test reject missing html tag`() = runTest {
        val longText = "a".repeat(100)
        val url = "url"
        val content = "Some random long text without proper markup: $longText"
        val missingHtml = HTMLContent(url, content, "hash")
        assertFailsWith<InvalidHtmlContentException>(
            "Invalid html for url: $url"
        ) { parser.parseAndValidate(missingHtml).onFailure { throw it } }
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
        assertTrue(parser.parseAndValidate(content).isSuccess)
    }
}
