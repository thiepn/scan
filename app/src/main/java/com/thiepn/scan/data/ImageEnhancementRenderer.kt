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
        val r = recipe.normalized().let {
            if (recipe.version < PageVisualRecipe.CURRENT_VERSION) {
                it.copy(version = recipe.version)
            } else {
                it
            }
        }
        if (r.isOriginal()) return source

        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val restoration = DocumentRestorationEngine.analyze(
            source = source,
            recipe = r
        )

        if (r.sharpness <= 0.001f || width < 3 || height < 3) {
            val raw = IntArray(width)
            val transformed = IntArray(width)
            for (y in 0 until height) {
                source.getPixels(raw, 0, width, 0, y, width, 1)
                transformRow(
                    input = raw,
                    output = transformed,
                    y = y,
                    width = width,
                    height = height,
                    recipe = r,
                    restoration = restoration
                )
                output.setPixels(transformed, 0, width, 0, y, width, 1)
            }
            return output
        }

        var previous = transformedRow(source, 0, r, restoration)
        var current = transformedRow(source, 1, r, restoration)
        output.setPixels(previous, 0, width, 0, 0, width, 1)

        val sharpened = IntArray(width)
        for (y in 1 until height - 1) {
            val next = transformedRow(source, y + 1, r, restoration)
            sharpenRow(
                previous = previous,
                current = current,
                next = next,
                output = sharpened,
                amount = r.sharpness
            )
            output.setPixels(sharpened, 0, width, 0, y, width, 1)
            previous = current
            current = next
        }

        output.setPixels(current, 0, width, 0, height - 1, width, 1)
        return output
    }

    private fun transformedRow(
        source: Bitmap,
        y: Int,
        recipe: PageVisualRecipe,
        restoration: RestorationAnalysis?
    ): IntArray {
        val width = source.width
        val input = IntArray(width)
        val output = IntArray(width)
        source.getPixels(input, 0, width, 0, y, width, 1)
        transformRow(
            input = input,
            output = output,
            y = y,
            width = width,
            height = source.height,
            recipe = recipe,
            restoration = restoration
        )
        return output
    }

    private fun transformRow(
        input: IntArray,
        output: IntArray,
        y: Int,
        width: Int,
        height: Int,
        recipe: PageVisualRecipe,
        restoration: RestorationAnalysis?
    ) {
        val black = (recipe.blackPoint * 0.42f).coerceIn(0f, 0.42f)
        val white = (1f - recipe.whitePoint * 0.28f).coerceIn(0.58f, 1f)
        val tonalRange = (white - black).coerceAtLeast(0.08f)
        val brightnessOffset = recipe.brightness * 0.18f
        val contrastFactor = (1f + recipe.contrast * 0.95f).coerceIn(0.25f, 2.1f)
        val saturationFactor = (1f + recipe.saturation * 0.85f).coerceIn(0f, 1.85f)
        val warmth = recipe.warmth * 0.12f
        val grayscale =
            recipe.preset == ScanPreset.GRAYSCALE ||
                recipe.preset == ScanPreset.BLACK_WHITE

        for (x in input.indices) {
            val color = input[x]
            var red = Color.red(color) / 255f
            var green = Color.green(color) / 255f
            var blue = Color.blue(color) / 255f
            val alpha = Color.alpha(color)

            var localIllumination = luma(red, green, blue)
            var illuminationFactor = 1f

            restoration?.let { analysis ->
                red = (red * analysis.redGain).coerceIn(0f, 1f)
                green = (green * analysis.greenGain).coerceIn(0f, 1f)
                blue = (blue * analysis.blueGain).coerceIn(0f, 1f)

                localIllumination = analysis.sampleIllumination(
                    x = x,
                    y = y,
                    imageWidth = width,
                    imageHeight = height
                )
                illuminationFactor = RestorationMath.illuminationFactor(
                    observed = localIllumination,
                    target = analysis.paperLuma,
                    strength = recipe.illuminationCorrection
                )
                red = (red * illuminationFactor).coerceIn(0f, 1f)
                green = (green * illuminationFactor).coerceIn(0f, 1f)
                blue = (blue * illuminationFactor).coerceIn(0f, 1f)

                if (recipe.shadowNormalization > 0.001f) {
                    val correctedLocal = (
                        localIllumination * illuminationFactor
                        ).coerceIn(0f, 1f)
                    val correction = (
                        (analysis.paperLuma - correctedLocal) *
                            recipe.shadowNormalization *
                            0.48f
                        ).coerceIn(-0.16f, 0.24f)
                    red = (red + correction).coerceIn(0f, 1f)
                    green = (green + correction).coerceIn(0f, 1f)
                    blue = (blue + correction).coerceIn(0f, 1f)
                }

                if (recipe.localContrast > 0.001f) {
                    val correctedLocal = (
                        localIllumination * illuminationFactor
                        ).coerceIn(0f, 1f)
                    val currentLuminance = luma(red, green, blue)
                    val detail = currentLuminance - correctedLocal
                    val boost = detail * recipe.localContrast * 0.72f
                    red = (red + boost).coerceIn(0f, 1f)
                    green = (green + boost).coerceIn(0f, 1f)
                    blue = (blue + boost).coerceIn(0f, 1f)
                }
            }

            var luminance = luma(red, green, blue)
            val lifted = applyShadowHighlightCurve(
                luminance = luminance,
                shadows = recipe.shadows,
                highlights = recipe.highlights
            )
            val luminanceDelta = lifted - luminance
            red += luminanceDelta
            green += luminanceDelta
            blue += luminanceDelta

            red = ((red - black) / tonalRange).coerceIn(0f, 1f)
            green = ((green - black) / tonalRange).coerceIn(0f, 1f)
            blue = ((blue - black) / tonalRange).coerceIn(0f, 1f)

            red = ((red - 0.5f) * contrastFactor + 0.5f + brightnessOffset)
                .coerceIn(0f, 1f)
            green = ((green - 0.5f) * contrastFactor + 0.5f + brightnessOffset)
                .coerceIn(0f, 1f)
            blue = ((blue - 0.5f) * contrastFactor + 0.5f + brightnessOffset)
                .coerceIn(0f, 1f)

            red = (red + warmth).coerceIn(0f, 1f)
            blue = (blue - warmth).coerceIn(0f, 1f)

            luminance = luma(red, green, blue)
            red = (luminance + (red - luminance) * saturationFactor).coerceIn(0f, 1f)
            green = (luminance + (green - luminance) * saturationFactor).coerceIn(0f, 1f)
            blue = (luminance + (blue - luminance) * saturationFactor).coerceIn(0f, 1f)

            if (recipe.backgroundWhitening > 0.001f) {
                val lum = luma(red, green, blue)
                val maxChannel = max(red, max(green, blue))
                val minChannel = min(red, min(green, blue))
                val chroma = maxChannel - minChannel
                val colorProtection = if (
                    recipe.restorationProfile == RestorationProfile.WHITEBOARD
                ) {
                    smoothstep(0.07f, 0.34f, chroma) * 0.86f
                } else {
                    0f
                }
                val mask = (
                    smoothstep(0.58f, 0.95f, lum) *
                        recipe.backgroundWhitening *
                        (1f - colorProtection)
                    ).coerceIn(0f, 1f)
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

            val adaptiveStrength = when {
                recipe.version < PageVisualRecipe.CURRENT_VERSION -> 0f
                recipe.preset == ScanPreset.BLACK_WHITE ->
                    max(recipe.adaptiveBlackWhite, 0.82f)
                else -> recipe.adaptiveBlackWhite
            }
            if (adaptiveStrength > 0.001f) {
                val correctedLocal = (
                    localIllumination * illuminationFactor
                    ).coerceIn(0f, 1f)
                val current = luma(red, green, blue)
                val adapted = RestorationMath.adaptiveMix(
                    luminance = current,
                    localPaper = correctedLocal,
                    strength = adaptiveStrength,
                    profile = recipe.restorationProfile
                )
                if (
                    recipe.preset == ScanPreset.BLACK_WHITE ||
                    grayscale
                ) {
                    red = adapted
                    green = adapted
                    blue = adapted
                } else {
                    val delta = adapted - current
                    red = (red + delta).coerceIn(0f, 1f)
                    green = (green + delta).coerceIn(0f, 1f)
                    blue = (blue + delta).coerceIn(0f, 1f)
                }
            } else if (recipe.preset == ScanPreset.BLACK_WHITE) {
                val threshold =
                    0.70f -
                        recipe.blackPoint * 0.14f +
                        recipe.whitePoint * 0.08f
                val value = smoothstep(
                    threshold - 0.10f,
                    threshold + 0.10f,
                    luma(red, green, blue)
                )
                red = value
                green = value
                blue = value
            }

            output[x] = Color.argb(
                alpha,
                (red * 255f).roundToInt().coerceIn(0, 255),
                (green * 255f).roundToInt().coerceIn(0, 255),
                (blue * 255f).roundToInt().coerceIn(0, 255)
            )
        }
    }

    private fun sharpenRow(
        previous: IntArray,
        current: IntArray,
        next: IntArray,
        output: IntArray,
        amount: Float
    ) {
        val strength = (amount * 0.62f).coerceIn(0f, 0.62f)
        output[0] = current[0]
        output[current.lastIndex] = current[current.lastIndex]

        for (x in 1 until current.lastIndex) {
            val center = current[x]
            val left = current[x - 1]
            val right = current[x + 1]
            val up = previous[x]
            val down = next[x]

            fun sharpen(c: Int, l: Int, r: Int, u: Int, d: Int): Int {
                val avg = (l + r + u + d) / 4f
                return (c + (c - avg) * strength)
                    .roundToInt()
                    .coerceIn(0, 255)
            }

            output[x] = Color.argb(
                Color.alpha(center),
                sharpen(
                    Color.red(center),
                    Color.red(left),
                    Color.red(right),
                    Color.red(up),
                    Color.red(down)
                ),
                sharpen(
                    Color.green(center),
                    Color.green(left),
                    Color.green(right),
                    Color.green(up),
                    Color.green(down)
                ),
                sharpen(
                    Color.blue(center),
                    Color.blue(left),
                    Color.blue(right),
                    Color.blue(up),
                    Color.blue(down)
                )
            )
        }
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

    private fun luma(r: Float, g: Float, b: Float): Float =
        0.299f * r + 0.587f * g + 0.114f * b

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
