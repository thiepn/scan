package com.thiepn.scan.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

object PageGeometryRenderer {
    private const val DEFAULT_GEOMETRY_MAX_EDGE = 5000

    fun renderFile(
        file: File,
        cropQuad: CropQuad,
        rotationDegrees: Int,
        maxLongEdge: Int? = null
    ): Bitmap {
        val decoded = decodeBounded(
            file = file,
            maxLongEdge = maxLongEdge ?: DEFAULT_GEOMETRY_MAX_EDGE
        )
        var current = decoded

        if (!cropQuad.isFullFrame()) {
            val transformed = perspectiveTransform(current, cropQuad, maxLongEdge)
            if (transformed !== current) current.recycle()
            current = transformed
        } else if (maxLongEdge != null) {
            val scaled = scaleToLongEdge(current, maxLongEdge)
            if (scaled !== current) current.recycle()
            current = scaled
        }

        val rotated = rotateBitmap(current, rotationDegrees)
        if (rotated !== current) current.recycle()
        current = rotated

        return current
    }

    fun renderUnrotatedForPdf(
        file: File,
        cropQuad: CropQuad,
        maxLongEdge: Int?
    ): Bitmap {
        val decoded = decodeBounded(
            file = file,
            maxLongEdge = maxLongEdge ?: DEFAULT_GEOMETRY_MAX_EDGE
        )
        if (cropQuad.isFullFrame()) {
            if (maxLongEdge == null) return decoded
            val scaled = scaleToLongEdge(decoded, maxLongEdge)
            if (scaled !== decoded) decoded.recycle()
            return scaled
        }

        val transformed = perspectiveTransform(decoded, cropQuad, maxLongEdge)
        if (transformed !== decoded) decoded.recycle()
        return transformed
    }

    private fun perspectiveTransform(
        source: Bitmap,
        quad: CropQuad,
        maxLongEdge: Int?
    ): Bitmap {
        val sourceWidth = source.width.toFloat()
        val sourceHeight = source.height.toFloat()
        val points = quad.points.map { point ->
            point.x * sourceWidth to point.y * sourceHeight
        }

        val widthTop = distance(points[0], points[1])
        val widthBottom = distance(points[3], points[2])
        val heightLeft = distance(points[0], points[3])
        val heightRight = distance(points[1], points[2])

        var outWidth = max(widthTop, widthBottom).roundToInt().coerceAtLeast(32)
        var outHeight = max(heightLeft, heightRight).roundToInt().coerceAtLeast(32)

        val longEdgeLimit = maxLongEdge ?: DEFAULT_GEOMETRY_MAX_EDGE
        val currentLongEdge = max(outWidth, outHeight)
        if (currentLongEdge > longEdgeLimit) {
            val scale = longEdgeLimit.toFloat() / currentLongEdge
            outWidth = max(32, (outWidth * scale).roundToInt())
            outHeight = max(32, (outHeight * scale).roundToInt())
        }

        val src = floatArrayOf(
            points[0].first, points[0].second,
            points[1].first, points[1].second,
            points[2].first, points[2].second,
            points[3].first, points[3].second
        )
        val dst = floatArrayOf(
            0f, 0f,
            outWidth.toFloat(), 0f,
            outWidth.toFloat(), outHeight.toFloat(),
            0f, outHeight.toFloat()
        )

        val matrix = Matrix()
        check(matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
            "Unable to calculate perspective transform"
        }

        return Bitmap.createBitmap(
            outWidth,
            outHeight,
            Bitmap.Config.ARGB_8888
        ).also { output ->
            Canvas(output).apply {
                drawColor(Color.WHITE)
                drawBitmap(
                    source,
                    matrix,
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                )
            }
        }
    }

    private fun decodeBounded(file: File, maxLongEdge: Int): Bitmap {
        require(file.isFile) { "Page image is unavailable" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unreadable page image" }

        var sample = 1
        while (
            max(bounds.outWidth / sample, bounds.outHeight / sample) > maxLongEdge * 2 &&
            sample < 32
        ) {
            sample *= 2
        }

        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: error("Unable to decode page image")
    }

    private fun scaleToLongEdge(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
        val currentLongEdge = max(bitmap.width, bitmap.height)
        if (currentLongEdge <= maxLongEdge) return bitmap

        val scale = maxLongEdge.toFloat() / currentLongEdge
        val width = max(1, (bitmap.width * scale).roundToInt())
        val height = max(1, (bitmap.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val degrees = PageRotation.normalize(rotationDegrees)
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }


    private fun distance(
        a: Pair<Float, Float>,
        b: Pair<Float, Float>
    ): Float = hypot(
        (a.first - b.first).toDouble(),
        (a.second - b.second).toDouble()
    ).toFloat()
}
