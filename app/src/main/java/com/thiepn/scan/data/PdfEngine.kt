package com.thiepn.scan.data

import android.content.Context
import android.graphics.Bitmap
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
        quality: PdfQuality = PdfQuality.ORIGINAL,
        includeOcrTextLayer: Boolean = true
    ) {
        require(pages.isNotEmpty()) { "Document has no pages" }
        destination.parentFile?.mkdirs()

        PDDocument().use { document ->
            val font = context.assets
                .open("com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf")
                .use { PDType0Font.load(document, it) }

            pages.sortedWith(compareBy<PageEntity> { it.sortKey }.thenBy { it.position }).forEach { pageEntity ->
                val imageFile = File(pageEntity.imagePath)
                require(imageFile.isFile) { "Missing page image" }

                val cropQuad = CropQuadCodec.decode(pageEntity.cropQuad)
                val recipe = PageVisualRecipeCodec.decode(pageEntity.visualRecipe)
                val cleanup = PageCleanupRecipeCodec.decode(pageEntity.cleanupRecipe)
                val geometryEdited = !cropQuad.isFullFrame()
                val directJpeg =
                    quality == PdfQuality.ORIGINAL &&
                        !geometryEdited &&
                        recipe.isOriginal() &&
                        cleanup.isEmpty()

                var geometryBitmap: Bitmap? = null
                var visualBitmap: Bitmap? = null
                val imageWidth: Int
                val imageHeight: Int

                if (directJpeg) {
                    imageWidth = pageEntity.width.coerceAtLeast(1)
                    imageHeight = pageEntity.height.coerceAtLeast(1)
                } else {
                    val rawGeometry = PageGeometryRenderer.renderUnrotatedForPdf(
                        file = imageFile,
                        cropQuad = cropQuad,
                        maxLongEdge = quality.maxLongEdge
                    )
                    val cleanedGeometry = PageCleanupRenderer.apply(
                        rawGeometry,
                        cleanup
                    )
                    if (cleanedGeometry !== rawGeometry) {
                        rawGeometry.recycle()
                    }
                    geometryBitmap = cleanedGeometry
                    visualBitmap = ImageEnhancementRenderer.apply(
                        cleanedGeometry,
                        recipe
                    )
                    imageWidth = visualBitmap.width
                    imageHeight = visualBitmap.height
                }

                val (pdfWidth, pdfHeight) = pageSize(imageWidth, imageHeight)
                val page = PDPage(PDRectangle(pdfWidth, pdfHeight)).apply {
                    rotation = PageRotation.normalize(pageEntity.rotationDegrees)
                }
                document.addPage(page)

                try {
                    PDPageContentStream(document, page).use { stream ->
                        if (directJpeg) {
                            imageFile.inputStream().use { input ->
                                val image = JPEGFactory.createFromStream(document, input)
                                stream.drawImage(image, 0f, 0f, pdfWidth, pdfHeight)
                            }
                        } else {
                            val bitmap = requireNotNull(visualBitmap)
                            val image = JPEGFactory.createFromImage(
                                document,
                                bitmap,
                                if (quality == PdfQuality.ORIGINAL) 0.94f else quality.jpegQuality
                            )
                            stream.drawImage(image, 0f, 0f, pdfWidth, pdfHeight)
                        }

                        val recognition = if (includeOcrTextLayer) {
                            runCatching {
                                val geometry = geometryBitmap
                                if (geometry != null) {
                                    ocr.recognizeDetailed(
                                        geometry,
                                        OcrScript.fromStored(pageEntity.ocrScript)
                                    )
                                } else {
                                    ocr.recognizeDetailed(
                                        imageFile,
                                        OcrScript.fromStored(pageEntity.ocrScript)
                                    )
                                }
                            }.getOrNull()
                        } else {
                            null
                        }

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
                } finally {
                    val visual = visualBitmap
                    val geometry = geometryBitmap
                    if (visual != null && visual !== geometry) visual.recycle()
                    geometry?.recycle()
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
        destination: File,
        password: String? = null,
        rotationDeltas: List<Int>? = null
    ) {
        require(source.isFile) { "PDF source is unavailable" }
        require(pageIndices.isNotEmpty()) { "No pages selected" }
        destination.parentFile?.mkdirs()

        PDDocument.load(source).use { sourceDocument ->
            PDDocument().use { output ->
                pageIndices.forEachIndexed { outputIndex, index ->
                    require(index in 0 until sourceDocument.numberOfPages) {
                        "Page ${index + 1} is outside the document"
                    }
                    val imported = output.importPage(sourceDocument.getPage(index))
                    val delta = rotationDeltas?.getOrNull(outputIndex) ?: 0
                    imported.rotation = PageRotation.normalize(imported.rotation + delta)
                }
                if (!password.isNullOrBlank()) {
                    protect(output, password)
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
