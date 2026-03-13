package com.fzizzi.crawler.logging

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

enum class LogCategory {
    FRONTIER,
    DNS,
    DOWNLOADER,
    PARSER,
    FILTER,
    EXTRACTOR,
    STORAGE,
    ORCHESTRATOR,
    CLUSTER,
    GENERAL
}

interface CrawlerLogger {

    fun log(level: LogLevel, category: LogCategory, message: String, throwable: Throwable? = null)

    fun debug(category: LogCategory, message: String) =
        log(LogLevel.DEBUG, category, message)

    fun info(category: LogCategory, message: String) =
        log(LogLevel.INFO, category, message)

    fun warn(category: LogCategory, message: String, throwable: Throwable? = null) =
        log(LogLevel.WARN, category, message, throwable)

    fun error(category: LogCategory, message: String, throwable: Throwable? = null) =
        log(LogLevel.ERROR, category, message, throwable)
}

object NoOpLogger : CrawlerLogger {
    override fun log(level: LogLevel, category: LogCategory, message: String, throwable: Throwable?) = Unit
}
