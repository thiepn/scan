package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComplianceSettingsTest {
    @Test
    fun codecRoundTripsArchivalAccessibilitySettings() {
        val settings = ComplianceSettings(
            pdfStandard = PdfStandard.PDF_A_2B,
            accessibilityMode = AccessibilityMode.TAGGED_OCR,
            documentLanguage = "de-DE",
            optimization = ExportOptimization.BALANCED,
            validateAfterExport = true
        )
        assertEquals(
            settings.normalized(),
            ComplianceSettingsCodec.decode(
                ComplianceSettingsCodec.encode(settings)
            )
        )
    }

    @Test
    fun pdfARejectsEncryptionAndRequiresRebuild() {
        val settings = ComplianceSettings(
            pdfStandard = PdfStandard.PDF_A_1B
        )
        assertTrue(settings.isPdfA())
        assertFalse(settings.allowsEncryption())
        assertTrue(settings.requiresNormalizedRebuild())
    }

    @Test
    fun languageNormalizationIsConservative() {
        assertEquals(
            "ko-KR",
            ComplianceSettings.normalizeLanguage("ko_KR")
        )
        assertEquals(
            "en",
            ComplianceSettings.normalizeLanguage("not a language tag")
        )
    }
}
