package com.thiepn.scan.data

import kotlin.math.abs

enum class ScanPreset {
    ORIGINAL,
    AUTO,
    CLEAN,
    COLOR,
    GRAYSCALE,
    BLACK_WHITE,
    NOTES,
    RECEIPT,
    WHITEBOARD
}

data class PageVisualRecipe(
    val version: Int = CURRENT_VERSION,
    val preset: ScanPreset = ScanPreset.ORIGINAL,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val blackPoint: Float = 0f,
    val whitePoint: Float = 0f,
    val warmth: Float = 0f,
    val saturation: Float = 0f,
    val sharpness: Float = 0f,
    val backgroundWhitening: Float = 0f,
    val shadowNormalization: Float = 0f
) {
    fun normalized(): PageVisualRecipe = copy(
        version = CURRENT_VERSION,
        brightness = brightness.coerceIn(-1f, 1f),
        contrast = contrast.coerceIn(-1f, 1f),
        highlights = highlights.coerceIn(-1f, 1f),
        shadows = shadows.coerceIn(-1f, 1f),
        blackPoint = blackPoint.coerceIn(0f, 1f),
        whitePoint = whitePoint.coerceIn(0f, 1f),
        warmth = warmth.coerceIn(-1f, 1f),
        saturation = saturation.coerceIn(-1f, 1f),
        sharpness = sharpness.coerceIn(0f, 1f),
        backgroundWhitening = backgroundWhitening.coerceIn(0f, 1f),
        shadowNormalization = shadowNormalization.coerceIn(0f, 1f)
    )

    fun isOriginal(epsilon: Float = 0.0001f): Boolean =
        preset == ScanPreset.ORIGINAL &&
            listOf(
                brightness,
                contrast,
                highlights,
                shadows,
                blackPoint,
                whitePoint,
                warmth,
                saturation,
                sharpness,
                backgroundWhitening,
                shadowNormalization
            ).all { abs(it) <= epsilon }

    companion object {
        const val CURRENT_VERSION = 1

        fun forPreset(preset: ScanPreset): PageVisualRecipe = when (preset) {
            ScanPreset.ORIGINAL -> PageVisualRecipe(preset = preset)
            ScanPreset.AUTO -> PageVisualRecipe(
                preset = preset,
                contrast = 0.10f,
                highlights = -0.08f,
                shadows = 0.10f,
                blackPoint = 0.03f,
                whitePoint = 0.05f,
                sharpness = 0.16f,
                backgroundWhitening = 0.10f,
                shadowNormalization = 0.12f
            )
            ScanPreset.CLEAN -> PageVisualRecipe(
                preset = preset,
                brightness = 0.04f,
                contrast = 0.18f,
                highlights = -0.12f,
                shadows = 0.14f,
                blackPoint = 0.05f,
                whitePoint = 0.10f,
                saturation = -0.08f,
                sharpness = 0.20f,
                backgroundWhitening = 0.22f,
                shadowNormalization = 0.20f
            )
            ScanPreset.COLOR -> PageVisualRecipe(
                preset = preset,
                contrast = 0.08f,
                highlights = -0.08f,
                shadows = 0.08f,
                saturation = 0.10f,
                sharpness = 0.12f,
                shadowNormalization = 0.10f
            )
            ScanPreset.GRAYSCALE -> PageVisualRecipe(
                preset = preset,
                contrast = 0.14f,
                highlights = -0.08f,
                shadows = 0.10f,
                sharpness = 0.18f,
                backgroundWhitening = 0.08f
            )
            ScanPreset.BLACK_WHITE -> PageVisualRecipe(
                preset = preset,
                contrast = 0.34f,
                blackPoint = 0.10f,
                whitePoint = 0.16f,
                sharpness = 0.18f,
                backgroundWhitening = 0.28f,
                shadowNormalization = 0.20f
            )
            ScanPreset.NOTES -> PageVisualRecipe(
                preset = preset,
                contrast = 0.20f,
                shadows = 0.16f,
                blackPoint = 0.06f,
                whitePoint = 0.10f,
                saturation = -0.12f,
                sharpness = 0.25f,
                backgroundWhitening = 0.18f,
                shadowNormalization = 0.14f
            )
            ScanPreset.RECEIPT -> PageVisualRecipe(
                preset = preset,
                brightness = 0.06f,
                contrast = 0.26f,
                shadows = 0.20f,
                blackPoint = 0.09f,
                whitePoint = 0.15f,
                saturation = -0.35f,
                sharpness = 0.24f,
                backgroundWhitening = 0.30f,
                shadowNormalization = 0.24f
            )
            ScanPreset.WHITEBOARD -> PageVisualRecipe(
                preset = preset,
                brightness = 0.08f,
                contrast = 0.20f,
                highlights = -0.14f,
                shadows = 0.16f,
                saturation = 0.18f,
                sharpness = 0.18f,
                backgroundWhitening = 0.36f,
                shadowNormalization = 0.30f
            )
        }
    }
}

object PageVisualRecipeCodec {
    private const val SEP = "|"

    fun encode(recipe: PageVisualRecipe?): String? {
        val normalized = recipe?.normalized() ?: return null
        if (normalized.isOriginal()) return null
        return listOf(
            normalized.version,
            normalized.preset.name,
            normalized.brightness,
            normalized.contrast,
            normalized.highlights,
            normalized.shadows,
            normalized.blackPoint,
            normalized.whitePoint,
            normalized.warmth,
            normalized.saturation,
            normalized.sharpness,
            normalized.backgroundWhitening,
            normalized.shadowNormalization
        ).joinToString(SEP)
    }

    fun decode(encoded: String?): PageVisualRecipe {
        if (encoded.isNullOrBlank()) return PageVisualRecipe()
        val parts = encoded.split(SEP)
        if (parts.size != 13) return PageVisualRecipe()

        return runCatching {
            PageVisualRecipe(
                version = parts[0].toInt(),
                preset = ScanPreset.valueOf(parts[1]),
                brightness = parts[2].toFloat(),
                contrast = parts[3].toFloat(),
                highlights = parts[4].toFloat(),
                shadows = parts[5].toFloat(),
                blackPoint = parts[6].toFloat(),
                whitePoint = parts[7].toFloat(),
                warmth = parts[8].toFloat(),
                saturation = parts[9].toFloat(),
                sharpness = parts[10].toFloat(),
                backgroundWhitening = parts[11].toFloat(),
                shadowNormalization = parts[12].toFloat()
            ).normalized()
        }.getOrElse { PageVisualRecipe() }
    }
}
