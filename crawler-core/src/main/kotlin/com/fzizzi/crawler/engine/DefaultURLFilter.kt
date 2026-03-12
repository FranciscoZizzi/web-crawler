package com.fzizzi.crawler.engine

import java.net.URI

class DefaultURLFilter(
    private val maxLength: Int = 2000,
    private val maxPathRepetitions: Int = 3
) : IURLFilter {

    override suspend fun isAllowed(url: String): Boolean {
        if (url.length > maxLength) {
            return false
        }

        try {
            val uri = URI(url)
            val path = uri.path ?: return true
            
            val segments = path.split("/").filter { it.isNotEmpty() }
            
            if (segments.isEmpty()) return true

            if (hasRepeatingPattern(segments, maxPathRepetitions)) {
                return false
            }

        } catch (e: Exception) {
            return false
        }

        return true
    }

    private fun hasRepeatingPattern(segments: List<String>, maxRepetitions: Int): Boolean {
        val n = segments.size
        for (patternLength in 1..n / 2) {
            var repetitions = 1
            var i = 0
            while (i <= n - 2 * patternLength) {
                var isMatch = true
                for (j in 0 until patternLength) {
                    if (segments[i + j] != segments[i + patternLength + j]) {
                        isMatch = false
                        break
                    }
                }
                if (isMatch) {
                    repetitions++
                    if (repetitions > maxRepetitions) return true
                    i += patternLength
                } else {
                    repetitions = 1
                    i++
                }
            }
        }
        return false
    }
}
