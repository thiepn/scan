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

enum class RestorationProfile(val label: String) {
    OFF("Off"),
    DOCUMENT("Document"),
    RECEIPT("Receipt"),
    WHITEBOARD("Whiteboard"),
    BOOK("Book"),
    NOTES("Notes"),
    FORM("Form")
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
    val shadowNormalization: Float = 0f,
    val illuminationCorrection: Float = 0f,
    val localContrast: Float = 0f,
    val whiteBalance: Float = 0f,
    val adaptiveBlackWhite: Float = 0f,
    val restorationProfile: RestorationProfile = RestorationProfile.OFF
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
        shadowNormalization = shadowNormalization.coerceIn(0f, 1f),
        illuminationCorrection = illuminationCorrection.coerceIn(0f, 1f),
        localContrast = localContrast.coerceIn(0f, 1f),
        whiteBalance = whiteBalance.coerceIn(0f, 1f),
        adaptiveBlackWhite = adaptiveBlackWhite.coerceIn(0f, 1f)
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
                shadowNormalization,
                illuminationCorrection,
                localContrast,
                whiteBalance,
                adaptiveBlackWhite
            ).all { abs(it) <= epsilon }

    fun hasRestoration(epsilon: Float = 0.0001f): Boolean =
        illuminationCorrection > epsilon ||
            localContrast > epsilon ||
            whiteBalance > epsilon ||
            adaptiveBlackWhite > epsilon ||
            shadowNormalization > epsilon

    companion object {
        const val CURRENT_VERSION = 2

        fun forPreset(
            preset: ScanPreset,
            restorationProfile: RestorationProfile = defaultProfile(preset)
        ): PageVisualRecipe = when (preset) {
            ScanPreset.ORIGINAL -> PageVisualRecipe(
                preset = preset,
                restorationProfile = RestorationProfile.OFF
            )
            ScanPreset.AUTO -> PageVisualRecipe(
                preset = preset,
                contrast = 0.10f,
                highlights = -0.08f,
                shadows = 0.10f,
                blackPoint = 0.03f,
                whitePoint = 0.05f,
                sharpness = 0.16f,
                backgroundWhitening = 0.10f,
                shadowNormalization = 0.18f,
                illuminationCorrection = 0.28f,
                localContrast = 0.18f,
                whiteBalance = 0.24f,
                restorationProfile = restorationProfile
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
                shadowNormalization = 0.28f,
                illuminationCorrection = 0.42f,
                localContrast = 0.26f,
                whiteBalance = 0.34f,
                adaptiveBlackWhite = 0.04f,
                restorationProfile = restorationProfile
            )
            ScanPreset.COLOR -> PageVisualRecipe(
                preset = preset,
                contrast = 0.08f,
                highlights = -0.08f,
                shadows = 0.08f,
                saturation = 0.10f,
                sharpness = 0.12f,
                shadowNormalization = 0.14f,
                illuminationCorrection = 0.20f,
                localContrast = 0.12f,
                whiteBalance = 0.26f,
                restorationProfile = restorationProfile
            )
            ScanPreset.GRAYSCALE -> PageVisualRecipe(
                preset = preset,
                contrast = 0.14f,
                highlights = -0.08f,
                shadows = 0.10f,
                sharpness = 0.18f,
                backgroundWhitening = 0.08f,
                shadowNormalization = 0.16f,
                illuminationCorrection = 0.24f,
                localContrast = 0.22f,
                whiteBalance = 0.18f,
                adaptiveBlackWhite = 0.06f,
                restorationProfile = restorationProfile
            )
            ScanPreset.BLACK_WHITE -> PageVisualRecipe(
                preset = preset,
                contrast = 0.24f,
                blackPoint = 0.08f,
                whitePoint = 0.12f,
                sharpness = 0.18f,
                backgroundWhitening = 0.24f,
                shadowNormalization = 0.30f,
                illuminationCorrection = 0.48f,
                localContrast = 0.40f,
                whiteBalance = 0.24f,
                adaptiveBlackWhite = 0.88f,
                restorationProfile = restorationProfile
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
                shadowNormalization = 0.22f,
                illuminationCorrection = 0.34f,
                localContrast = 0.32f,
                whiteBalance = 0.22f,
                adaptiveBlackWhite = 0.10f,
                restorationProfile = restorationProfile
            )
            ScanPreset.RECEIPT -> PageVisualRecipe(
                preset = preset,
                brightness = 0.06f,
                contrast = 0.22f,
                shadows = 0.18f,
                blackPoint = 0.08f,
                whitePoint = 0.13f,
                saturation = -0.42f,
                sharpness = 0.24f,
                backgroundWhitening = 0.30f,
                shadowNormalization = 0.38f,
                illuminationCorrection = 0.62f,
                localContrast = 0.44f,
                whiteBalance = 0.52f,
                adaptiveBlackWhite = 0.44f,
                restorationProfile = RestorationProfile.RECEIPT
            )
            ScanPreset.WHITEBOARD -> PageVisualRecipe(
                preset = preset,
                brightness = 0.08f,
                contrast = 0.18f,
                highlights = -0.12f,
                shadows = 0.14f,
                saturation = 0.20f,
                sharpness = 0.18f,
                backgroundWhitening = 0.40f,
                shadowNormalization = 0.42f,
                illuminationCorrection = 0.70f,
                localContrast = 0.34f,
                whiteBalance = 0.64f,
                adaptiveBlackWhite = 0.04f,
                restorationProfile = RestorationProfile.WHITEBOARD
            )
        }

        fun forMode(mode: ScanMode): PageVisualRecipe {
            val preset = ScanModeProfiles.forMode(mode).defaultPreset
            val profile = when (mode) {
                ScanMode.RECEIPT -> RestorationProfile.RECEIPT
                ScanMode.WHITEBOARD -> RestorationProfile.WHITEBOARD
                ScanMode.BOOK -> RestorationProfile.BOOK
                ScanMode.NOTES -> RestorationProfile.NOTES
                ScanMode.FORM -> RestorationProfile.FORM
                ScanMode.PHOTO -> RestorationProfile.OFF
                else -> RestorationProfile.DOCUMENT
            }
            val base = forPreset(preset, profile)
            return when (mode) {
                ScanMode.BOOK -> base.copy(
                    illuminationCorrection = 0.50f,
                    localContrast = 0.24f,
                    whiteBalance = 0.18f,
                    shadowNormalization = 0.34f,
                    adaptiveBlackWhite = 0f
                )
                ScanMode.FORM -> base.copy(
                    illuminationCorrection = 0.38f,
                    localContrast = 0.30f,
                    whiteBalance = 0.30f,
                    adaptiveBlackWhite = 0.08f
                )
                ScanMode.PHOTO -> PageVisualRecipe.forPreset(ScanPreset.ORIGINAL)
                else -> base
            }.normalized()
        }

        fun forPresetInMode(
            preset: ScanPreset,
            mode: ScanMode
        ): PageVisualRecipe {
            val defaultPreset = ScanModeProfiles.forMode(mode).defaultPreset
            if (preset == defaultPreset) return forMode(mode)

            val profile = when (mode) {
                ScanMode.RECEIPT -> RestorationProfile.RECEIPT
                ScanMode.WHITEBOARD -> RestorationProfile.WHITEBOARD
                ScanMode.BOOK -> RestorationProfile.BOOK
                ScanMode.NOTES -> RestorationProfile.NOTES
                ScanMode.FORM -> RestorationProfile.FORM
                ScanMode.PHOTO -> RestorationProfile.OFF
                else -> RestorationProfile.DOCUMENT
            }
            return forPreset(
                preset = preset,
                restorationProfile = profile
            ).normalized()
        }

        fun defaultProfile(preset: ScanPreset): RestorationProfile = when (preset) {
            ScanPreset.ORIGINAL -> RestorationProfile.OFF
            ScanPreset.RECEIPT -> RestorationProfile.RECEIPT
            ScanPreset.WHITEBOARD -> RestorationProfile.WHITEBOARD
            ScanPreset.NOTES -> RestorationProfile.NOTES
            else -> RestorationProfile.DOCUMENT
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
            normalized.shadowNormalization,
            normalized.illuminationCorrection,
            normalized.localContrast,
            normalized.whiteBalance,
            normalized.adaptiveBlackWhite,
            normalized.restorationProfile.name
        ).joinToString(SEP)
    }

    fun decode(encoded: String?): PageVisualRecipe {
        if (encoded.isNullOrBlank()) return PageVisualRecipe()
        val parts = encoded.split(SEP)
        return when (parts.size) {
            13 -> decodeV1(parts)
            18 -> decodeV2(parts)
            else -> PageVisualRecipe()
        }
    }

    private fun decodeV1(parts: List<String>): PageVisualRecipe =
        runCatching {
            PageVisualRecipe(
                version = 1,
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
                shadowNormalization = parts[12].toFloat(),
                restorationProfile = RestorationProfile.OFF
            ).normalized().copy(version = 1)
        }.getOrElse { PageVisualRecipe() }

    private fun decodeV2(parts: List<String>): PageVisualRecipe =
        runCatching {
            require(parts[0].toInt() == PageVisualRecipe.CURRENT_VERSION)
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
                shadowNormalization = parts[12].toFloat(),
                illuminationCorrection = parts[13].toFloat(),
                localContrast = parts[14].toFloat(),
                whiteBalance = parts[15].toFloat(),
                adaptiveBlackWhite = parts[16].toFloat(),
                restorationProfile = RestorationProfile.valueOf(parts[17])
            ).normalized()
        }.getOrElse { PageVisualRecipe() }
}
