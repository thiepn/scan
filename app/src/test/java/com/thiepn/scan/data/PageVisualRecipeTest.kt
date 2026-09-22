package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageVisualRecipeTest {
    @Test
    fun originalRecipeSerializesAsNull() {
        assertNull(PageVisualRecipeCodec.encode(PageVisualRecipe()))
    }

    @Test
    fun nonOriginalRecipeRoundTrips() {
        val expected = PageVisualRecipe.forPreset(ScanPreset.CLEAN)
            .copy(brightness = 0.17f, warmth = -0.22f)

        val encoded = PageVisualRecipeCodec.encode(expected)
        val actual = PageVisualRecipeCodec.decode(encoded)

        assertEquals(expected.preset, actual.preset)
        assertEquals(expected.brightness, actual.brightness, 0.0001f)
        assertEquals(expected.warmth, actual.warmth, 0.0001f)
        assertEquals(expected.backgroundWhitening, actual.backgroundWhitening, 0.0001f)
    }

    @Test
    fun valuesAreClampedToSupportedRanges() {
        val normalized = PageVisualRecipe(
            brightness = 5f,
            contrast = -4f,
            blackPoint = -2f,
            whitePoint = 4f,
            sharpness = 3f
        ).normalized()

        assertEquals(1f, normalized.brightness, 0f)
        assertEquals(-1f, normalized.contrast, 0f)
        assertEquals(0f, normalized.blackPoint, 0f)
        assertEquals(1f, normalized.whitePoint, 0f)
        assertEquals(1f, normalized.sharpness, 0f)
    }

    @Test
    fun allProductionPresetsExceptOriginalAreVisibleEdits() {
        ScanPreset.entries.forEach { preset ->
            val recipe = PageVisualRecipe.forPreset(preset)
            if (preset == ScanPreset.ORIGINAL) {
                assertTrue(recipe.isOriginal())
            } else {
                assertFalse("$preset should change the rendered page", recipe.isOriginal())
            }
        }
    }

    @Test
    fun malformedRecipeFallsBackToOriginal() {
        assertTrue(PageVisualRecipeCodec.decode("not|a|valid|recipe").isOriginal())
    }
}
