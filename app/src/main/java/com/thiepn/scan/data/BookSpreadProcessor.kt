package com.thiepn.scan.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

data class BookSpreadAnalysis(
    val likelySpread: Boolean,
    val autoSplitRecommended: Boolean,
    val confidence: Float,
    val gutterX: Float,
    val gutterContrast: Float,
    val seamStrength: Float,
    val consistency: Float,
    val dewarpStrength: Float,
    val reason: String
)

data class BookRenderedPage(
    val file: File,
    val width: Int,
    val height: Int,
    val side: BookPageSide,
    val dewarpStrength: Float
)

object BookSpreadScoring {
    fun evaluate(
        aspect: Float,
        gutterX: Float,
        gutterContrast: Float,
        seamStrength: Float,
        consistency: Float
    ): BookSpreadAnalysis {
        val aspectScore = ((aspect - 1.12f) / 0.72f).coerceIn(0f, 1f)
        val contrastScore = (gutterContrast / 0.085f).coerceIn(0f, 1f)
        val seamScore = (seamStrength / 0.16f).coerceIn(0f, 1f)
        val balanceScore = (
            1f - abs(gutterX - 0.5f) / 0.17f
            ).coerceIn(0f, 1f)

        val confidence = (
            aspectScore * 0.25f +
                contrastScore * 0.30f +
                seamScore * 0.18f +
                consistency.coerceIn(0f, 1f) * 0.17f +
                balanceScore * 0.10f
            ).coerceIn(0f, 1f)

        val likely = aspect >= 1.18f &&
            gutterX in 0.34f..0.66f &&
            confidence >= 0.48f
        val auto = likely && confidence >= 0.66f
        val dewarp = if (likely) {
            (
                0.018f +
                    contrastScore * 0.045f +
                    seamScore * 0.025f +
                    consistency.coerceIn(0f, 1f) * 0.018f
                ).coerceIn(0.018f, 0.105f)
        } else {
            0f
        }

        return BookSpreadAnalysis(
            likelySpread = likely,
            autoSplitRecommended = auto,
            confidence = confidence,
            gutterX = gutterX.coerceIn(0.32f, 0.68f),
            gutterContrast = gutterContrast.coerceAtLeast(0f),
            seamStrength = seamStrength.coerceAtLeast(0f),
            consistency = consistency.coerceIn(0f, 1f),
            dewarpStrength = dewarp,
            reason = when {
                auto -> "Strong center gutter detected"
                likely -> "Possible spread; review before splitting"
                aspect < 1.25f -> "Image may be a single page"
                else -> "Center gutter confidence is too low"
            }
        )
    }
}

object BookSpreadProcessor {
    private const val ANALYSIS_EDGE = 760
    private const val MESH_COLUMNS = 32
    private const val MESH_ROWS = 24
    private const val JPEG_QUALITY = 94

