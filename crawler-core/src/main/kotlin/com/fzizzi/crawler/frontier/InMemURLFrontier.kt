package com.fzizzi.crawler.frontier

class InMemURLFrontier : URLFrontier {
    private val urls = LinkedHashSet<String>()

    override suspend fun add(url: String) {
        urls.add(url)
    }

    override suspend fun addAll(urls: List<String>) {
        this.urls.addAll(urls)
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
