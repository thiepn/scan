package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTextEditingTest {
    @Test
    fun recipeCodecRoundTripsMultilineAndSpecialCharacters() {
        val region = OcrTextEditEngine.regions(
            samplePage(),
            OcrTextEditTarget.BLOCK
        ).single()
        val recipe = PageTextEditRecipe(
            edits = listOf(
                OcrTextEditEngine.createEdit(
                    region = region,
                    replacementText = "Paid\tin full\n€42 ~ ✓",
                    alignment = OcrTextAlignment.CENTER,
                    fontScale = 1.25f
                )
            )
        )

        val decoded = PageTextEditRecipeCodec.decode(
            PageTextEditRecipeCodec.encode(recipe)
        )

        assertEquals(1, decoded.edits.size)
        assertEquals("Paid\tin full\n€42 ~ ✓", decoded.edits.single().replacementText)
        assertEquals(OcrTextAlignment.CENTER, decoded.edits.single().alignment)
        assertEquals(1.25f, decoded.edits.single().fontScale, 0.0001f)
    }

    @Test
    fun wordEditUpdatesSearchableLineAndFullText() {
        val base = samplePage()
        val word = OcrTextEditEngine.regions(
            base,
            OcrTextEditTarget.WORD
        ).first { it.text == "total" }
        val recipe = OcrTextEditEngine.withEdit(
            PageTextEditRecipe(),
            OcrTextEditEngine.createEdit(word, "amount")
        )

        val edited = OcrTextEditEngine.apply(base, recipe)

        assertTrue(edited.text.contains("Invoice amount"))
        assertFalse(edited.text.contains("Invoice total"))
        assertTrue(edited.words.any { it.text == "amount" })
        assertFalse(edited.words.any { it.text == "total" })
        assertEquals("Invoice total\nDue today", base.text)
    }

    @Test
    fun multilineBlockEditRebuildsLinesWordsAndSearchTruth() {
        val base = samplePage()
        val block = OcrTextEditEngine.regions(
            base,
            OcrTextEditTarget.BLOCK
        ).single()
        val recipe = OcrTextEditEngine.withEdit(
            PageTextEditRecipe(),
            OcrTextEditEngine.createEdit(
                block,
                "Invoice paid\nThank you"
            )
        )

        val edited = OcrTextEditEngine.apply(base, recipe)

        assertEquals("Invoice paid\nThank you", edited.text)
        assertEquals(listOf("Invoice paid", "Thank you"), edited.lines.map { it.text })
        assertEquals(listOf("Invoice", "paid", "Thank", "you"), edited.words.map { it.text })
        assertEquals("Invoice total\nDue today", base.text)
    }

    @Test
    fun lineEditSupersedesConflictingWordEditsButKeepsOtherLines() {
        val base = samplePage()
        val words = OcrTextEditEngine.regions(base, OcrTextEditTarget.WORD)
        val total = words.first { it.text == "total" }
        val due = words.first { it.text == "Due" }
        var recipe = PageTextEditRecipe()
        recipe = OcrTextEditEngine.withEdit(
            recipe,
            OcrTextEditEngine.createEdit(total, "amount")
        )
        recipe = OcrTextEditEngine.withEdit(
            recipe,
            OcrTextEditEngine.createEdit(due, "Payable")
        )

        val firstLine = OcrTextEditEngine.regions(
            base,
            OcrTextEditTarget.LINE
        ).first()
        recipe = OcrTextEditEngine.withEdit(
            recipe,
            OcrTextEditEngine.createEdit(firstLine, "Balance 42")
        )

        assertEquals(2, recipe.edits.size)
        assertTrue(recipe.edits.any {
            it.target == OcrTextEditTarget.LINE &&
                it.replacementText == "Balance 42"
        })
        assertTrue(recipe.edits.any {
            it.target == OcrTextEditTarget.WORD &&
                it.replacementText == "Payable"
        })
        assertFalse(recipe.edits.any { it.replacementText == "amount" })

        val edited = OcrTextEditEngine.apply(base, recipe)
        assertTrue(edited.text.contains("Balance 42"))
        assertTrue(edited.text.contains("Payable today"))
    }

    @Test
    fun revertingToOriginalRegionRemovesConflictingReplacement() {
        val base = samplePage()
        val word = OcrTextEditEngine.regions(
            base,
            OcrTextEditTarget.WORD
        ).first { it.text == "total" }
        var recipe = OcrTextEditEngine.withEdit(
            PageTextEditRecipe(),
            OcrTextEditEngine.createEdit(word, "amount")
        )
        assertFalse(recipe.isEmpty())

        recipe = OcrTextEditEngine.withEdit(
            recipe,
            OcrTextEditEngine.createEdit(word, word.text)
        )

        assertTrue(recipe.isEmpty())
        assertEquals(base, OcrTextEditEngine.apply(base, recipe))
    }

    @Test
    fun regionCoordinatesAreNormalizedForHitTesting() {
        val regions = OcrTextEditEngine.regions(
            samplePage(),
            OcrTextEditTarget.WORD
        )

        assertTrue(regions.isNotEmpty())
        regions.forEach { region ->
            assertTrue(region.left in 0f..1f)
            assertTrue(region.top in 0f..1f)
            assertTrue(region.right in 0f..1f)
            assertTrue(region.bottom in 0f..1f)
            assertTrue(region.right > region.left)
            assertTrue(region.bottom > region.top)
        }
    }

    private fun samplePage(): OcrPageResult {
        val words = listOf(
            word("Invoice", line = 0, word = 0, order = 0, left = 100, right = 300, top = 100, bottom = 160),
            word("total", line = 0, word = 1, order = 1, left = 320, right = 470, top = 100, bottom = 160),
            word("Due", line = 1, word = 0, order = 2, left = 100, right = 200, top = 190, bottom = 250),
            word("today", line = 1, word = 1, order = 3, left = 220, right = 370, top = 190, bottom = 250)
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
                confidence = 0.97f,
                angleDegrees = 0f
            ),
            OcrTextLine(
                blockIndex = 0,
                lineIndex = 1,
                readingOrder = 1,
                text = "Due today",
                left = 100,
                top = 190,
                right = 370,
                bottom = 250,
                cornerPoints = emptyList(),
                languageTag = "en",
                confidence = 0.96f,
                angleDegrees = 0f
            )
        )
        val blocks = listOf(
            OcrTextBlock(
                blockIndex = 0,
                readingOrder = 0,
                text = "Invoice total\nDue today",
                left = 100,
                top = 100,
                right = 470,
                bottom = 250,
                cornerPoints = emptyList(),
                languageTag = "en"
            )
        )
        return OcrPageResult(
            text = "Invoice total\nDue today",
            blocks = blocks,
            lines = lines,
            words = words,
            sourceWidth = 1000,
            sourceHeight = 1400,
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
