package com.thiepn.scan.data

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class RestorationAnalysis internal constructor(
    private val illumination: FloatArray,
    private val gridWidth: Int,
    private val gridHeight: Int,
    val paperLuma: Float,
    val redGain: Float,
    val greenGain: Float,
    val blueGain: Float
) {
    fun sampleIllumination(
        x: Int,
        y: Int,
        imageWidth: Int,
        imageHeight: Int
    ): Float {
        val fx = if (imageWidth <= 1) 0f
        else x.toFloat() / (imageWidth - 1).toFloat()
        val fy = if (imageHeight <= 1) 0f
        else y.toFloat() / (imageHeight - 1).toFloat()

        val px = fx * (gridWidth - 1)
        val py = fy * (gridHeight - 1)
        val x0 = px.toInt().coerceIn(0, gridWidth - 1)
        val y0 = py.toInt().coerceIn(0, gridHeight - 1)
        val x1 = min(gridWidth - 1, x0 + 1)
        val y1 = min(gridHeight - 1, y0 + 1)
        val tx = px - x0
        val ty = py - y0

        val a = RestorationMath.lerp(
            illumination[y0 * gridWidth + x0],
            illumination[y0 * gridWidth + x1],
            tx
        )
        val b = RestorationMath.lerp(
            illumination[y1 * gridWidth + x0],
            illumination[y1 * gridWidth + x1],
            tx
        )
        return RestorationMath.lerp(a, b, ty)
    }
}

object DocumentRestorationEngine {
    private const val LONG_GRID_EDGE = 36
    private const val MIN_GRID_EDGE = 12

    fun analyze(
        source: Bitmap,
        recipe: PageVisualRecipe
    ): RestorationAnalysis? {
        val normalized = recipe.normalized()
        if (!normalized.hasRestoration()) return null

        val gridWidth: Int
        val gridHeight: Int
        if (source.width >= source.height) {
            gridWidth = LONG_GRID_EDGE
            gridHeight = (
                LONG_GRID_EDGE.toFloat() *
                    source.height.toFloat() /
                    source.width.coerceAtLeast(1).toFloat()
                ).toInt().coerceIn(MIN_GRID_EDGE, LONG_GRID_EDGE)
        } else {
            gridHeight = LONG_GRID_EDGE
            gridWidth = (
                LONG_GRID_EDGE.toFloat() *
                    source.width.toFloat() /
                    source.height.coerceAtLeast(1).toFloat()
                ).toInt().coerceIn(MIN_GRID_EDGE, LONG_GRID_EDGE)
        }

        val reduced = Bitmap.createScaledBitmap(
            source,
            gridWidth,
            gridHeight,
            true
        )
        return try {
            val pixels = IntArray(gridWidth * gridHeight)
            reduced.getPixels(
                pixels,
                0,
                gridWidth,
                0,
                0,
                gridWidth,
                gridHeight
            )

            val luma = FloatArray(pixels.size)
            val red = FloatArray(pixels.size)
            val green = FloatArray(pixels.size)
            val blue = FloatArray(pixels.size)

            pixels.forEachIndexed { index, color ->
                val r = Color.red(color) / 255f
                val g = Color.green(color) / 255f
                val b = Color.blue(color) / 255f
                red[index] = r
                green[index] = g
                blue[index] = b
                luma[index] = RestorationMath.luma(r, g, b)
            }

            val smoothed = boxBlur(
                boxBlur(
                    values = luma,
                    width = gridWidth,
                    height = gridHeight,
                    radius = 2
                ),
                width = gridWidth,
                height = gridHeight,
                radius = 2
            )

            val sorted = smoothed.copyOf().apply { sort() }
            val observedBright = percentile(sorted, 0.86f)
            val paperTarget = RestorationMath.paperTarget(
                normalized.restorationProfile,
                observedBright
            )

            val brightThreshold = percentile(
                luma.copyOf().apply { sort() },
                0.62f
            )
            var redSum = 0f
            var greenSum = 0f
            var blueSum = 0f
            var neutralCount = 0

            for (i in pixels.indices) {
                if (luma[i] < brightThreshold) continue
                val maxChannel = max(red[i], max(green[i], blue[i]))
                val minChannel = min(red[i], min(green[i], blue[i]))
                val saturation = if (maxChannel <= 0.001f) {
                    0f
                } else {
                    (maxChannel - minChannel) / maxChannel
                }
                if (saturation > 0.42f) continue

                redSum += red[i]
                greenSum += green[i]
                blueSum += blue[i]
                neutralCount += 1
            }

            val fallbackCount = pixels.size.coerceAtLeast(1)
            val meanRed = if (neutralCount > 0) redSum / neutralCount
            else red.sum() / fallbackCount
            val meanGreen = if (neutralCount > 0) greenSum / neutralCount
            else green.sum() / fallbackCount
            val meanBlue = if (neutralCount > 0) blueSum / neutralCount
            else blue.sum() / fallbackCount
            val neutralMean = (meanRed + meanGreen + meanBlue) / 3f

            RestorationAnalysis(
                illumination = smoothed,
                gridWidth = gridWidth,
                gridHeight = gridHeight,
                paperLuma = paperTarget,
                redGain = RestorationMath.whiteBalanceGain(
                    meanRed,
                    neutralMean,
                    normalized.whiteBalance
                ),
                greenGain = RestorationMath.whiteBalanceGain(
                    meanGreen,
                    neutralMean,
                    normalized.whiteBalance
                ),
                blueGain = RestorationMath.whiteBalanceGain(
                    meanBlue,
                    neutralMean,
                    normalized.whiteBalance
                )
            )
        } finally {
            if (reduced !== source) reduced.recycle()
        }
    }

