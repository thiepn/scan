package com.thiepn.scan.data

/**
 * Loads OCR-search hits in bounded batches, restoring the ranking returned by FTS.
 *
 * A Room IN query does not preserve the order of its input IDs. Search must preserve
 * the title/FTS relevance ordering, and large libraries must not exceed SQLite's
 * bind-variable budget or issue one database query per matching document.
 */
internal object RankedDocumentLoader {
    private const val MAX_BATCH_SIZE = 900

    suspend fun <T> load(
        rankedIds: List<String>,
        fetchBatch: suspend (List<String>) -> List<T>,
        getId: (T) -> String
    ): List<T> {
        if (rankedIds.isEmpty()) return emptyList()

        val orderedIds = rankedIds.distinct()
        val fetchedById = HashMap<String, T>(orderedIds.size)

        for (batch in orderedIds.chunked(MAX_BATCH_SIZE)) {
            for (item in fetchBatch(batch)) {
                val id = getId(item)
                if (id in batch) {
                    fetchedById[id] = item
                }
            }
        }
        // A document can disappear between the FTS query and the document fetch.
        return orderedIds.mapNotNull(fetchedById::get)
    }
}
