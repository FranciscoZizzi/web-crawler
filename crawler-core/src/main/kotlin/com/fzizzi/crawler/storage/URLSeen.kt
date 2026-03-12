package com.fzizzi.crawler.storage

interface URLSeen {
    suspend fun isSeen(url: String): Boolean
    suspend fun add(url: String)
}
