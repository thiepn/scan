package com.thiepn.scan.data

import android.graphics.Bitmap
import java.io.File
import kotlin.math.abs
import kotlin.math.max

object PageBoundaryDetector {
    fun detect(file: File): CropQuad? {
        val bitmap = runCatching {
            PageGeometryRenderer.renderFile(
                file = file,
                cropQuad = CropQuad.FULL,
                rotationDegrees = 0,
                maxLongEdge = 520
            )
        }.getOrNull() ?: return null

        return try {
            detect(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun detect(bitmap: Bitmap): CropQuad? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 80 || height < 80) return null

        val gray = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                gray[y * width + x] = (r * 30 + g * 59 + b * 11) / 100
            }
        }

        fun gx(x: Int, y: Int): Int {
            if (x <= 0 || x >= width - 1) return 0
            return abs(gray[y * width + x + 1] - gray[y * width + x - 1])
        }

        fun gy(x: Int, y: Int): Int {
            if (y <= 0 || y >= height - 1) return 0
            return abs(gray[(y + 1) * width + x] - gray[(y - 1) * width + x])
        }

        val yStart = (height * 0.08f).toInt().coerceAtLeast(1)
        val yEnd = (height * 0.92f).toInt().coerceAtMost(height - 2)
        val xStart = (width * 0.08f).toInt().coerceAtLeast(1)
        val xEnd = (width * 0.92f).toInt().coerceAtMost(width - 2)

        val leftBandEnd = max(2, (width * 0.38f).toInt())
        val rightBandStart = (width * 0.62f).toInt().coerceAtMost(width - 3)
        val topBandEnd = max(2, (height * 0.38f).toInt())
        val bottomBandStart = (height * 0.62f).toInt().coerceAtMost(height - 3)

        val leftSamples = mutableListOf<Pair<Float, Float>>()
        val rightSamples = mutableListOf<Pair<Float, Float>>()
        val topSamples = mutableListOf<Pair<Float, Float>>()
        val bottomSamples = mutableListOf<Pair<Float, Float>>()
        var leftStrength = 0f
        var rightStrength = 0f
        var topStrength = 0f
        var bottomStrength = 0f

        val rowStep = max(2, height / 80)
        for (y in yStart..yEnd step rowStep) {
            var bestLeftX = 1
            var bestLeft = -1
            for (x in 1..leftBandEnd) {
                val value = gx(x.coerceAtMost(width - 2), y)
                if (value > bestLeft) {
                    bestLeft = value
                    bestLeftX = x
                }
            }
            leftSamples += y.toFloat() to bestLeftX.toFloat()
            leftStrength += bestLeft

            var bestRightX = rightBandStart
            var bestRight = -1
            for (x in rightBandStart until width - 1) {
                val value = gx(x, y)
                if (value > bestRight) {
                    bestRight = value
                    bestRightX = x
                }
            }
            rightSamples += y.toFloat() to bestRightX.toFloat()
            rightStrength += bestRight
        }

        val colStep = max(2, width / 80)
        for (x in xStart..xEnd step colStep) {
            var bestTopY = 1
            var bestTop = -1
            for (y in 1..topBandEnd) {
                val value = gy(x, y.coerceAtMost(height - 2))
                if (value > bestTop) {
                    bestTop = value
                    bestTopY = y
                }
            }
            topSamples += x.toFloat() to bestTopY.toFloat()
            topStrength += bestTop

            var bestBottomY = bottomBandStart
            var bestBottom = -1
            for (y in bottomBandStart until height - 1) {
                val value = gy(x, y)
                if (value > bestBottom) {
                    bestBottom = value
                    bestBottomY = y
                }
            }
            bottomSamples += x.toFloat() to bestBottomY.toFloat()
            bottomStrength += bestBottom
        }

        if (leftSamples.size < 4 || topSamples.size < 4) return null

        val left = fitLine(leftSamples) ?: return null     // x = a*y + b
        val right = fitLine(rightSamples) ?: return null   // x = a*y + b
        val top = fitLine(topSamples) ?: return null       // y = a*x + b
        val bottom = fitLine(bottomSamples) ?: return null // y = a*x + b

        val averageGradient = estimateAverageGradient(gray, width, height).coerceAtLeast(1f)
        val sideScore = listOf(
            leftStrength / leftSamples.size,
            rightStrength / rightSamples.size,
            topStrength / topSamples.size,
            bottomStrength / bottomSamples.size
        ).average().toFloat()

        if (sideScore < averageGradient * 1.45f) return null

        val tl = intersect(left, top) ?: return null
        val tr = intersect(right, top) ?: return null
        val br = intersect(right, bottom) ?: return null
        val bl = intersect(left, bottom) ?: return null

        val quad = CropQuad(
            NormalizedPoint(tl.first / width, tl.second / height).clamped(),
            NormalizedPoint(tr.first / width, tr.second / height).clamped(),
            NormalizedPoint(br.first / width, br.second / height).clamped(),
            NormalizedPoint(bl.first / width, bl.second / height).clamped()
        )

        if (!quad.isValid()) return null
        if (quad.polygonArea() < 0.35f) return null
        return quad
    }

    private data class Line(val a: Float, val b: Float)

    private fun fitLine(samples: List<Pair<Float, Float>>): Line? {
        if (samples.size < 2) return null
        val meanX = samples.map { it.first }.average().toFloat()
        val meanY = samples.map { it.second }.average().toFloat()
        var numerator = 0f
        var denominator = 0f
        samples.forEach { (x, y) ->
            val dx = x - meanX
            numerator += dx * (y - meanY)
            denominator += dx * dx
        }
        if (abs(denominator) < 0.0001f) return Line(0f, meanY)
        val a = numerator / denominator
        return Line(a, meanY - a * meanX)
    }

    private fun intersect(
        vertical: Line,
        horizontal: Line
    ): Pair<Float, Float>? {
        val denominator = 1f - vertical.a * horizontal.a
        if (abs(denominator) < 0.02f) return null
        val x = (vertical.a * horizontal.b + vertical.b) / denominator
        val y = horizontal.a * x + horizontal.b
        if (!x.isFinite() || !y.isFinite()) return null
        return x to y
    }

    private fun estimateAverageGradient(
        gray: IntArray,
        width: Int,
        height: Int
    ): Float {
        var total = 0L
        var count = 0L
        val step = max(2, max(width, height) / 160)
        for (y in 1 until height - 1 step step) {
            for (x in 1 until width - 1 step step) {
                val gx = abs(gray[y * width + x + 1] - gray[y * width + x - 1])
                val gy = abs(gray[(y + 1) * width + x] - gray[(y - 1) * width + x])
                total += gx + gy
                count += 2
            }
        }
        return if (count == 0L) 1f else total.toFloat() / count
    }
}
