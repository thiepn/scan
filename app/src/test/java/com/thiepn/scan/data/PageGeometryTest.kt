package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageGeometryTest {
    @Test
    fun fullFrameSerializesAsNull() {
        assertNull(CropQuadCodec.encode(CropQuad.FULL))
    }

    @Test
    fun cropQuadRoundTrips() {
        val quad = CropQuad(
            NormalizedPoint(0.08f, 0.05f),
            NormalizedPoint(0.94f, 0.09f),
            NormalizedPoint(0.90f, 0.96f),
            NormalizedPoint(0.11f, 0.91f)
        )

        val decoded = CropQuadCodec.decode(CropQuadCodec.encode(quad))
        assertEquals(quad.points.size, decoded.points.size)
        quad.points.zip(decoded.points).forEach { (expected, actual) ->
            assertTrue(kotlin.math.abs(expected.x - actual.x) < 0.0001f)
            assertTrue(kotlin.math.abs(expected.y - actual.y) < 0.0001f)
        }
    }

    @Test
    fun crossingGeometryIsRejected() {
        val invalid = CropQuad(
            NormalizedPoint(0.1f, 0.1f),
            NormalizedPoint(0.9f, 0.9f),
            NormalizedPoint(0.9f, 0.1f),
            NormalizedPoint(0.1f, 0.9f)
        )
        assertFalse(invalid.isValid())
    }

    @Test
    fun displayRotationRoundTripsPoints() {
        val point = NormalizedPoint(0.23f, 0.71f)
        listOf(0, 90, 180, 270).forEach { rotation ->
            val display = GeometryRotation.sourceToDisplay(point, rotation)
            val source = GeometryRotation.displayToSource(display, rotation)
            assertTrue(kotlin.math.abs(point.x - source.x) < 0.0001f)
            assertTrue(kotlin.math.abs(point.y - source.y) < 0.0001f)
        }
    }

    @Test
    fun nearEdgePointsSnapCleanly() {
        val snapped = NormalizedPoint(0.01f, 0.992f).snappedToEdges()
        assertEquals(0f, snapped.x)
        assertEquals(1f, snapped.y)
    }

    @Test
    fun cornerMoveCannotCollapsePage() {
        val result = CropQuad.FULL.withCorner(
            index = 0,
            point = NormalizedPoint(0.99f, 0.99f)
        )
        assertNull(result)
    }
}
