package com.thiepn.scan.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankedDocumentLoaderTest {
    private data class Hit(val id: String)

    @Test
    fun preservesFtsRankInsteadOfDatabaseRowOrder() = runBlocking {
        val ranked = listOf("z", "a", "m")
        val result = RankedDocumentLoader.load(
            rankedIds = ranked,
            fetchBatch = { batch -> batch.sorted().map(::Hit) },
            getId = Hit::id
        )
        assertEquals(ranked, result.map { it.id })
    }

    @Test
    fun chunksLargeResultsUnderSqliteVariableLimit() = runBlocking {
        val ids = (0 until 2_005).map { "doc-$it" }
        val sizes = mutableListOf<Int>()
        val result = RankedDocumentLoader.load(
            rankedIds = ids,
            fetchBatch = { batch ->
                sizes += batch.size
                batch.reversed().map(::Hit)
            },
            getId = Hit::id
        )
        assertEquals(listOf(900, 900, 205), sizes)
        assertEquals(ids, result.map { it.id })
    }

    @Test
    fun removesDuplicateHitsAndSkipsDocumentsDeletedDuringQuery() = runBlocking {
        val result = RankedDocumentLoader.load(
            rankedIds = listOf("one", "gone", "one", "two"),
            fetchBatch = { batch ->
                batch.filterNot { it == "gone" }.map(::Hit)
            },
            getId = Hit::id
        )
        assertEquals(listOf("one", "two"), result.map { it.id })
    }

    @Test
    fun emptyQueryDoesNotTouchDatabase() = runBlocking {
        var calls = 0
        val result = RankedDocumentLoader.load(
            rankedIds = emptyList(),
            fetchBatch = { batch ->
                calls += 1
                batch.map(::Hit)
            },
            getId = Hit::id
        )
        assertTrue(result.isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun ignoresUnexpectedDatabaseRows() = runBlocking {
        val result = RankedDocumentLoader.load(
            rankedIds = listOf("a"),
            fetchBatch = { listOf(Hit("unrequested"), Hit("a")) },
            getId = Hit::id
        )
        assertEquals(listOf("a"), result.map { it.id })
    }
}
