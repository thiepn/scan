package com.thiepn.scan.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSpreadScoringTest {
    @Test
    fun strongCenteredGutterAutoSplits() {
        val result = BookSpreadScoring.evaluate(
            aspect = 1.55f,
            gutterX = 0.50f,
            gutterContrast = 0.10f,
            seamStrength = 0.19f,
            consistency = 0.90f
        )

        assertTrue(result.likelySpread)
        assertTrue(result.autoSplitRecommended)
        assertTrue(result.dewarpStrength > 0f)
    }

    @Test
    fun mediumEvidenceRequiresReviewInsteadOfAutoSplit() {
        val result = BookSpreadScoring.evaluate(
            aspect = 1.45f,
            gutterX = 0.51f,
            gutterContrast = 0.055f,
            seamStrength = 0.10f,
            consistency = 0.62f
        )

        assertTrue(result.likelySpread)
        assertFalse(result.autoSplitRecommended)
    }

    @Test
    fun portraitPageNeverBecomesSpread() {
        val result = BookSpreadScoring.evaluate(
            aspect = 0.78f,
            gutterX = 0.50f,
            gutterContrast = 0.12f,
            seamStrength = 0.20f,
            consistency = 0.95f
        )

        assertFalse(result.likelySpread)
        assertFalse(result.autoSplitRecommended)
    }

    @Test
    fun strongOffCenterSeamIsRejected() {
        val result = BookSpreadScoring.evaluate(
            aspect = 1.60f,
            gutterX = 0.72f,
            gutterContrast = 0.12f,
            seamStrength = 0.22f,
            consistency = 0.90f
        )

        assertFalse(result.likelySpread)
    }

    @Test
    fun dewarpStrengthRemainsBounded() {
        val result = BookSpreadScoring.evaluate(
            aspect = 2.1f,
            gutterX = 0.50f,
            gutterContrast = 1f,
            seamStrength = 1f,
            consistency = 1f
        )

        assertTrue(result.dewarpStrength in 0f..0.105f)
    }
}
