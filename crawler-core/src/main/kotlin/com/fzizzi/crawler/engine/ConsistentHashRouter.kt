package com.fzizzi.crawler.engine

import java.security.MessageDigest
import java.util.SortedMap
import java.util.TreeMap

class ConsistentHashRouter<T>() {
    // Defines method to evaluate if this crawler node owns this partition
    fun isLocal(key: String): Boolean {
        return true // Default behavior assumes 1 node unless partitioned
    }
}
