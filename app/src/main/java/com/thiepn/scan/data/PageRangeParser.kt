package com.thiepn.scan.data

object PageRangeParser {
    fun parse(spec: String, maxPage: Int): List<Int> {
        require(maxPage > 0) { "Document has no pages" }
        val normalized = spec.trim()
        require(normalized.isNotEmpty()) { "Enter at least one page" }

        val pages = sortedSetOf<Int>()
        normalized.split(',').forEach { rawPart ->
            val part = rawPart.trim()
            require(part.isNotEmpty()) { "Invalid page range" }

            if ('-' in part) {
                val pieces = part.split('-')
                require(pieces.size == 2) { "Invalid range: $part" }
                val start = pieces[0].trim().toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid page: ${pieces[0].trim()}")
                val end = pieces[1].trim().toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid page: ${pieces[1].trim()}")
                require(start <= end) { "Range must go forward: $part" }
                require(start >= 1 && end <= maxPage) {
                    "Pages must be between 1 and $maxPage"
                }
                for (page in start..end) pages += page
            } else {
                val page = part.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid page: $part")
                require(page in 1..maxPage) {
                    "Pages must be between 1 and $maxPage"
                }
                pages += page
            }
        }

        require(pages.isNotEmpty()) { "No pages selected" }
        return pages.toList()
    }
}
