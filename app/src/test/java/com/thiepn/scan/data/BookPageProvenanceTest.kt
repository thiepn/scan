package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BookPageProvenanceTest {
    @Test
    fun normalPageDefaultsHaveNoBookProvenance() {
        val page = PageEntity(
            id = "page",
            documentId = "doc",
            position = 0,
            imagePath = "/tmp/page.jpg",
            width = 1200,
            height = 1600
        )

        assertNull(page.sourceSpreadPageId)
        assertNull(page.bookSide)
        assertNull(page.bookSplitConfidence)
        assertEquals(0f, page.bookDewarpStrength, 0.0001f)
        assertFalse(page.preservedBookSource)
        assertFalse(page.bookReviewResolved)
    }
}