    private fun percentile(
        sorted: FloatArray,
        fraction: Float
    ): Float {
        if (sorted.isEmpty()) return 0.85f
        val index = (
            (sorted.lastIndex * fraction.coerceIn(0f, 1f))
            ).toInt().coerceIn(sorted.indices)
        return sorted[index]
    }

    private fun boxBlur(
        values: FloatArray,
        width: Int,
        height: Int,
        radius: Int
    ): FloatArray {
        if (radius <= 0 || values.isEmpty()) return values.copyOf()
        val horizontal = FloatArray(values.size)
        val output = FloatArray(values.size)

        for (y in 0 until height) {
            var sum = 0f
            var count = 0
            for (x in -radius..radius) {
                if (x in 0 until width) {
                    sum += values[y * width + x]
                    count += 1
                }
            }
            for (x in 0 until width) {
                horizontal[y * width + x] = sum / count.coerceAtLeast(1)
                val remove = x - radius
                val add = x + radius + 1
                if (remove in 0 until width) {
                    sum -= values[y * width + remove]
                    count -= 1
                }
                if (add in 0 until width) {
                    sum += values[y * width + add]
                    count += 1
                }
            }
        }

        for (x in 0 until width) {
            var sum = 0f
            var count = 0
            for (y in -radius..radius) {
                if (y in 0 until height) {
                    sum += horizontal[y * width + x]
                    count += 1
                }
            }
            for (y in 0 until height) {
                output[y * width + x] = sum / count.coerceAtLeast(1)
                val remove = y - radius
                val add = y + radius + 1
                if (remove in 0 until height) {
                    sum -= horizontal[remove * width + x]
                    count -= 1
                }
                if (add in 0 until height) {
                    sum += horizontal[add * width + x]
                    count += 1
                }
            }
        }

        return output
    }
}

object RestorationMath {
    fun luma(r: Float, g: Float, b: Float): Float =
        0.299f * r + 0.587f * g + 0.114f * b

    fun whiteBalanceGain(
        channelMean: Float,
        neutralMean: Float,
        strength: Float
    ): Float {
        if (strength <= 0f || channelMean <= 0.001f) return 1f
        val full = (neutralMean / channelMean).coerceIn(0.78f, 1.24f)
        return lerp(1f, full, strength.coerceIn(0f, 1f))
    }

    fun paperTarget(
        profile: RestorationProfile,
        observedBright: Float
    ): Float {
        val fixed = when (profile) {
            RestorationProfile.OFF -> observedBright
            RestorationProfile.DOCUMENT -> 0.88f
            RestorationProfile.RECEIPT -> 0.94f
            RestorationProfile.WHITEBOARD -> 0.96f
            RestorationProfile.BOOK -> 0.84f
            RestorationProfile.NOTES -> 0.89f
            RestorationProfile.FORM -> 0.91f
        }
        return lerp(
            observedBright.coerceIn(0.45f, 0.98f),
            fixed,
            0.48f
        ).coerceIn(0.52f, 0.97f)
    }

    fun illuminationFactor(
        observed: Float,
        target: Float,
        strength: Float
    ): Float {
        if (strength <= 0f) return 1f
        val safeObserved = observed.coerceIn(0.06f, 1f)
        val safeTarget = target.coerceIn(0.20f, 1f)
        val full = (
            (safeTarget + 0.04f) /
                (safeObserved + 0.04f)
            ).pow(0.86f)
            .coerceIn(0.72f, 1.48f)
        return lerp(1f, full, strength.coerceIn(0f, 1f))
    }

    fun adaptiveThreshold(
        localPaper: Float,
        strength: Float,
        profile: RestorationProfile
    ): Float {
        val baseOffset = when (profile) {
            RestorationProfile.RECEIPT -> 0.17f
            RestorationProfile.WHITEBOARD -> 0.14f
            RestorationProfile.BOOK -> 0.13f
            RestorationProfile.NOTES -> 0.14f
            RestorationProfile.FORM -> 0.16f
            RestorationProfile.DOCUMENT -> 0.15f
            RestorationProfile.OFF -> 0.15f
        }
        val offset = baseOffset + (1f - strength.coerceIn(0f, 1f)) * 0.035f
        return (localPaper - offset).coerceIn(0.18f, 0.92f)
    }

    fun adaptiveMix(
        luminance: Float,
        localPaper: Float,
        strength: Float,
        profile: RestorationProfile
    ): Float {
        if (strength <= 0f) return luminance.coerceIn(0f, 1f)
        val s = strength.coerceIn(0f, 1f)
        val threshold = adaptiveThreshold(localPaper, s, profile)
        val softness = lerp(0.075f, 0.025f, s)
        val binary = smoothstep(
            threshold - softness,
            threshold + softness,
            luminance
        )
        return lerp(luminance, binary, s).coerceIn(0f, 1f)
    }

    fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge1 <= edge0) return if (value >= edge1) 1f else 0f
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun lerp(a: Float, b: Float, t: Float): Float =
        a + (b - a) * t.coerceIn(0f, 1f)
}
