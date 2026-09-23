package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCleanupTest {
    @Test
    fun emptyRecipeSerializesAsNull() {
        assertNull(
            PageCleanupRecipeCodec.encode(PageCleanupRecipe())
        )
    }

    @Test
    fun cleanupRecipeRoundTripsStrokeGeometry() {
        val recipe = PageCleanupRecipe(
            strokes = listOf(
                CleanupStroke(
                    kind = CleanupKind.MANUAL,
                    points = listOf(
                        NormalizedPoint(0.12f, 0.20f),
                        NormalizedPoint(0.40f, 0.55f),
                        NormalizedPoint(0.62f, 0.70f)
                    ),
                    radius = 0.035f
                ),
                CleanupStroke(
                    kind = CleanupKind.PUNCH_HOLE,
                    points = listOf(
                        NormalizedPoint(0.03f, 0.25f)
                    ),
                    radius = 0.018f,
                    confidence = 0.91f
                )
            )
        )

        val decoded = PageCleanupRecipeCodec.decode(
            PageCleanupRecipeCodec.encode(recipe)
        )

        assertEquals(2, decoded.strokes.size)
        assertEquals(CleanupKind.MANUAL, decoded.strokes[0].kind)
        assertEquals(3, decoded.strokes[0].points.size)
        assertEquals(0.035f, decoded.strokes[0].radius, 0.0001f)
        assertEquals(
            CleanupKind.PUNCH_HOLE,
            decoded.strokes[1].kind
        )
        assertEquals(0.91f, decoded.strokes[1].confidence, 0.0001f)
    }

    @Test
    fun cleanupNormalizationClampsUnsafeValues() {
        val recipe = PageCleanupRecipe(
            strokes = listOf(
                CleanupStroke(
                    kind = CleanupKind.OBJECT,
                    points = listOf(
                        NormalizedPoint(-1f, 2f)
                    ),
                    radius = 0.9f,
                    confidence = 4f
                )
            )
        ).normalized()

        val stroke = recipe.strokes.single()
        assertEquals(0f, stroke.points.single().x)
        assertEquals(1f, stroke.points.single().y)
        assertTrue(stroke.radius <= CleanupStroke.MAX_RADIUS)
        assertEquals(1f, stroke.confidence)
    }

    @Test
    fun malformedCleanupRecipeFailsClosed() {
        val decoded = PageCleanupRecipeCodec.decode(
            "v1~BOGUS|bad|data|oops"
        )

        assertTrue(decoded.isEmpty())
        assertFalse(decoded.strokes.isNotEmpty())
    }
}
