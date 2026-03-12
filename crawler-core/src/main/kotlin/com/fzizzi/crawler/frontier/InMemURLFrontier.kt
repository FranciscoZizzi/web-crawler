package com.fzizzi.crawler.frontier

import com.fzizzi.crawler.protocol.IURLFrontier

class InMemURLFrontier : IURLFrontier {
    private val urls = LinkedHashSet<String>()

    override suspend fun add(url: String) {
        urls.add(url)
    }

    override suspend fun addAll(newUrls: List<String>) {
        urls.addAll(newUrls)
    }

    override suspend fun getNext(): String? {
        val next = urls.firstOrNull()
        if (next != null) {
            urls.remove(next)
        }
        return next
    }

    override suspend fun isEmpty(): Boolean {
        return urls.isEmpty()
    }
}
