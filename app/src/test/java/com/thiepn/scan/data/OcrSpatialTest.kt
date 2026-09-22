package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OcrSpatialTest {
    @Test
    fun spatialLayoutRoundTripsWithoutLosingGeometryOrEmptyLanguageTags() {
        val result = OcrPageResult(
            text = "Hello world",
            blocks = listOf(
                OcrTextBlock(
                    blockIndex = 0,
                    readingOrder = 0,
                    text = "Hello world",
                    left = 10,
                    top = 20,
                    right = 210,
                    bottom = 90,
                    cornerPoints = listOf(
                        OcrPoint(10, 20),
                        OcrPoint(210, 20),
                        OcrPoint(210, 90),
                        OcrPoint(10, 90)
                    ),
                    languageTag = ""
                )
            ),
            lines = listOf(
                OcrTextLine(
                    blockIndex = 0,
                    lineIndex = 0,
                    readingOrder = 0,
                    text = "Hello world",
                    left = 12,
                    top = 24,
                    right = 205,
                    bottom = 82,
                    cornerPoints = emptyList(),
                    languageTag = "en",
                    confidence = 0.91f,
                    angleDegrees = 0.5f
                )
            ),
            words = listOf(
                OcrWordBox(
                    blockIndex = 0,
                    lineIndex = 0,
                    wordIndex = 0,
                    readingOrder = 0,
                    text = "Hello",
                    left = 12,
                    top = 24,
                    right = 90,
                    bottom = 82,
                    cornerPoints = emptyList(),
                    languageTag = "en",
                    confidence = 0.96f,
                    angleDegrees = 0f
                )
            ),
            sourceWidth = 1200,
            sourceHeight = 1600,
            script = OcrScript.LATIN
        )

        val decoded = requireNotNull(OcrLayoutCodec.decode(OcrLayoutCodec.encode(result)))

        assertEquals(result.text, decoded.text)
        assertEquals(result.sourceWidth, decoded.sourceWidth)
        assertEquals(result.sourceHeight, decoded.sourceHeight)
        assertEquals("", decoded.blocks.single().languageTag)
        assertEquals(result.blocks.single().cornerPoints, decoded.blocks.single().cornerPoints)
        assertEquals(0.96f, decoded.words.single().confidence, 0.0001f)
    }

    @Test
    fun ftsQuerySupportsExactPhrasesAndPrefixes() {
        assertEquals(
            "\"invoice\" AND \"total due\" AND \"acc\"*",
            OcrSearchTerms.buildFtsQuery("invoice \"total due\" acc*")
        )
    }

    @Test
    fun matchingWordsSupportsExactAndPrefixTerms() {
        val page = OcrPageResult(
            text = "Invoice account accrued",
            blocks = emptyList(),
            lines = emptyList(),
            words = listOf(
                word("Invoice", 0),
                word("account", 1),
                word("accrued", 2)
            ),
            sourceWidth = 1000,
            sourceHeight = 1000,
            script = OcrScript.LATIN
        )

        val matches = OcrSearchTerms.matchingWords(page, "invoice acc*")

        assertEquals(listOf("Invoice", "account", "accrued"), matches.map { it.text })
    }

    @Test
    fun fingerprintChangesWithGeometryRotationAndScript() {
        val file = File.createTempFile("scan-ocr-fingerprint", ".txt")
        try {
            file.writeText("same image bytes")
            val base = OcrFingerprint.create(file, null, 0, OcrScript.LATIN)
            val cropped = OcrFingerprint.create(
                file,
                "0.1;0.1,0.9;0.1,0.9;0.9,0.1;0.9",
                0,
                OcrScript.LATIN
            )
            val rotated = OcrFingerprint.create(file, null, 90, OcrScript.LATIN)
            val korean = OcrFingerprint.create(file, null, 0, OcrScript.KOREAN)

            assertNotEquals(base, cropped)
            assertNotEquals(base, rotated)
            assertNotEquals(base, korean)
            assertTrue(base.length == 64)
        } finally {
            file.delete()
        }
    }

    private fun word(text: String, order: Int): OcrWordBox =
        OcrWordBox(
            blockIndex = 0,
            lineIndex = 0,
            wordIndex = order,
            readingOrder = order,
            text = text,
            left = order * 20,
            top = 0,
            right = order * 20 + 15,
            bottom = 20,
            cornerPoints = emptyList(),
            languageTag = "en",
            confidence = 1f,
            angleDegrees = 0f
        )
}
