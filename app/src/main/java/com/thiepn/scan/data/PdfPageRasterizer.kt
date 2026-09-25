package com.thiepn.scan.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PdfPageRasterizer(
    private val deviceCapabilities: DeviceCapabilityPolicy
) {
    fun render(
        pdf: File,
        outputForPage: (Int) -> File
    ): List<RenderedPage> {
        val results = mutableListOf<RenderedPage>()
        openRenderer(pdf) { renderer ->
            for (index in 0 until renderer.pageCount) {
                results += renderPage(
                    renderer = renderer,
                    index = index,
                    output = outputForPage(index)
                )
            }
        }
        return results
    }

    suspend fun renderIncrementally(
        pdf: File,
        outputForPage: (Int) -> File,
        onPageRendered: suspend (Int, RenderedPage) -> Unit
    ) {
        ParcelFileDescriptor.open(
            pdf,
            ParcelFileDescriptor.MODE_READ_ONLY
        ).use { fd ->
            PdfRenderer(fd).use { renderer ->
                for (index in 0 until renderer.pageCount) {
                    val rendered = renderPage(
                        renderer = renderer,
                        index = index,
                        output = outputForPage(index)
                    )
                    onPageRendered(index, rendered)
                }
            }
        }
    }

    private fun openRenderer(
        pdf: File,
        block: (PdfRenderer) -> Unit
    ) {
        ParcelFileDescriptor.open(
            pdf,
            ParcelFileDescriptor.MODE_READ_ONLY
        ).use { fd ->
            PdfRenderer(fd).use(block)
        }
    }

    private fun renderPage(
        renderer: PdfRenderer,
        index: Int,
        output: File
    ): RenderedPage {
        return renderer.openPage(index).use { page ->
            val maxEdge = max(
                page.width,
                page.height
            ).coerceAtLeast(1)
            val renderLongEdge =
                deviceCapabilities.budget.pdfImportLongEdge
            val scale = min(
                4f,
                renderLongEdge.toFloat() / maxEdge.toFloat()
            ).coerceIn(0.10f, 4f)
            val width = (
                page.width * scale
                ).roundToInt().coerceAtLeast(1)
            val height = (
                page.height * scale
                ).roundToInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
            )
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(
                    bitmap,
                    null,
                    null,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                )
                output.parentFile?.mkdirs()
                output.outputStream().use { stream ->
                    check(
                        bitmap.compress(
                            Bitmap.CompressFormat.JPEG,
                            94,
                            stream
                        )
                    ) {
                        "Unable to persist imported PDF page"
                    }
                }
                RenderedPage(output, width, height)
            } finally {
                bitmap.recycle()
            }
        }
    }
}

data class RenderedPage(
    val file: File,
    val width: Int,
    val height: Int
)
