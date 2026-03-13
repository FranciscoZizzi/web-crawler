package com.fzizzi.cli

import com.fzizzi.crawler.downloader.CachingDNSResolver
import com.fzizzi.crawler.downloader.DefaultDownloadDispatcher
import com.fzizzi.crawler.downloader.DefaultDownloader
import com.fzizzi.crawler.downloader.RobotsCache
import com.fzizzi.crawler.engine.CrawlerOrchestrator
import com.fzizzi.crawler.engine.DefaultURLFilter
import com.fzizzi.crawler.engine.URLFilter
import com.fzizzi.crawler.extractor.HtmlHandler
import com.fzizzi.crawler.frontier.DefaultURLFrontier
import com.fzizzi.crawler.logging.CrawlerLogger
import com.fzizzi.crawler.logging.LogCategory
import com.fzizzi.crawler.logging.LogLevel
import com.fzizzi.crawler.model.CrawlEvent
import com.fzizzi.crawler.parser.DefaultContentParser
import com.fzizzi.crawler.sink.CrawlResultSink
import com.fzizzi.crawler.storage.MemoryContentStorage
import com.fzizzi.crawler.storage.MemoryURLStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.PrintWriter
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

// ─── Logger ───────────────────────────────────────────────────────────────────

/**
 * Writes log lines to a .txt file.
 * Only emits entries whose [LogLevel] and [LogCategory] are in the active sets.
 */
class FileLogger(
    private val writer: PrintWriter,
    private val enabledLevels: Set<LogLevel>,
    private val enabledCategories: Set<LogCategory>
) : CrawlerLogger {
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    override fun log(level: LogLevel, category: LogCategory, message: String, throwable: Throwable?) {
        if (level !in enabledLevels || category !in enabledCategories) return
        val ts = LocalDateTime.now().format(fmt)
        writer.println("[$ts] [$level] [$category] $message")
        throwable?.let { writer.println("  → ${it.message}") }
        writer.flush()
    }
}

// ─── URL Filter ───────────────────────────────────────────────────────────────

/** Allows a URL only if it contains ALL of the given substrings. */
class ContainsFilter(private val substrings: List<String>) : URLFilter {
    override suspend fun isAllowed(url: String): Boolean =
        substrings.all { url.contains(it, ignoreCase = true) }
}

// ─── Sink ─────────────────────────────────────────────────────────────────────

private class CollectingSink : CrawlResultSink {
    val events = CopyOnWriteArrayList<CrawlEvent>()
    override suspend fun accept(event: CrawlEvent) { events.add(event) }
}

// ─── CLI Helpers ──────────────────────────────────────────────────────────────

private fun prompt(label: String): String {
    print("  $label: ")
    System.out.flush()
    return readLine()?.trim() ?: ""
}

