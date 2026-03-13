package com.fzizzi.crawler.logging

import java.time.Instant

class ConsoleLogger(private val minLevel: LogLevel = LogLevel.INFO) : CrawlerLogger {

    override fun log(level: LogLevel, category: LogCategory, message: String, throwable: Throwable?) {
        if (level < minLevel) return

        val timestamp = Instant.now()
        val levelTag  = level.name.padEnd(5)
        val catTag    = category.name.padEnd(12)
        val line      = "$timestamp [$levelTag] [$catTag] $message"

        if (level >= LogLevel.WARN) {
            System.err.println(line)
            throwable?.printStackTrace(System.err)
        } else {
            println(line)
        }
    }
}
