package com.thiepn.scan.data

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfQualityTest {
    @Test
    fun originalDoesNotDownsample() {
        assertNull(PdfQuality.ORIGINAL.maxLongEdge)
    }

    @Test
    fun compressedPresetsDecreaseResolutionAndQuality() {
        assertTrue(PdfQuality.HIGH.maxLongEdge!! > PdfQuality.BALANCED.maxLongEdge!!)
        assertTrue(PdfQuality.BALANCED.maxLongEdge!! > PdfQuality.SMALL.maxLongEdge!!)
        assertTrue(PdfQuality.HIGH.jpegQuality > PdfQuality.BALANCED.jpegQuality)
        assertTrue(PdfQuality.BALANCED.jpegQuality > PdfQuality.SMALL.jpegQuality)
    }
}