    fun analyze(file: File): BookSpreadAnalysis {
        val bitmap = runCatching {
            PageGeometryRenderer.renderFile(
                file = file,
                cropQuad = CropQuad.FULL,
                rotationDegrees = 0,
                maxLongEdge = ANALYSIS_EDGE
            )
        }.getOrNull() ?: return failedAnalysis("Unable to read spread image")

        return try {
            analyze(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun analyze(bitmap: Bitmap): BookSpreadAnalysis {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 160 || height < 120) {
            return failedAnalysis("Image is too small for spread analysis")
        }

        val aspect = width.toFloat() / height.toFloat()
        if (aspect < 1.12f) {
            return BookSpreadAnalysis(
                likelySpread = false,
                autoSplitRecommended = false,
                confidence = 0.08f,
                gutterX = 0.5f,
                gutterContrast = 0f,
                seamStrength = 0f,
                consistency = 0f,
                dewarpStrength = 0f,
                reason = "Page is not wide enough to be a two-page spread"
            )
        }

        val yStart = (height * 0.10f).roundToInt().coerceIn(1, height - 2)
        val yEnd = (height * 0.90f).roundToInt().coerceIn(yStart + 1, height - 1)
        val xStart = (width * 0.35f).roundToInt().coerceIn(2, width - 3)
        val xEnd = (width * 0.65f).roundToInt().coerceIn(xStart + 1, width - 3)
        val yStep = max(1, height / 180)
        val neighborOffset = max(5, (width * 0.055f).roundToInt())
        val probeRadius = max(1, width / 250)

        val columnLuma = FloatArray(width)
        for (x in 0 until width) {
            var sum = 0L
            var count = 0
            for (y in yStart until yEnd step yStep) {
                sum += luminance(bitmap.getPixel(x, y)).toLong()
                count++
            }
            columnLuma[x] = if (count == 0) 255f else sum.toFloat() / count
        }

        var bestX = width / 2
        var bestScore = Float.NEGATIVE_INFINITY
        var bestContrast = 0f
        var bestSeam = 0f

        for (x in xStart..xEnd) {
            val leftX = (x - neighborOffset).coerceAtLeast(1)
            val rightX = (x + neighborOffset).coerceAtMost(width - 2)
            val neighbor = (columnLuma[leftX] + columnLuma[rightX]) * 0.5f
            val contrast = ((neighbor - columnLuma[x]) / 255f).coerceIn(-0.25f, 0.35f)

            var seamTotal = 0f
            var seamCount = 0
            for (y in yStart until yEnd step yStep) {
                val xl = (x - probeRadius).coerceAtLeast(0)
                val xr = (x + probeRadius).coerceAtMost(width - 1)
                seamTotal += abs(
                    luminance(bitmap.getPixel(xr, y)) -
                        luminance(bitmap.getPixel(xl, y))
                ) / 255f
                seamCount++
            }
            val seam = if (seamCount == 0) 0f else seamTotal / seamCount
            val normalized = x.toFloat() / width
            val centerBias = (1f - abs(normalized - 0.5f) / 0.16f).coerceIn(0f, 1f)
            val score =
                contrast.coerceAtLeast(0f) * 1.65f +
                    seam * 0.80f +
                    centerBias * 0.12f

            if (score > bestScore) {
                bestScore = score
                bestX = x
                bestContrast = contrast.coerceAtLeast(0f)
                bestSeam = seam
            }
        }

        val consistency = gutterConsistency(
            bitmap = bitmap,
            gutterX = bestX,
            neighborOffset = neighborOffset
        )
        return BookSpreadScoring.evaluate(
            aspect = aspect,
            gutterX = bestX.toFloat() / width,
            gutterContrast = bestContrast,
            seamStrength = bestSeam,
            consistency = consistency
        )
    }

    fun renderSplitPages(
        sourceFile: File,
        leftDestination: File,
        rightDestination: File,
        analysis: BookSpreadAnalysis,
        dewarp: Boolean = true,
        maxLongEdge: Int = 3200
    ): List<BookRenderedPage> {
        require(sourceFile.isFile) { "Book spread image is unavailable" }
        val source = PageGeometryRenderer.renderFile(
            file = sourceFile,
            cropQuad = CropQuad.FULL,
            rotationDegrees = 0,
            maxLongEdge = maxLongEdge
        )

        try {
            val gutter = (
                source.width * analysis.gutterX.coerceIn(0.32f, 0.68f)
                ).roundToInt().coerceIn(48, source.width - 48)
            val seamTrim = max(1, (source.width * 0.004f).roundToInt())

            val leftWidth = (gutter - seamTrim).coerceAtLeast(32)
            val rightStart = (gutter + seamTrim).coerceAtMost(source.width - 32)
            val rightWidth = (source.width - rightStart).coerceAtLeast(32)

            val leftCrop = Bitmap.createBitmap(
                source,
                0,
                0,
                leftWidth,
                source.height
            )
            val rightCrop = Bitmap.createBitmap(
                source,
                rightStart,
                0,
                rightWidth,
                source.height
            )

            try {
                val strength = if (dewarp) analysis.dewarpStrength else 0f
                val left = cylindricalFlatten(
                    source = leftCrop,
                    side = BookPageSide.LEFT,
                    strength = strength
                )
                val right = cylindricalFlatten(
                    source = rightCrop,
                    side = BookPageSide.RIGHT,
                    strength = strength
                )

                try {
                    writeJpeg(left, leftDestination)
                    writeJpeg(right, rightDestination)

                    return listOf(
                        BookRenderedPage(
                            file = leftDestination,
                            width = left.width,
                            height = left.height,
                            side = BookPageSide.LEFT,
                            dewarpStrength = strength
                        ),
                        BookRenderedPage(
                            file = rightDestination,
                            width = right.width,
                            height = right.height,
                            side = BookPageSide.RIGHT,
                            dewarpStrength = strength
                        )
                    )
                } finally {
                    if (left !== leftCrop) left.recycle()
                    if (right !== rightCrop) right.recycle()
                }
            } finally {
                leftCrop.recycle()
                rightCrop.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    private fun cylindricalFlatten(
        source: Bitmap,
        side: BookPageSide,
        strength: Float
    ): Bitmap {
        if (strength <= 0.001f) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }

        val normalizedStrength = strength.coerceIn(0f, 0.12f)
        val widthScale = 1f + normalizedStrength * 0.55f
        val outWidth = (source.width * widthScale)
            .roundToInt()
            .coerceAtLeast(source.width)
        val outHeight = source.height

        val verts = FloatArray((MESH_COLUMNS + 1) * (MESH_ROWS + 1) * 2)
        var offset = 0
        for (row in 0..MESH_ROWS) {
            val v = row.toFloat() / MESH_ROWS
            for (col in 0..MESH_COLUMNS) {
                val u = col.toFloat() / MESH_COLUMNS
                val wave = sin(PI.toFloat() * u)
                val warpedU = when (side) {
                    BookPageSide.LEFT ->
                        (u - normalizedStrength * wave).coerceIn(0f, 1f)
                    BookPageSide.RIGHT ->
                        (u + normalizedStrength * wave).coerceIn(0f, 1f)
                }
                verts[offset++] = warpedU * outWidth
                verts[offset++] = v * outHeight
            }
        }

        return Bitmap.createBitmap(
            outWidth,
            outHeight,
            Bitmap.Config.ARGB_8888
        ).also { output ->
            Canvas(output).apply {
                drawColor(Color.WHITE)
                drawBitmapMesh(
                    source,
                    MESH_COLUMNS,
                    MESH_ROWS,
                    verts,
                    0,
                    null,
                    0,
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                )
            }
        }
    }

    private fun gutterConsistency(
        bitmap: Bitmap,
        gutterX: Int,
        neighborOffset: Int
    ): Float {
        val height = bitmap.height
        val width = bitmap.width
        val bands = 8
        var positiveBands = 0
        var totalStrength = 0f

        for (band in 0 until bands) {
            val yStart = (height * (0.08f + band * 0.105f))
                .roundToInt()
                .coerceIn(1, height - 2)
            val yEnd = (yStart + height * 0.09f)
                .roundToInt()
                .coerceIn(yStart + 1, height - 1)
            val step = max(1, (yEnd - yStart) / 24)
            var center = 0f
            var neighbors = 0f
            var count = 0

            for (y in yStart until yEnd step step) {
                center += luminance(bitmap.getPixel(gutterX, y))
                val left = (gutterX - neighborOffset).coerceIn(0, width - 1)
                val right = (gutterX + neighborOffset).coerceIn(0, width - 1)
                neighbors += (
                    luminance(bitmap.getPixel(left, y)) +
                        luminance(bitmap.getPixel(right, y))
                    ) * 0.5f
                count++
            }

            if (count > 0) {
                val delta = (neighbors - center) / count / 255f
                if (delta > 0.012f) positiveBands++
                totalStrength += delta.coerceAtLeast(0f)
            }
        }

        val coverage = positiveBands.toFloat() / bands
        val strength = (totalStrength / bands / 0.06f).coerceIn(0f, 1f)
        return (coverage * 0.65f + strength * 0.35f).coerceIn(0f, 1f)
    }

    private fun writeJpeg(bitmap: Bitmap, destination: File) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        FileOutputStream(temporary).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                "Unable to encode book page"
            }
            output.flush()
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            error("Unable to replace ${destination.name}")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            error("Unable to save ${destination.name}")
        }
    }

    private fun luminance(color: Int): Float {
        val r = (color shr 16) and 0xff
        val g = (color shr 8) and 0xff
        val b = color and 0xff
        return (r * 30 + g * 59 + b * 11) / 100f
    }

    private fun failedAnalysis(reason: String): BookSpreadAnalysis =
        BookSpreadAnalysis(
            likelySpread = false,
            autoSplitRecommended = false,
            confidence = 0f,
            gutterX = 0.5f,
            gutterContrast = 0f,
            seamStrength = 0f,
            consistency = 0f,
            dewarpStrength = 0f,
            reason = reason
        )
}