private fun promptList(label: String, emptyMeaning: String = "(none)"): List<String> {
    println("  $label (comma-separated, leave blank for $emptyMeaning):")
    print("  > ")
    System.out.flush()
    val raw = readLine()?.trim() ?: return emptyList()
    return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

private fun <T : Enum<T>> promptEnumSet(label: String, values: Array<T>): Set<T> {
    val listed = values.joinToString(", ") { it.name }
    println("  $label")
    println("  Available: $listed")
    println("  (comma-separated, 'ALL' for everything, leave blank for none)")
    print("  > ")
    System.out.flush()
    val raw = readLine()?.trim()?.uppercase() ?: return emptySet()
    if (raw == "ALL" || raw.isBlank()) return if (raw == "ALL") values.toHashSet() else emptySet()
    val chosen = raw.split(",").map { it.trim() }
    return values.filter { it.name in chosen }.toHashSet()
}

private fun hr() = println("─".repeat(60))

// ─── Entry Point ──────────────────────────────────────────────────────────────

fun main() = runBlocking {
    println()
    println("╔══════════════════════════════════════════════════════════╗")
    println("║               Web Crawler  —  CLI Setup                 ║")
    println("╚══════════════════════════════════════════════════════════╝")
    println()

    // ── 1. Seed URLs ──────────────────────────────────────────────────────────
    hr()
    println("STEP 1 — Seed URLs")
    hr()
    val seeds = mutableListOf<String>()
    println("  Enter seed URLs one per line. Press ENTER on an empty line to finish.")
    while (true) {
        print("  URL ${seeds.size + 1}: ")
        System.out.flush()
        val line = readLine()?.trim() ?: break
        if (line.isEmpty()) break
        if (!line.startsWith("http")) {
            println("  ⚠  Must start with http/https — skipping.")
            continue
        }
        seeds.add(line)
    }
    if (seeds.isEmpty()) {
        println("\n  No seeds given — using default Wikipedia seed.")
        seeds.add("https://en.wikipedia.org/wiki/Web_crawler")
    }

    // ── 2. URL Filters ────────────────────────────────────────────────────────
    println()
    hr()
    println("STEP 2 — URL Filters")
    hr()
    val filterStrings = promptList(
        "Strings that crawled URLs must contain",
        emptyMeaning = "allow all"
    )
    val filters = buildList<URLFilter> {
        add(DefaultURLFilter())           // always-on: basic scheme/depth rules
        if (filterStrings.isNotEmpty()) add(ContainsFilter(filterStrings))
    }
    if (filterStrings.isEmpty()) println("  → No custom filter applied — all URLs allowed by default filter.")
    else println("  → URLs must contain: ${filterStrings.joinToString(", ")}")

    // ── 3. Log Configuration ──────────────────────────────────────────────────
    println()
    hr()
    println("STEP 3 — Log Configuration")
    hr()

    val enabledLevels = promptEnumSet("Which log LEVELS to capture:", LogLevel.values())
    val enabledCategories = promptEnumSet("Which log CATEGORIES to capture:", LogCategory.values())

    val logFileName = prompt("Log output file (e.g. crawl.txt, relative to working dir)").ifBlank { "crawl.log.txt" }
    val logPath = Paths.get(logFileName).toAbsolutePath()
    val logWriter = PrintWriter(logPath.toFile())
    val logger: CrawlerLogger = if (enabledLevels.isEmpty() || enabledCategories.isEmpty()) {
        println("  → No log levels/categories selected — logging disabled.")
        logWriter.close()
        com.fzizzi.crawler.logging.NoOpLogger
    } else {
        println("  → Logging ${enabledLevels.map { it.name }} × ${enabledCategories.map { it.name }} → $logPath")
        FileLogger(logWriter, enabledLevels, enabledCategories)
    }

    // ── 4. Runtime limits ─────────────────────────────────────────────────────
    println()
    hr()
    println("STEP 4 — Runtime Limits")
    hr()
    val maxPages = prompt("Max pages to crawl (leave blank for 200)").toIntOrNull() ?: 200
    val timeoutSec = prompt("Timeout in seconds (leave blank for 120)").toLongOrNull() ?: 120L
    println("  → Will stop after $maxPages pages or ${timeoutSec}s, whichever comes first.")

    // ── Build & Run ───────────────────────────────────────────────────────────
    println()
    println("╔══════════════════════════════════════════════════════════╗")
    println("║                   Starting Crawl …                      ║")
    println("╚══════════════════════════════════════════════════════════╝")

    val scope = CoroutineScope(Dispatchers.Default)
    val sink = CollectingSink()

    val frontier = DefaultURLFrontier(numFrontQueues = 3, numBackQueues = 10)
    val dnsResolver = CachingDNSResolver(scope, logger)
    val dispatcher = DefaultDownloadDispatcher(scope, numWorkers = 6)
    val robots = RobotsCache()
    val downloader = DefaultDownloader(dispatcher, robots, timeoutMs = 8_000L, logger)
    val parser = DefaultContentParser(minContentLength = 100)
    val urlStorage = MemoryURLStorage()
    val contentStorage = MemoryContentStorage()
    val htmlHandler = HtmlHandler(parser)

    val orchestrator = CrawlerOrchestrator(
        urlFrontier    = frontier,
        htmlDownloader = downloader,
        dnsResolver    = dnsResolver,
        handlers       = listOf(htmlHandler),
        contentStorage = contentStorage,
        urlFilters     = filters,
        urlStorage     = urlStorage,
        sink           = sink,
        logger         = logger
    )

    val startMs = System.currentTimeMillis()

    val progressJob = scope.launch {
        while (true) {
            val elapsed = (System.currentTimeMillis() - startMs) / 1000
            print("\r  [${elapsed}s] Crawled: ${sink.events.size} / $maxPages  ")
            System.out.flush()
            delay(2_000)
        }
    }

    withTimeoutOrNull(timeoutSec * 1_000L) {
        val crawlJob = launch(Dispatchers.Default) { orchestrator.start(seeds) }
        while (sink.events.size < maxPages) {
            delay(500)
            if (crawlJob.isCompleted) break
        }
        crawlJob.cancel()
    }

    progressJob.cancel()
    println()

    val elapsedSec = (System.currentTimeMillis() - startMs) / 1000
    val events = sink.events

    // ── Summary ───────────────────────────────────────────────────────────────
    println()
    println("╔══════════════════════════════════════════════════════════╗")
    println("║                      Results                            ║")
    println("╚══════════════════════════════════════════════════════════╝")
    println("  Duration : ${elapsedSec}s")
    println("  Pages    : ${events.size} crawled")
    println()
    println("── Top ${minOf(20, events.size)} crawled pages " + "─".repeat(30))
    events.take(20).forEachIndexed { i, event ->
        val kb = event.contentSizeBytes / 1024
        val ref = when (event.referrerUrls.size) {
            0    -> " (seed)"
            1    -> " ← ${event.referrerUrls[0].substringAfterLast('/')}"
            else -> " ← ${event.referrerUrls.size} referrers"
        }
        println("  ${(i + 1).toString().padStart(2)}. [${kb}KB] ${event.url}$ref")
    }
    if (events.size > 20) println("\n  … and ${events.size - 20} more.")

    if (logger !is com.fzizzi.crawler.logging.NoOpLogger) {
        println()
        println("  Log written to: $logPath")
    }

    // ── URL Report file ────────────────────────────────────────────────────────
    val reportFileName = "crawl-report-${System.currentTimeMillis()}.txt"
    val reportPath = Paths.get(reportFileName).toAbsolutePath()
    PrintWriter(reportPath.toFile()).use { out ->
        out.println("Web Crawler — URL Report")
        out.println("Generated : ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
        out.println("Duration  : ${elapsedSec}s")
        out.println("Total URLs: ${events.size}")
        out.println()
        out.println(String.format("%-6s  %-9s  %s", "REFS", "SIZE(KB)", "URL"))
        out.println("─".repeat(90))

        events
            .sortedByDescending { it.referrerUrls.size }
            .forEach { event ->
                out.println(
                    String.format(
                        "%-6d  %-9d  %s",
                        event.referrerUrls.size,
                        event.contentSizeBytes / 1024,
                        event.url
                    )
                )
            }
    }
    println()
    println("  URL report  : $reportPath")

    scope.cancel()
    println()
    println("Done.")
}
