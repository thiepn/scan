package com.thiepn.scan.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class CaptureFingerprint(
    val hashHex: String,
    val meanLuma: Float,
    val edgeEnergy: Float,
    val aspectRatio: Float,
    val qualityScore: Float
)

object CaptureFrameAnalyzer {
    private const val HASH_WIDTH = 9
    private const val HASH_HEIGHT = 8
    private const val ANALYSIS_EDGE = 96

    fun analyze(file: File): CaptureFingerprint {
        require(file.isFile) { "Captured page image is unavailable" }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Captured page image cannot be decoded"
        }

        val sample = calculateSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            maxEdge = ANALYSIS_EDGE
        )
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: error("Captured page image cannot be decoded")

        return try {
            val hashBitmap = Bitmap.createScaledBitmap(
                decoded,
                HASH_WIDTH,
                HASH_HEIGHT,
                true
            )
            try {
                val hash = differenceHash(hashBitmap)
                val statistics = imageStatistics(decoded)
                val aspect = max(bounds.outWidth, bounds.outHeight).toFloat() /
                    min(bounds.outWidth, bounds.outHeight).coerceAtLeast(1).toFloat()
                val resolutionScore = (
                    max(bounds.outWidth, bounds.outHeight).toFloat() / 1800f
                    ).coerceIn(0f, 1f)
                val edgeScore = (statistics.second / 0.16f).coerceIn(0f, 1f)
                val exposureScore = (
                    1f - abs(statistics.first - 0.52f) / 0.52f
                    ).coerceIn(0f, 1f)
                val quality = (
                    resolutionScore * 0.45f +
                        edgeScore * 0.40f +
                        exposureScore * 0.15f
                    ).coerceIn(0f, 1f)

                CaptureFingerprint(
                    hashHex = hash.toULong().toString(16).padStart(16, '0'),
                    meanLuma = statistics.first,
                    edgeEnergy = statistics.second,
                    aspectRatio = aspect,
                    qualityScore = quality
                )
            } finally {
                if (hashBitmap !== decoded) hashBitmap.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    fun isLikelyDuplicate(
        candidate: CaptureFingerprint,
        referenceHashHex: String?,
        referenceMeanLuma: Float?,
        referenceEdgeEnergy: Float?,
        referenceAspectRatio: Float?
    ): Boolean {
        val referenceHash = referenceHashHex?.toULongOrNull(16) ?: return false
        val mean = referenceMeanLuma ?: return false
        val edge = referenceEdgeEnergy ?: return false
        val aspect = referenceAspectRatio ?: return false

        val candidateHash = candidate.hashHex.toULongOrNull(16) ?: return false
        val hamming = (candidateHash xor referenceHash).countOneBits()
        val meanDelta = abs(candidate.meanLuma - mean)
        val edgeDelta = abs(candidate.edgeEnergy - edge)
        val aspectDelta = abs(candidate.aspectRatio - aspect)

        return hamming <= 3 &&
            meanDelta <= 0.035f &&
            edgeDelta <= 0.045f &&
            aspectDelta <= 0.02f
    }

    private fun differenceHash(bitmap: Bitmap): Long {
        var hash = 0L
        var bit = 0
        for (y in 0 until HASH_HEIGHT) {
            for (x in 0 until HASH_WIDTH - 1) {
                val left = luma(bitmap.getPixel(x, y))
                val right = luma(bitmap.getPixel(x + 1, y))
                if (left > right) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        return hash
    }

    private fun imageStatistics(bitmap: Bitmap): Pair<Float, Float> {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 3 || height < 3) return 0.5f to 0f

        val step = max(1, max(width, height) / 72)
        var lumaTotal = 0f
        var lumaCount = 0
        var edgeTotal = 0f
        var edgeCount = 0

        for (y in 1 until height - 1 step step) {
            for (x in 1 until width - 1 step step) {
                val center = luma(bitmap.getPixel(x, y))
                lumaTotal += center
                lumaCount++

                val left = luma(bitmap.getPixel(x - 1, y))
                val right = luma(bitmap.getPixel(x + 1, y))
                val up = luma(bitmap.getPixel(x, y - 1))
                val down = luma(bitmap.getPixel(x, y + 1))
                val secondDerivative = abs(
                    4f * center - left - right - up - down
                )
                edgeTotal += secondDerivative
                edgeCount++
            }
        }

        val mean = if (lumaCount == 0) 0.5f else lumaTotal / lumaCount
        val edge = if (edgeCount == 0) 0f else edgeTotal / edgeCount
        return mean.coerceIn(0f, 1f) to edge.coerceAtLeast(0f)
    }

    private fun luma(color: Int): Float {
        val r = (color shr 16) and 0xff
        val g = (color shr 8) and 0xff
        val b = color and 0xff
        return (r * 0.299f + g * 0.587f + b * 0.114f) / 255f
    }

    private fun calculateSampleSize(
        width: Int,
        height: Int,
        maxEdge: Int
    ): Int {
        var sample = 1
        while (
            max(width / sample, height / sample) > maxEdge * 2 &&
            sample < 64
        ) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }
}
