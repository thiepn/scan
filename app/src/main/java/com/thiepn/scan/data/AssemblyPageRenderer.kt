package com.thiepn.scan.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.File
import kotlin.math.min

object AssemblyPageRenderer {
    const val DEFAULT_WIDTH = 1654
    const val DEFAULT_HEIGHT = 2339

    fun createBlank(
        destination: File,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT
    ): Pair<Int, Int> {
        val bitmap = Bitmap.createBitmap(
            width.coerceAtLeast(600),
            height.coerceAtLeast(800),
            Bitmap.Config.ARGB_8888
        )
        bitmap.eraseColor(Color.WHITE)
        save(bitmap, destination)
        val result = bitmap.width to bitmap.height
        bitmap.recycle()
        return result
    }

    fun createDivider(
        destination: File,
        title: String,
        subtitle: String = "",
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT
    ): Pair<Int, Int> {
        val bitmap = Bitmap.createBitmap(
            width.coerceAtLeast(600),
            height.coerceAtLeast(800),
            Bitmap.Config.ARGB_8888
        )
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.rgb(25, 25, 28)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = min(bitmap.width, bitmap.height) * 0.055f
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.rgb(80, 80, 86)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textSize = min(bitmap.width, bitmap.height) * 0.026f
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(175, 175, 182)
            strokeWidth = min(bitmap.width, bitmap.height) * 0.002f
        }

        val centerX = bitmap.width / 2f
        val centerY = bitmap.height / 2f
        canvas.drawText(
            fit(titlePaint, title.ifBlank { "Section" }, bitmap.width * 0.78f),
            centerX,
            centerY - titlePaint.textSize * 0.2f,
            titlePaint
        )
        canvas.drawLine(
            bitmap.width * 0.24f,
            centerY + titlePaint.textSize * 0.55f,
            bitmap.width * 0.76f,
            centerY + titlePaint.textSize * 0.55f,
            linePaint
        )
        if (subtitle.isNotBlank()) {
            canvas.drawText(
                fit(subtitlePaint, subtitle, bitmap.width * 0.72f),
                centerX,
                centerY + titlePaint.textSize * 1.35f,
                subtitlePaint
            )
        }

        save(bitmap, destination)
        val result = bitmap.width to bitmap.height
        bitmap.recycle()
        return result
    }

    private fun fit(paint: Paint, text: String, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var value = text
        while (value.length > 4 && paint.measureText(value + "…") > maxWidth) {
            value = value.dropLast(1)
        }
        return value.trimEnd() + "…"
    }

    private fun save(bitmap: Bitmap, destination: File) {
        destination.parentFile?.mkdirs()
        destination.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
        }
    }
}
