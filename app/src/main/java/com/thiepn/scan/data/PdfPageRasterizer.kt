package com.thiepn.scan.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PdfPageRasterizer {
    fun render(pdf: File, outputForPage: (Int) -> File): List<RenderedPage> {
        val results = mutableListOf<RenderedPage>()
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                for (index in 0 until renderer.pageCount) {
                    renderer.openPage(index).use { page ->
                        val maxEdge = max(page.width, page.height).coerceAtLeast(1)
                        val scale = min(4f, 2400f / maxEdge.toFloat()).coerceAtLeast(1f)
                        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val file = outputForPage(index)
                        file.parentFile?.mkdirs()
                        file.outputStream().use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 94, out)
                        }
                        bitmap.recycle()
                        results += RenderedPage(file, width, height)
                    }
                }
            }
        }
        return results
    }
}

data class RenderedPage(val file: File, val width: Int, val height: Int)
