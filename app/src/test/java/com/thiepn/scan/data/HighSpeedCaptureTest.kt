package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighSpeedCaptureTest {
    @Test
    fun closePerceptualFingerprintsAreSuppressedConservatively() {
        val candidate = CaptureFingerprint(
            hashHex = "0f0f0f0f0f0f0f0f",
            meanLuma = 0.51f,
            edgeEnergy = 0.12f,
            aspectRatio = 1.414f,
            qualityScore = 0.82f
        )

        assertTrue(
            CaptureFrameAnalyzer.isLikelyDuplicate(
                candidate = candidate,
                referenceHashHex = "0f0f0f0f0f0f0f0e",
                referenceMeanLuma = 0.50f,
                referenceEdgeEnergy = 0.11f,
                referenceAspectRatio = 1.41f
            )
        )
    }

    @Test
    fun visuallyDifferentFramesAreNeverSuppressed() {
        val candidate = CaptureFingerprint(
            hashHex = "ffffffffffffffff",
            meanLuma = 0.70f,
            edgeEnergy = 0.25f,
            aspectRatio = 1.40f,
            qualityScore = 0.90f
        )

        assertFalse(
            CaptureFrameAnalyzer.isLikelyDuplicate(
                candidate = candidate,
                referenceHashHex = "0000000000000000",
                referenceMeanLuma = 0.45f,
                referenceEdgeEnergy = 0.08f,
                referenceAspectRatio = 1.40f
            )
        )
    }

    @Test
    fun sessionProgressUsesTerminalCapturedFrames() {
        val session = CaptureSessionEntity(
            id = "session",
            documentId = "doc",
            scanMode = ScanMode.DOCUMENT.name,
            startedAt = 1L,
            updatedAt = 2L,
            status = CaptureSessionStatus.PROCESSING.name,
            capturedCount = 120,
            processedCount = 75,
            duplicateCount = 4,
            lowQualityCount = 2,
            failedCount = 0
        )

        assertEquals(0.625f, session.progressFraction, 0.0001f)
        assertFalse(session.isTerminal)
    }

    @Test
    fun onlyMultipageWorkflowsOfferRapidCapture() {
        val supported = ScanMode.entries.filter {
            ScanModeProfiles.forMode(it).supportsHighSpeedCapture
        }.toSet()

        assertEquals(
            setOf(
                ScanMode.DOCUMENT,
                ScanMode.BOOK,
                ScanMode.FORM,
                ScanMode.NOTES
            ),
            supported
        )
    }
}
