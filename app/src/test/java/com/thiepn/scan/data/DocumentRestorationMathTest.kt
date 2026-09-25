package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentRestorationMathTest {
    @Test
    fun zeroStrengthLeavesIlluminationUnchanged() {
        assertEquals(
            1f,
            RestorationMath.illuminationFactor(
                observed = 0.45f,
                target = 0.90f,
                strength = 0f
            ),
            0.0001f
        )
    }

    @Test
    fun illuminationCorrectionBrightensShadowedPaper() {
        val factor = RestorationMath.illuminationFactor(
            observed = 0.42f,
            target = 0.90f,
            strength = 1f
        )

        assertTrue(factor > 1f)
        assertTrue(factor <= 1.48f)
    }

    @Test
    fun whiteBalanceGainMovesWarmChannelTowardNeutral() {
        val redGain = RestorationMath.whiteBalanceGain(
            channelMean = 0.90f,
            neutralMean = 0.75f,
            strength = 1f
        )
        val blueGain = RestorationMath.whiteBalanceGain(
            channelMean = 0.60f,
            neutralMean = 0.75f,
            strength = 1f
        )

        assertTrue(redGain < 1f)
        assertTrue(blueGain > 1f)
    }

    @Test
    fun receiptTargetIsWhiterThanBookTarget() {
        val observed = 0.72f
        val receipt = RestorationMath.paperTarget(
            RestorationProfile.RECEIPT,
            observed
        )
        val book = RestorationMath.paperTarget(
            RestorationProfile.BOOK,
            observed
        )

        assertTrue(receipt > book)
    }

    @Test
    fun adaptiveThresholdTracksLocalPaperBrightness() {
        val darkRegion = RestorationMath.adaptiveThreshold(
            localPaper = 0.58f,
            strength = 0.8f,
            profile = RestorationProfile.RECEIPT
        )
        val brightRegion = RestorationMath.adaptiveThreshold(
            localPaper = 0.92f,
            strength = 0.8f,
            profile = RestorationProfile.RECEIPT
        )

        assertTrue(brightRegion > darkRegion)
    }

    @Test
    fun adaptiveMixSeparatesInkFromPaper() {
        val darkInk = RestorationMath.adaptiveMix(
            luminance = 0.28f,
            localPaper = 0.86f,
            strength = 0.9f,
            profile = RestorationProfile.DOCUMENT
        )
        val paper = RestorationMath.adaptiveMix(
            luminance = 0.90f,
            localPaper = 0.90f,
            strength = 0.9f,
            profile = RestorationProfile.DOCUMENT
        )

        assertTrue(darkInk < 0.28f)
        assertTrue(paper > 0.90f)
    }
}
