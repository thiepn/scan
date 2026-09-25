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
            .copy(
                brightness = 0.17f,
                warmth = -0.22f,
                illuminationCorrection = 0.63f,
                localContrast = 0.41f,
                whiteBalance = 0.37f,
                adaptiveBlackWhite = 0.21f,
                restorationProfile = RestorationProfile.BOOK
            )

        val encoded = PageVisualRecipeCodec.encode(expected)
        val actual = PageVisualRecipeCodec.decode(encoded)

        assertEquals(expected.preset, actual.preset)
        assertEquals(expected.brightness, actual.brightness, 0.0001f)
        assertEquals(expected.warmth, actual.warmth, 0.0001f)
        assertEquals(expected.backgroundWhitening, actual.backgroundWhitening, 0.0001f)
        assertEquals(expected.illuminationCorrection, actual.illuminationCorrection, 0.0001f)
        assertEquals(expected.localContrast, actual.localContrast, 0.0001f)
        assertEquals(expected.whiteBalance, actual.whiteBalance, 0.0001f)
        assertEquals(expected.adaptiveBlackWhite, actual.adaptiveBlackWhite, 0.0001f)
        assertEquals(expected.restorationProfile, actual.restorationProfile)
        assertEquals(PageVisualRecipe.CURRENT_VERSION, actual.version)
    }

    @Test
    fun valuesAreClampedToSupportedRanges() {
        val normalized = PageVisualRecipe(
            brightness = 5f,
            contrast = -4f,
            blackPoint = -2f,
            whitePoint = 4f,
            sharpness = 3f,
            illuminationCorrection = 8f,
            localContrast = -2f,
            whiteBalance = 5f,
            adaptiveBlackWhite = 7f
        ).normalized()

        assertEquals(1f, normalized.brightness, 0f)
        assertEquals(-1f, normalized.contrast, 0f)
        assertEquals(0f, normalized.blackPoint, 0f)
        assertEquals(1f, normalized.whitePoint, 0f)
        assertEquals(1f, normalized.sharpness, 0f)
        assertEquals(1f, normalized.illuminationCorrection, 0f)
        assertEquals(0f, normalized.localContrast, 0f)
        assertEquals(1f, normalized.whiteBalance, 0f)
        assertEquals(1f, normalized.adaptiveBlackWhite, 0f)
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
    fun modeDefaultsUseSpecializedRestorationProfiles() {
        assertEquals(
            RestorationProfile.RECEIPT,
            PageVisualRecipe.forMode(ScanMode.RECEIPT).restorationProfile
        )
        assertEquals(
            RestorationProfile.WHITEBOARD,
            PageVisualRecipe.forMode(ScanMode.WHITEBOARD).restorationProfile
        )
        assertEquals(
            RestorationProfile.BOOK,
            PageVisualRecipe.forMode(ScanMode.BOOK).restorationProfile
        )
        assertEquals(
            RestorationProfile.FORM,
            PageVisualRecipe.forMode(ScanMode.FORM).restorationProfile
        )
        assertEquals(
            RestorationProfile.OFF,
            PageVisualRecipe.forMode(ScanMode.PHOTO).restorationProfile
        )
    }

    @Test
    fun legacyV1RecipeKeepsLegacyRestorationDisabled() {
        val legacy = listOf(
            "1",
            ScanPreset.BLACK_WHITE.name,
            "0.0",
            "0.34",
            "0.0",
            "0.0",
            "0.10",
            "0.16",
            "0.0",
            "0.0",
            "0.18",
            "0.28",
            "0.20"
        ).joinToString("|")

        val decoded = PageVisualRecipeCodec.decode(legacy)

        assertEquals(1, decoded.version)
        assertEquals(ScanPreset.BLACK_WHITE, decoded.preset)
        assertEquals(0f, decoded.illuminationCorrection, 0f)
        assertEquals(0f, decoded.localContrast, 0f)
        assertEquals(0f, decoded.whiteBalance, 0f)
        assertEquals(0f, decoded.adaptiveBlackWhite, 0f)
        assertEquals(RestorationProfile.OFF, decoded.restorationProfile)
    }

    @Test
    fun malformedRecipeFallsBackToOriginal() {
        assertTrue(PageVisualRecipeCodec.decode("not|a|valid|recipe").isOriginal())
    }
}
