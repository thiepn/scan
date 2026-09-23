package com.thiepn.scan.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookReviewPolicyTest {
    @Test
    fun wideUnresolvedPageWithEvidenceNeedsReview() {
        val page = page(
            width = 1800,
            height = 1200,
            confidence = 0.52f
        )

        assertTrue(BookReviewPolicy.needsManualReview(page))
    }

    @Test
    fun keepSingleResolutionRemovesPageFromReviewQueue() {
        val page = page(
            width = 1800,
            height = 1200,
            confidence = 0.52f,
            resolved = true
        )

        assertFalse(BookReviewPolicy.needsManualReview(page))
        assertTrue(BookReviewPolicy.canOpenManualReview(page))
    }

    @Test
    fun derivedBookPagesNeverEnterSpreadReviewQueue() {
        val page = page(
            width = 1800,
            height = 1200,
            confidence = 0.90f,
            sourceSpreadPageId = "spread"
        )

        assertFalse(BookReviewPolicy.needsManualReview(page))
        assertFalse(BookReviewPolicy.canOpenManualReview(page))
    }

    @Test
    fun portraitSinglePageDoesNotNeedSpreadReview() {
        val page = page(
            width = 1000,
            height = 1500,
            confidence = 0.20f
        )

        assertFalse(BookReviewPolicy.needsManualReview(page))
        assertFalse(BookReviewPolicy.canOpenManualReview(page))
    }

    private fun page(
        width: Int,
        height: Int,
        confidence: Float?,
        resolved: Boolean = false,
        sourceSpreadPageId: String? = null
    ): PageEntity =
        PageEntity(
            id = "page",
            documentId = "doc",
            position = 0,
            imagePath = "/tmp/page.jpg",
            width = width,
            height = height,
            bookSplitConfidence = confidence,
            bookReviewResolved = resolved,
            sourceSpreadPageId = sourceSpreadPageId
        )
}
