package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PageRangeParserTest {
    @Test
    fun parsesSinglePagesAndRanges() {
        assertEquals(
            listOf(1, 2, 3, 5, 8, 9, 10),
            PageRangeParser.parse("1-3, 5, 8-10", 10)
        )
    }

    @Test
    fun removesDuplicatesAndSorts() {
        assertEquals(
            listOf(1, 2, 3),
            PageRangeParser.parse("3,1-2,2", 5)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPagesOutsideDocument() {
        PageRangeParser.parse("1,6", 5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsReverseRange() {
        PageRangeParser.parse("4-2", 5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyInput() {
        PageRangeParser.parse(" ", 5)
    }
}
