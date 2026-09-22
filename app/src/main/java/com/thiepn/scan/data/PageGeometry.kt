package com.thiepn.scan.data

import kotlin.math.abs
import kotlin.math.hypot

data class NormalizedPoint(
    val x: Float,
    val y: Float
) {
    fun clamped(): NormalizedPoint = NormalizedPoint(
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f)
    )
}

data class CropQuad(
    val topLeft: NormalizedPoint,
    val topRight: NormalizedPoint,
    val bottomRight: NormalizedPoint,
    val bottomLeft: NormalizedPoint
) {
    val points: List<NormalizedPoint>
        get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun withCorner(index: Int, point: NormalizedPoint): CropQuad? {
        val p = point.clamped()
        val candidate = when (index) {
            0 -> copy(topLeft = p)
            1 -> copy(topRight = p)
            2 -> copy(bottomRight = p)
            3 -> copy(bottomLeft = p)
            else -> return null
        }
        return candidate.takeIf { it.isValid() }
    }

    fun isValid(): Boolean {
        val p = points
        if (p.any { it.x !in 0f..1f || it.y !in 0f..1f }) return false
        if (polygonArea() < 0.02f) return false
        if (edgeLength(topLeft, topRight) < 0.04f) return false
        if (edgeLength(topRight, bottomRight) < 0.04f) return false
        if (edgeLength(bottomRight, bottomLeft) < 0.04f) return false
        if (edgeLength(bottomLeft, topLeft) < 0.04f) return false

        val crosses = List(4) { index ->
            val a = p[index]
            val b = p[(index + 1) % 4]
            val c = p[(index + 2) % 4]
            cross(a, b, c)
        }
        val positive = crosses.all { it > 0.0005f }
        val negative = crosses.all { it < -0.0005f }
        return positive || negative
    }

    fun isFullFrame(tolerance: Float = 0.003f): Boolean =
        abs(topLeft.x) <= tolerance &&
            abs(topLeft.y) <= tolerance &&
            abs(topRight.x - 1f) <= tolerance &&
            abs(topRight.y) <= tolerance &&
            abs(bottomRight.x - 1f) <= tolerance &&
            abs(bottomRight.y - 1f) <= tolerance &&
            abs(bottomLeft.x) <= tolerance &&
            abs(bottomLeft.y - 1f) <= tolerance

    fun polygonArea(): Float {
        val p = points
        var sum = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) * 0.5f
    }

    companion object {
        val FULL = CropQuad(
            topLeft = NormalizedPoint(0f, 0f),
            topRight = NormalizedPoint(1f, 0f),
            bottomRight = NormalizedPoint(1f, 1f),
            bottomLeft = NormalizedPoint(0f, 1f)
        )
    }
}

object CropQuadCodec {
    fun encode(quad: CropQuad?): String? {
        if (quad == null || quad.isFullFrame()) return null
        require(quad.isValid()) { "Invalid crop geometry" }
        return quad.points.joinToString(",") { point ->
            "${point.x};${point.y}"
        }
    }

    fun decode(encoded: String?): CropQuad {
        if (encoded.isNullOrBlank()) return CropQuad.FULL
        val parts = encoded.split(',')
        if (parts.size != 4) return CropQuad.FULL

        val points = parts.mapNotNull { token ->
            val xy = token.split(';')
            if (xy.size != 2) return@mapNotNull null
            val x = xy[0].toFloatOrNull() ?: return@mapNotNull null
            val y = xy[1].toFloatOrNull() ?: return@mapNotNull null
            NormalizedPoint(x, y)
        }
        if (points.size != 4) return CropQuad.FULL

        val quad = CropQuad(
            points[0],
            points[1],
            points[2],
            points[3]
        )
        return quad.takeIf { it.isValid() } ?: CropQuad.FULL
    }
}

object GeometryRotation {
    fun sourceToDisplay(point: NormalizedPoint, rotationDegrees: Int): NormalizedPoint =
        when (PageRotation.normalize(rotationDegrees)) {
            90 -> NormalizedPoint(1f - point.y, point.x)
            180 -> NormalizedPoint(1f - point.x, 1f - point.y)
            270 -> NormalizedPoint(point.y, 1f - point.x)
            else -> point
        }

    fun displayToSource(point: NormalizedPoint, rotationDegrees: Int): NormalizedPoint =
        when (PageRotation.normalize(rotationDegrees)) {
            90 -> NormalizedPoint(point.y, 1f - point.x)
            180 -> NormalizedPoint(1f - point.x, 1f - point.y)
            270 -> NormalizedPoint(1f - point.y, point.x)
            else -> point
        }

    fun sourceToDisplay(quad: CropQuad, rotationDegrees: Int): CropQuad {
        val mapped = quad.points.map { sourceToDisplay(it, rotationDegrees) }
        return reorderClockwise(mapped)
    }

    private fun reorderClockwise(points: List<NormalizedPoint>): CropQuad {
        val byTop = points.sortedBy { it.y }
        val top = byTop.take(2).sortedBy { it.x }
        val bottom = byTop.takeLast(2).sortedBy { it.x }
        return CropQuad(
            topLeft = top.first(),
            topRight = top.last(),
            bottomRight = bottom.last(),
            bottomLeft = bottom.first()
        )
    }
}

private fun edgeLength(a: NormalizedPoint, b: NormalizedPoint): Float =
    hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

private fun cross(a: NormalizedPoint, b: NormalizedPoint, c: NormalizedPoint): Float =
    (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
