package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTextSummaryTest {
    @Test
    fun smallDocumentsRemainLossless() {
        val pages = listOf(
            "first page",
            "second page"
        )

        assertEquals(
            "first page\n\nsecond page",
            DocumentTextSummary.build(pages)
        )
    }

    @Test
    fun largeDocumentsAreBounded() {
        val pages = List(500) { index ->
            "Page $index " + "x".repeat(2000)
        }

        val summary = DocumentTextSummary.build(pages)

        assertTrue(
            summary.length <=
                DocumentTextSummary.MAX_CHARS
        )
    }

    @Test
    fun boundedSummarySamplesAcrossDocument() {
        val pages = List(20) { index ->
            "marker-$index " + "x".repeat(3000)
        }

        val summary = DocumentTextSummary.build(
            pages,
            maxChars = 20_000
        )

        assertTrue(summary.contains("marker-0"))
        assertTrue(summary.contains("marker-10"))
        assertTrue(summary.contains("marker-19"))
    }

    @Test
    fun streamingAccumulatorStaysBounded() {
        val accumulator = DocumentTextSummary.Accumulator(
            pageCount = 500,
            maxChars = 20_000
        )
        repeat(500) { index ->
            accumulator.add(
                index,
                "page-$index " + "x".repeat(3000)
            )
        }

        val summary = accumulator.build()
        assertTrue(summary.length <= 20_000)
        assertTrue(summary.contains("page-0"))
        assertTrue(summary.contains("page-499"))
    }

    @Test
    fun blankPagesDoNotConsumeBudget() {
        assertEquals(
            "useful",
            DocumentTextSummary.build(
                listOf("", "  ", "useful")
            )
        )
    }
}
