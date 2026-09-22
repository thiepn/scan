package com.thiepn.scan.data

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

object ImageEnhancementRenderer {
    fun apply(
        source: Bitmap,
        recipe: PageVisualRecipe
    ): Bitmap {
        val r = recipe.normalized()
        if (r.isOriginal()) return source

        val width = source.width
        val height = source.height
        val input = IntArray(width * height)
        source.getPixels(input, 0, width, 0, 0, width, height)

        val illumination = if (r.shadowNormalization > 0.001f) {
            estimateIllumination(input, width, height)
        } else {
            null
        }

        val output = IntArray(input.size)
        val black = (r.blackPoint * 0.42f).coerceIn(0f, 0.42f)
        val white = (1f - r.whitePoint * 0.28f).coerceIn(0.58f, 1f)
        val tonalRange = (white - black).coerceAtLeast(0.08f)
        val brightnessOffset = r.brightness * 0.18f
        val contrastFactor = (1f + r.contrast * 0.95f).coerceIn(0.25f, 2.1f)
        val saturationFactor = (1f + r.saturation * 0.85f).coerceIn(0f, 1.85f)
        val warmth = r.warmth * 0.12f
        val grayscale = r.preset == ScanPreset.GRAYSCALE || r.preset == ScanPreset.BLACK_WHITE

        for (index in input.indices) {
            val color = input[index]
            var red = Color.red(color) / 255f
            var green = Color.green(color) / 255f
            var blue = Color.blue(color) / 255f
            val alpha = Color.alpha(color)

            var luminance = luma(red, green, blue)

            illumination?.let { map ->
                val local = map[index]
                val correction = ((map.median - local) * r.shadowNormalization * 0.70f)
                    .coerceIn(-0.22f, 0.30f)
                red = (red + correction).coerceIn(0f, 1f)
                green = (green + correction).coerceIn(0f, 1f)
                blue = (blue + correction).coerceIn(0f, 1f)
                luminance = luma(red, green, blue)
            }

            val lifted = applyShadowHighlightCurve(
                luminance = luminance,
                shadows = r.shadows,
                highlights = r.highlights
            )
            val luminanceDelta = lifted - luminance
            red += luminanceDelta
            green += luminanceDelta
            blue += luminanceDelta

            red = ((red - black) / tonalRange).coerceIn(0f, 1f)
            green = ((green - black) / tonalRange).coerceIn(0f, 1f)
            blue = ((blue - black) / tonalRange).coerceIn(0f, 1f)

            red = ((red - 0.5f) * contrastFactor + 0.5f + brightnessOffset).coerceIn(0f, 1f)
            green = ((green - 0.5f) * contrastFactor + 0.5f + brightnessOffset).coerceIn(0f, 1f)
            blue = ((blue - 0.5f) * contrastFactor + 0.5f + brightnessOffset).coerceIn(0f, 1f)

            red = (red + warmth).coerceIn(0f, 1f)
            blue = (blue - warmth).coerceIn(0f, 1f)

            val gray = luma(red, green, blue)
            red = (gray + (red - gray) * saturationFactor).coerceIn(0f, 1f)
            green = (gray + (green - gray) * saturationFactor).coerceIn(0f, 1f)
            blue = (gray + (blue - gray) * saturationFactor).coerceIn(0f, 1f)

            if (r.backgroundWhitening > 0.001f) {
                val lum = luma(red, green, blue)
                val mask = smoothstep(0.58f, 0.95f, lum) * r.backgroundWhitening
                red += (1f - red) * mask
                green += (1f - green) * mask
                blue += (1f - blue) * mask
            }

            if (grayscale) {
                val value = luma(red, green, blue)
                red = value
                green = value
                blue = value
            }

            if (r.preset == ScanPreset.BLACK_WHITE) {
                val threshold = 0.70f - r.blackPoint * 0.14f + r.whitePoint * 0.08f
                val lum = luma(red, green, blue)
                val widthSoft = 0.10f
                val value = smoothstep(
                    threshold - widthSoft,
                    threshold + widthSoft,
                    lum
                )
                red = value
                green = value
                blue = value
            }

            output[index] = Color.argb(
                alpha,
                (red * 255f).roundToInt().coerceIn(0, 255),
                (green * 255f).roundToInt().coerceIn(0, 255),
                (blue * 255f).roundToInt().coerceIn(0, 255)
            )
        }

        if (r.sharpness > 0.001f && width > 2 && height > 2) {
            applySharpen(output, width, height, r.sharpness)
        }

        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    private data class IlluminationMap(
        val values: FloatArray,
        val median: Float
    ) {
        operator fun get(index: Int): Float = values[index]
    }

    private fun estimateIllumination(
        pixels: IntArray,
        width: Int,
        height: Int
    ): IlluminationMap {
        val gridX = 12
        val gridY = 12
        val cell = FloatArray(gridX * gridY)
        val counts = IntArray(cell.size)

        for (y in 0 until height) {
            val gy = min(gridY - 1, y * gridY / max(1, height))
            for (x in 0 until width) {
                val gx = min(gridX - 1, x * gridX / max(1, width))
                val index = gy * gridX + gx
                val color = pixels[y * width + x]
                cell[index] += luma(
                    Color.red(color) / 255f,
                    Color.green(color) / 255f,
                    Color.blue(color) / 255f
                )
                counts[index]++
            }
        }

        for (i in cell.indices) {
            if (counts[i] > 0) cell[i] /= counts[i]
        }

        val sorted = cell.copyOf().apply { sort() }
        val median = sorted[sorted.size / 2]
        val values = FloatArray(width * height)

        for (y in 0 until height) {
            val fy = if (height <= 1) 0f else y.toFloat() / (height - 1)
            val py = fy * (gridY - 1)
            val y0 = py.toInt().coerceIn(0, gridY - 1)
            val y1 = min(gridY - 1, y0 + 1)
            val ty = py - y0
            for (x in 0 until width) {
                val fx = if (width <= 1) 0f else x.toFloat() / (width - 1)
                val px = fx * (gridX - 1)
                val x0 = px.toInt().coerceIn(0, gridX - 1)
                val x1 = min(gridX - 1, x0 + 1)
                val tx = px - x0

                val a = lerp(cell[y0 * gridX + x0], cell[y0 * gridX + x1], tx)
                val b = lerp(cell[y1 * gridX + x0], cell[y1 * gridX + x1], tx)
                values[y * width + x] = lerp(a, b, ty)
            }
        }

        return IlluminationMap(values, median)
    }

    private fun applyShadowHighlightCurve(
        luminance: Float,
        shadows: Float,
        highlights: Float
    ): Float {
        var value = luminance
        if (shadows != 0f) {
            val shadowMask = (1f - value).pow(2f)
            value += shadows * 0.30f * shadowMask
        }
        if (highlights != 0f) {
            val highlightMask = value.pow(2f)
            value += highlights * 0.25f * highlightMask
        }
        return value.coerceIn(0f, 1f)
    }

    private fun applySharpen(
        pixels: IntArray,
        width: Int,
        height: Int,
        amount: Float
    ) {
        val source = pixels.copyOf()
        val strength = (amount * 0.62f).coerceIn(0f, 0.62f)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val center = source[i]
                val left = source[i - 1]
                val right = source[i + 1]
                val up = source[i - width]
                val down = source[i + width]

                fun sharpenChannel(extract: (Int) -> Int): Int {
                    val c = extract(center)
                    val avg = (
                        extract(left) + extract(right) +
                            extract(up) + extract(down)
                        ) / 4f
                    return (c + (c - avg) * strength)
                        .roundToInt()
                        .coerceIn(0, 255)
                }

                pixels[i] = Color.argb(
                    Color.alpha(center),
                    sharpenChannel(Color::red),
                    sharpenChannel(Color::green),
                    sharpenChannel(Color::blue)
                )
            }
        }
    }

    private fun luma(r: Float, g: Float, b: Float): Float =
        0.299f * r + 0.587f * g + 0.114f * b

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
