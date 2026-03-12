package com.fzizzi.crawler.engine

import com.fzizzi.crawler.protocol.IURLFilter
import java.net.URI

class DefaultURLFilter(
    private val maxLength: Int = 2000,
    private val maxPathRepetitions: Int = 3
) : IURLFilter {

    override suspend fun isAllowed(url: String): Boolean {
        // 1. Spider Trap: Check maximum URL length
        if (url.length > maxLength) {
            return false
        }

        // 2. Spider Trap: Check for repeating path segments (e.g. /foo/bar/foo/bar)
        try {
            val uri = URI(url)
            val path = uri.path ?: return true
            
            // Split path into segments, ignoring empty strings from leading/trailing slashes
            val segments = path.split("/").filter { it.isNotEmpty() }
            
            if (segments.isEmpty()) return true

            // TODO extract to methods to improve readability and remove comments
            // Look for repeating sequences of segments
            // check if any single segment repeats more than maxPathRepetitions
            val segmentCounts = mutableMapOf<String, Int>()
            for (segment in segments) {
                val count = segmentCounts.getOrDefault(segment, 0) + 1
                if (count > maxPathRepetitions) {
                    return false
                }
                segmentCounts[segment] = count
            }

            // A more advanced heuristic check for repeating multi-segment patterns
            // (e.g., /a/b/a/b/a/b)
            if (hasRepeatingPattern(segments, maxPathRepetitions)) {
                return false
            }

        } catch (e: Exception) {
            // If URL is unparsable, we'll err on the side of caution and drop it
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
