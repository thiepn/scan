package com.thiepn.scan.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.util.UUID
import kotlin.math.max

enum class PdfQuality(
    val maxLongEdge: Int?,
    val jpegQuality: Float
) {
    ORIGINAL(null, 1f),
    HIGH(3000, 0.88f),
    BALANCED(2200, 0.78f),
    SMALL(1400, 0.62f)
}

class PdfEngine(
    private val context: Context,
    private val ocr: OcrEngine
) {
    suspend fun createSearchablePdf(
        pages: List<PageEntity>,
        destination: File,
        password: String? = null,
        quality: PdfQuality = PdfQuality.ORIGINAL
    ) {
        require(pages.isNotEmpty()) { "Document has no pages" }
        destination.parentFile?.mkdirs()

        PDDocument().use { document ->
            val font = context.assets
                .open("com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf")
                .use { PDType0Font.load(document, it) }

            pages.sortedBy { it.position }.forEach { pageEntity ->
                val imageFile = File(pageEntity.imagePath)
                require(imageFile.isFile) { "Missing page image" }

                val imageWidth = pageEntity.width.coerceAtLeast(1)
                val imageHeight = pageEntity.height.coerceAtLeast(1)
                val (pdfWidth, pdfHeight) = pageSize(imageWidth, imageHeight)
                val page = PDPage(PDRectangle(pdfWidth, pdfHeight))
                document.addPage(page)

                PDPageContentStream(document, page).use { stream ->
                    if (quality == PdfQuality.ORIGINAL) {
                        imageFile.inputStream().use { input ->
                            val image = JPEGFactory.createFromStream(document, input)
                            stream.drawImage(image, 0f, 0f, pdfWidth, pdfHeight)
                        }
                    } else {
                        val targetEdge = requireNotNull(quality.maxLongEdge)
                        val bitmap = decodeScaled(imageFile, targetEdge)
                        try {
                            val image = JPEGFactory.createFromImage(
                                document,
                                bitmap,
                                quality.jpegQuality
                            )
                            stream.drawImage(image, 0f, 0f, pdfWidth, pdfHeight)
                        } finally {
                            bitmap.recycle()
                        }
                    }

                    val recognition = runCatching { ocr.recognizeDetailed(imageFile) }.getOrNull()
                    recognition?.words?.forEach { word ->
                        addInvisibleWord(
                            stream = stream,
                            font = font,
                            word = word,
                            sourceWidth = imageWidth,
                            sourceHeight = imageHeight,
                            pageWidth = pdfWidth,
                            pageHeight = pdfHeight
                        )
                    }
                }
            }

            if (!password.isNullOrBlank()) {
                protect(document, password)
            }

            document.documentInformation.producer = "Scan"
            document.save(destination)
        }
    }

    fun protectExisting(
        source: File,
        destination: File,
        password: String
    ) {
        require(source.isFile) { "PDF source is unavailable" }
        require(password.isNotBlank()) { "Password cannot be blank" }
        destination.parentFile?.mkdirs()

        PDDocument.load(source).use { document ->
            protect(document, password)
            document.save(destination)
        }
    }

    fun extractPages(
        source: File,
        pageIndices: List<Int>,
        destination: File
    ) {
        require(source.isFile) { "PDF source is unavailable" }
        require(pageIndices.isNotEmpty()) { "No pages selected" }
        destination.parentFile?.mkdirs()

        PDDocument.load(source).use { sourceDocument ->
            PDDocument().use { output ->
                pageIndices.distinct().forEach { index ->
                    require(index in 0 until sourceDocument.numberOfPages) {
                        "Page ${index + 1} is outside the document"
                    }
                    output.importPage(sourceDocument.getPage(index))
                }
                output.documentInformation.producer = "Scan"
                output.save(destination)
            }
        }
    }

    fun merge(
        sources: List<File>,
        destination: File
    ) {
        require(sources.isNotEmpty()) { "No PDFs selected" }
        sources.forEach { require(it.isFile) { "Missing PDF source" } }
        destination.parentFile?.mkdirs()

        PDFMergerUtility().apply {
            sources.forEach(::addSource)
            destinationFileName = destination.absolutePath
            mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
        }
    }

    private fun decodeScaled(file: File, maxLongEdge: Int): Bitmap {
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

        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: error("Unable to decode page image")

        val currentLongEdge = max(decoded.width, decoded.height)
        if (currentLongEdge <= maxLongEdge) return decoded

        val scale = maxLongEdge.toFloat() / currentLongEdge
        val width = max(1, (decoded.width * scale).toInt())
        val height = max(1, (decoded.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(decoded, width, height, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun addInvisibleWord(
        stream: PDPageContentStream,
        font: PDType0Font,
        word: OcrWordBox,
        sourceWidth: Int,
        sourceHeight: Int,
        pageWidth: Float,
        pageHeight: Float
    ) {
        if (word.text.isBlank()) return
        if (word.right <= word.left || word.bottom <= word.top) return

        val encodable = runCatching { font.encode(word.text) }.isSuccess
        if (!encodable) return

        val x = word.left.toFloat() / sourceWidth * pageWidth
        val y = pageHeight - (word.bottom.toFloat() / sourceHeight * pageHeight)
        val desiredWidth = (word.right - word.left).toFloat() / sourceWidth * pageWidth
        val desiredHeight = (word.bottom - word.top).toFloat() / sourceHeight * pageHeight
        val fontSize = max(1f, desiredHeight * 0.86f)

        val naturalWidth = runCatching {
            font.getStringWidth(word.text) / 1000f * fontSize
        }.getOrDefault(desiredWidth).coerceAtLeast(0.1f)
        val horizontalScale = (desiredWidth / naturalWidth * 100f).coerceIn(45f, 220f)

        runCatching {
            stream.beginText()
            stream.setRenderingMode(RenderingMode.NEITHER)
            stream.setFont(font, fontSize)
            stream.setHorizontalScaling(horizontalScale)
            stream.setTextMatrix(Matrix.getTranslateInstance(x, y))
            stream.showText(word.text)
            stream.endText()
        }.onFailure {
            runCatching { stream.endText() }
        }
    }

    private fun pageSize(width: Int, height: Int): Pair<Float, Float> {
        val longEdge = 842f
        return if (height >= width) {
            val pageHeight = longEdge
            val pageWidth = (longEdge * width.toFloat() / height).coerceAtLeast(72f)
            pageWidth to pageHeight
        } else {
            val pageWidth = longEdge
            val pageHeight = (longEdge * height.toFloat() / width).coerceAtLeast(72f)
            pageWidth to pageHeight
        }
    }

    private fun protect(document: PDDocument, userPassword: String) {
        val permission = AccessPermission()
        val ownerPassword = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        val policy = StandardProtectionPolicy(ownerPassword, userPassword, permission).apply {
            encryptionKeyLength = 256
        }
        document.protect(policy)
    }
}
