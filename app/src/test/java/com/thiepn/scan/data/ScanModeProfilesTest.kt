package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanModeProfilesTest {
    @Test
    fun everyModeHasAProfileForItself() {
        ScanMode.entries.forEach { mode ->
            assertEquals(mode, ScanModeProfiles.forMode(mode).mode)
        }
    }

    @Test
    fun idCardUsesTwoStepHighQualityIdentityDefaults() {
        val profile = ScanModeProfiles.forMode(ScanMode.ID_CARD)

        assertTrue(profile.requiresTwoSidedCapture)
        assertEquals(1, profile.pageLimit)
        assertEquals(DocumentType.ID, profile.defaultDocumentType)
        assertEquals(ScanPreset.COLOR, profile.defaultPreset)
        assertEquals(PdfQuality.HIGH, profile.defaultPdfQuality)
    }

    @Test
    fun bookModeUsesMultipageCleanBookDefaults() {
        val profile = ScanModeProfiles.forMode(ScanMode.BOOK)

        assertEquals(DocumentType.BOOK, profile.defaultDocumentType)
        assertEquals(ScanPreset.CLEAN, profile.defaultPreset)
        assertEquals(PdfQuality.BALANCED, profile.defaultPdfQuality)
        assertTrue(profile.ocrEnabled)
        assertEquals(40, profile.pageLimit)
    }

    @Test
    fun photoModeIsVisualFirstAndDoesNotRunOcr() {
        val profile = ScanModeProfiles.forMode(ScanMode.PHOTO)

        assertFalse(profile.ocrEnabled)
        assertEquals(ScanPreset.ORIGINAL, profile.defaultPreset)
        assertEquals(PdfQuality.HIGH, profile.defaultPdfQuality)
    }

    @Test
    fun lowResolutionCaptureIsFlaggedByModeQualityRules() {
        assertNotNull(
            ScanModeProfiles.resolutionWarning(
                ScanMode.CERTIFICATE,
                width = 700,
                height = 990
            )
        )
        assertNull(
            ScanModeProfiles.resolutionWarning(
                ScanMode.CERTIFICATE,
                width = 1600,
                height = 2200
            )
        )
    }

    @Test
    fun cardAspectValidationAcceptsNormalCardAndFlagsSquareCapture() {
        assertNull(
            ScanModeProfiles.aspectRatioWarning(
                ScanMode.ID_CARD,
                width = 1590,
                height = 1000
            )
        )
        assertNotNull(
            ScanModeProfiles.aspectRatioWarning(
                ScanMode.ID_CARD,
                width = 1000,
                height = 1000
            )
        )
    }
}
