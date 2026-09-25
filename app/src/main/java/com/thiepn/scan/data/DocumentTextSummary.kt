package com.thiepn.scan.data

object DocumentTextSummary {
    const val MAX_CHARS = 128 * 1024
    private const val FIRST_PAGE_BUDGET = 16 * 1024
    private const val SEPARATOR = "\n\n"

    fun build(
        pageTexts: List<String>,
        maxChars: Int = MAX_CHARS
    ): String {
        if (maxChars <= 0) return ""
        val texts = pageTexts.filter { it.isNotBlank() }
        if (texts.isEmpty()) return ""

        val fullLength = texts.sumOf {
            it.length.toLong()
        } + SEPARATOR.length.toLong() *
            (texts.size - 1).toLong()
        if (fullLength <= maxChars.toLong()) {
            return texts.joinToString(SEPARATOR)
        }

        if (texts.size == 1) {
            return texts.first().take(maxChars)
        }

        val firstBudget = minOf(
            FIRST_PAGE_BUDGET,
            maxChars / 4,
            texts.first().length
        )
        val builder = StringBuilder(maxChars)
        builder.append(texts.first(), 0, firstBudget)

        val remainingCount = texts.size - 1
        val separatorBudget =
            SEPARATOR.length * remainingCount
        val remainingBudget = (
            maxChars - builder.length - separatorBudget
            ).coerceAtLeast(0)
        val perPage = if (remainingCount == 0) {
            0
        } else {
            remainingBudget / remainingCount
        }

        for (index in 1 until texts.size) {
            if (
                builder.length + SEPARATOR.length >=
                maxChars
            ) {
                break
            }
            builder.append(SEPARATOR)
            val available = maxChars - builder.length
            if (available <= 0) break
            val take = minOf(
                texts[index].length,
                perPage.coerceAtLeast(1),
                available
            )
            builder.append(texts[index], 0, take)
        }

        return builder.toString().take(maxChars)
    }
}
