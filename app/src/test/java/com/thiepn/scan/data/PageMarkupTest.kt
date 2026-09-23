package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMarkupTest {
    @Test
    fun markupCodecRoundTripsAllSecurityRelevantFields() {
        val recipe = PageMarkupRecipe(
            items = listOf(
                PageMarkupItem(
                    id = "r1",
                    kind = PageMarkupKind.REDACTION,
                    left = 0.1f,
                    top = 0.2f,
                    right = 0.5f,
                    bottom = 0.3f,
                    text = "secret",
                    colorArgb = -65536,
                    strokeWidth = 0.01f,
                    opacity = 0.8f,
                    checked = true,
                    groupKey = "group"
                )
            )
        )

        val decoded = PageMarkupRecipeCodec.decode(
            PageMarkupRecipeCodec.encode(recipe)
        )

        assertEquals(1, decoded.items.size)
        assertEquals(PageMarkupKind.REDACTION, decoded.items.single().kind)
        assertEquals("secret", decoded.items.single().text)
        assertEquals("group", decoded.items.single().groupKey)
        assertTrue(decoded.items.single().checked)
    }

    @Test
    fun secureRedactionRemovesIntersectingOcrWordsAndSearchTruth() {
        val base = samplePage()
        val recipe = PageMarkupRecipe(
            items = listOf(
                PageMarkupItem(
                    id = "redact-total",
                    kind = PageMarkupKind.REDACTION,
                    left = 0.30f,
                    top = 0.05f,
                    right = 0.52f,
                    bottom = 0.18f
                )
            )
        )

        val redacted = OcrRedactionEngine.apply(base, recipe)
        val verification = OcrRedactionEngine.verify(base, recipe)

        assertFalse(redacted.text.contains("total"))
        assertTrue(redacted.text.contains("Invoice"))
        assertTrue(redacted.text.contains("Due today"))
        assertEquals(1, verification.removedWordCount)
        assertEquals(0, verification.exposedWordCount)
        assertTrue(verification.secure)
    }

    @Test
    fun signaturePathNormalizesAndPlacesWithoutLeavingPageBounds() {
        val encoded = SignaturePathCodec.encode(
            listOf(
                NormalizedPoint(0.2f, 0.3f),
                NormalizedPoint(0.6f, 0.7f),
                NormalizedPoint(0.8f, 0.4f)
            )
        )
        val decoded = SignaturePathCodec.decode(encoded)
        val placed = SignaturePathCodec.place(
            decoded,
            left = 0.6f,
            top = 0.7f,
            right = 0.95f,
            bottom = 0.9f
        )

        assertTrue(decoded.isNotEmpty())
        assertTrue(placed.all { it.x in 0f..1f && it.y in 0f..1f })
        assertTrue(placed.minOf { it.x } >= 0.6f)
        assertTrue(placed.maxOf { it.x } <= 0.95f)
    }

    private fun samplePage(): OcrPageResult {
        val words = listOf(
            word("Invoice", 0, 0, 0, 100, 280, 100, 160),
            word("total", 0, 1, 1, 320, 470, 100, 160),
            word("Due", 1, 0, 2, 100, 200, 200, 260),
            word("today", 1, 1, 3, 220, 370, 200, 260)
        )
        val lines = listOf(
            OcrTextLine(
                blockIndex = 0,
                lineIndex = 0,
                readingOrder = 0,
                text = "Invoice total",
                left = 100,
                top = 100,
                right = 470,
                bottom = 160,
                cornerPoints = emptyList(),
                languageTag = "en",
                confidence = 0.98f,
                angleDegrees = 0f
            ),
            OcrTextLine(
                blockIndex = 0,
                lineIndex = 1,
                readingOrder = 1,
                text = "Due today",
                left = 100,
                top = 200,
                right = 370,
                bottom = 260,
                cornerPoints = emptyList(),
                languageTag = "en",
                confidence = 0.98f,
                angleDegrees = 0f
            )
        )
        return OcrPageResult(
            text = "Invoice total\nDue today",
            blocks = listOf(
                OcrTextBlock(
                    blockIndex = 0,
                    readingOrder = 0,
                    text = "Invoice total\nDue today",
                    left = 100,
                    top = 100,
                    right = 470,
                    bottom = 260,
                    cornerPoints = emptyList(),
                    languageTag = "en"
                )
            ),
            lines = lines,
            words = words,
            sourceWidth = 1000,
            sourceHeight = 1000,
            script = OcrScript.LATIN
        )
    }

    private fun word(
        text: String,
        line: Int,
        word: Int,
        order: Int,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int
    ): OcrWordBox = OcrWordBox(
        blockIndex = 0,
        lineIndex = line,
        wordIndex = word,
        readingOrder = order,
        text = text,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        cornerPoints = emptyList(),
        languageTag = "en",
        confidence = 0.98f,
        angleDegrees = 0f
    )
}
