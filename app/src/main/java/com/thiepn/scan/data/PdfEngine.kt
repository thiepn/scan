package com.thiepn.scan.data

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabelRange
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabels
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
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
        includeOcrTextLayer: Boolean = true,
        documentTitle: String = "",
        publishingSettings: PublishingSettings = PublishingSettings(),
        complianceSettings: ComplianceSettings = ComplianceSettings()
    ) {
        require(pages.isNotEmpty()) { "Document has no pages" }
        val compliance = complianceSettings.normalized()
        require(password.isNullOrBlank() || compliance.allowsEncryption()) {
            "PDF/A exports cannot be password encrypted"
        }
        destination.parentFile?.mkdirs()

        PDDocument().use { document ->
            PdfStandardsSupport.prepareDocument(
                context = context,
                document = document,
                documentTitle = documentTitle,
                publishing = publishingSettings,
                compliance = compliance
            )
            val tagger = if (
                compliance.accessibilityMode ==
                AccessibilityMode.TAGGED_OCR
            ) {
                TaggedOcrBuilder(
                    document,
                    compliance.documentLanguage
                )
            } else {
                null
            }
            val orderedPages = pages.sortedWith(
                compareBy<PageEntity> { it.sortKey }
                    .thenBy { it.position }
            )
            val font = context.assets
                .open("com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf")
                .use { PDType0Font.load(document, it) }

            orderedPages.forEachIndexed { pageIndex, pageEntity ->
                val imageFile = File(pageEntity.imagePath)
                require(imageFile.isFile) { "Missing page image" }

                val cropQuad = CropQuadCodec.decode(pageEntity.cropQuad)
                val recipe = PageVisualRecipeCodec.decode(pageEntity.visualRecipe)
                val cleanup = PageCleanupRecipeCodec.decode(pageEntity.cleanupRecipe)
                val textEdits = PageTextEditRecipeCodec.decode(pageEntity.textEditRecipe)
                val markup = PageMarkupRecipeCodec.decode(pageEntity.markupRecipe)
                val form = PageFormRecipeCodec.decode(pageEntity.formFillRecipe)
                val geometryEdited = !cropQuad.isFullFrame()
                val directJpeg =
                    quality == PdfQuality.ORIGINAL &&
                        !geometryEdited &&
                        recipe.isOriginal() &&
                        cleanup.isEmpty() &&
                        textEdits.isEmpty() &&
                        markup.isEmpty() &&
                        !form.hasFillContent()

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
                    var semanticGeometry = cleanedGeometry
                    val rasterRotation =
                        !textEdits.isEmpty() || !markup.isEmpty() || form.hasFillContent()
                    if (rasterRotation) {
                        val rotated = PageGeometryRenderer.rotateBitmap(
                            semanticGeometry,
                            pageEntity.rotationDegrees
                        )
                        if (rotated !== semanticGeometry) {
                            semanticGeometry.recycle()
                        }
                        semanticGeometry = rotated
                    }
                    if (!textEdits.isEmpty()) {
                        val edited = OcrTextEditRenderer.apply(
                            semanticGeometry,
                            textEdits
                        )
                        if (edited !== semanticGeometry) {
                            semanticGeometry.recycle()
                        }
                        semanticGeometry = edited
                    }
                    geometryBitmap = semanticGeometry
                    val enhanced = ImageEnhancementRenderer.apply(
                        semanticGeometry,
                        recipe
                    )
                    val filled = FormFillRenderer.apply(enhanced, form)
                    if (filled !== enhanced && enhanced !== semanticGeometry) {
                        enhanced.recycle()
                    }
                    visualBitmap = PageMarkupRenderer.apply(filled, markup)
                    if (visualBitmap !== filled && filled !== semanticGeometry) {
                        filled.recycle()
                    }
                    imageWidth = visualBitmap.width
                    imageHeight = visualBitmap.height
                }

                val (pdfWidth, pdfHeight) = pageSize(imageWidth, imageHeight)
                val page = PDPage(PDRectangle(pdfWidth, pdfHeight)).apply {
                    rotation = if (
                        textEdits.isEmpty() &&
                        markup.isEmpty() &&
                        !form.hasFillContent()
                    ) {
                        PageRotation.normalize(pageEntity.rotationDegrees)
                    } else {
                        0
                    }
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
                            if (
                                !textEdits.isEmpty() ||
                                !markup.isEmpty() ||
                                form.hasSearchableValues()
                            ) {
                                OcrLayoutCodec.decode(pageEntity.ocrLayout)
                                    ?: if (markup.hasRedactions()) {
                                        null
                                    } else {
                                        runCatching {
                                            requireNotNull(geometryBitmap).let { geometry ->
                                                FormFillOcr.apply(
                                                    ocr.recognizeDetailed(
                                                        geometry,
                                                        OcrScript.fromStored(pageEntity.ocrScript)
                                                    ),
                                                    form
                                                )
                                            }
                                        }.getOrNull()
                                    }
                            } else {
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
                            }
                        } else {
                            null
                        }

                        if (recognition != null) {
                            if (tagger != null) {
                                tagger.addPage(
                                    pageKey = pageIndex,
                                    page = page,
                                    recognition = recognition,
                                    stream = stream,
                                    font = font,
                                    pageWidth = pdfWidth,
                                    pageHeight = pdfHeight
                                )
                            } else {
                                recognition.words.forEach { word ->
                                    addInvisibleWord(
                                        stream = stream,
                                        font = font,
                                        word = word,
                                        sourceWidth = recognition.sourceWidth,
                                        sourceHeight = recognition.sourceHeight,
                                        pageWidth = pdfWidth,
                                        pageHeight = pdfHeight
                                    )
                                }
                            }
                        }
                    }
                } finally {
                    val visual = visualBitmap
                    val geometry = geometryBitmap
                    if (visual != null && visual !== geometry) visual.recycle()
                    geometry?.recycle()
                }
            }

            tagger?.finish()

            applyPublishing(
                document = document,
                font = font,
                pages = orderedPages,
                documentTitle = documentTitle,
                settings = publishingSettings,
                compliance = compliance
            )

            if (!password.isNullOrBlank()) {
                protect(document, password)
            }

            document.documentInformation.producer = "Scan"
            document.save(destination)
        }
    }

    fun publishExisting(
        source: File,
        destination: File,
        pages: List<PageEntity>,
        documentTitle: String,
        settings: PublishingSettings,
        password: String? = null
    ) {
        require(source.isFile) { "PDF source is unavailable" }
        require(pages.isNotEmpty()) { "Document has no pages" }
        destination.parentFile?.mkdirs()

        PDDocument.load(source).use { document ->
            require(document.numberOfPages == pages.size) {
                "PDF page count no longer matches the document"
            }
            val font = context.assets
                .open("com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf")
                .use { PDType0Font.load(document, it) }
            applyPublishing(
                document = document,
                font = font,
                pages = pages,
                documentTitle = documentTitle,
                settings = settings,
                compliance = ComplianceSettings()
            )
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

    private fun applyPublishing(
        document: PDDocument,
        font: PDType0Font,
        pages: List<PageEntity>,
        documentTitle: String,
        settings: PublishingSettings,
        compliance: ComplianceSettings
    ) {
        val normalized = settings.normalized()
        val info = document.documentInformation
        info.producer = "Scan"
        info.title = normalized.metadataTitle.ifBlank { documentTitle }
        if (normalized.metadataAuthor.isNotBlank()) {
            info.author = normalized.metadataAuthor
        }
        if (normalized.metadataSubject.isNotBlank()) {
            info.subject = normalized.metadataSubject
        }
        if (normalized.metadataKeywords.isNotBlank()) {
            info.keywords = normalized.metadataKeywords
        }

        applyPageLabels(document, pages, normalized)
        applyBookmarks(document, pages)

        if (!normalized.hasVisualDecorations()) return

        val count = minOf(document.numberOfPages, pages.size)
        for (index in 0 until count) {
            val page = document.getPage(index)
            val entity = pages[index]
            val metadata = PageAssemblyMetadataCodec.decode(
                entity.assemblyMetadata
            )
            PDPageContentStream(
                document,
                page,
                PDPageContentStream.AppendMode.APPEND,
                true,
                true
            ).use { stream ->
                drawPublishingOverlay(
                    stream = stream,
                    font = font,
                    page = page,
                    documentTitle = documentTitle,
                    pageIndex = index,
                    pageCount = count,
                    metadata = metadata,
                    settings = normalized,
                    compliance = compliance
                )
            }
        }
    }

    private fun applyPageLabels(
        document: PDDocument,
        pages: List<PageEntity>,
        settings: PublishingSettings
    ) {
        if (pages.isEmpty()) return
        val hasCustomLabels = pages.any {
            PageAssemblyMetadataCodec.decode(it.assemblyMetadata)
                .label.isNotBlank()
        }
        if (!hasCustomLabels && settings.pageNumberPosition == PageNumberPosition.NONE) {
            return
        }

        val labels = PDPageLabels(document)
        pages.forEachIndexed { index, page ->
            val metadata = PageAssemblyMetadataCodec.decode(
                page.assemblyMetadata
            )
            val range = PDPageLabelRange()
            if (metadata.label.isNotBlank()) {
                range.prefix = metadata.label
                range.style = null
            } else {
                range.style = when (settings.pageNumberStyle) {
                    PageNumberStyle.ROMAN_LOWER -> PDPageLabelRange.STYLE_ROMAN_LOWER
                    PageNumberStyle.ROMAN_UPPER -> PDPageLabelRange.STYLE_ROMAN_UPPER
                    PageNumberStyle.ARABIC -> PDPageLabelRange.STYLE_DECIMAL
                }
                range.start = (settings.pageNumberStart + index)
                    .coerceAtLeast(1)
            }
            labels.setLabelItem(index, range)
        }
        document.documentCatalog.pageLabels = labels
    }

    private fun applyBookmarks(
        document: PDDocument,
        pages: List<PageEntity>
    ) {
        val bookmarkData = pages.mapIndexedNotNull { index, page ->
            val metadata = PageAssemblyMetadataCodec.decode(
                page.assemblyMetadata
            )
            val title = metadata.bookmarkTitle.trim()
            title.takeIf { it.isNotBlank() }?.let {
                Triple(index, metadata.bookmarkLevel.coerceIn(0, 3), it)
            }
        }
        if (bookmarkData.isEmpty()) return

        val outline = PDDocumentOutline()
        document.documentCatalog.documentOutline = outline
        val lastAtLevel = arrayOfNulls<PDOutlineItem>(4)

        bookmarkData.forEach { (pageIndex, requestedLevel, title) ->
            val item = PDOutlineItem().apply {
                this.title = title
                setDestination(document.getPage(pageIndex))
            }
            var level = requestedLevel
            while (level > 0 && lastAtLevel[level - 1] == null) {
                level--
            }
            val parent = if (level == 0) null else lastAtLevel[level - 1]
            if (parent == null) {
                outline.addLast(item)
            } else {
                parent.addLast(item)
                parent.openNode()
            }
            lastAtLevel[level] = item
            for (deeper in level + 1 until lastAtLevel.size) {
                lastAtLevel[deeper] = null
            }
        }
    }

    private fun drawPublishingOverlay(
        stream: PDPageContentStream,
        font: PDType0Font,
        page: PDPage,
        documentTitle: String,
        pageIndex: Int,
        pageCount: Int,
        metadata: PageAssemblyMetadata,
        settings: PublishingSettings,
        compliance: ComplianceSettings
    ) {
        val width = page.mediaBox.width
        val height = page.mediaBox.height
        val marginX = 24f
        val headerY = height - 20f
        val footerY = 14f
        val fontSize = 9f

        val slots = linkedMapOf(
            PageNumberPosition.HEADER_LEFT to PublishingText.resolve(
                settings.headerLeft,
                documentTitle,
                pageIndex,
                pageCount,
                metadata,
                settings
            ),
            PageNumberPosition.HEADER_CENTER to PublishingText.resolve(
                settings.headerCenter,
                documentTitle,
                pageIndex,
                pageCount,
                metadata,
                settings
            ),
            PageNumberPosition.HEADER_RIGHT to PublishingText.resolve(
                settings.headerRight,
                documentTitle,
                pageIndex,
                pageCount,
                metadata,
                settings
            ),
            PageNumberPosition.FOOTER_LEFT to PublishingText.resolve(
                settings.footerLeft,
                documentTitle,
                pageIndex,
                pageCount,
                metadata,
                settings
            ),
            PageNumberPosition.FOOTER_CENTER to PublishingText.resolve(
                settings.footerCenter,
                documentTitle,
                pageIndex,
                pageCount,
                metadata,
                settings
            ),
            PageNumberPosition.FOOTER_RIGHT to PublishingText.resolve(
                settings.footerRight,
                documentTitle,
                pageIndex,
                pageCount,
                metadata,
                settings
            )
        )

        if (settings.pageNumberPosition != PageNumberPosition.NONE) {
            val number = PublishingText.pageNumber(
                pageIndex,
                pageCount,
                settings
            )
            val current = slots[settings.pageNumberPosition].orEmpty()
            slots[settings.pageNumberPosition] = listOf(current, number)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        }

        drawSlot(
            stream,
            font,
            slots[PageNumberPosition.HEADER_LEFT].orEmpty(),
            marginX,
            headerY,
            width - 2 * marginX,
            fontSize,
            TextAlign.LEFT
        )
        drawSlot(
            stream,
            font,
            slots[PageNumberPosition.HEADER_CENTER].orEmpty(),
            marginX,
            headerY,
            width - 2 * marginX,
            fontSize,
            TextAlign.CENTER
        )
        drawSlot(
            stream,
            font,
            slots[PageNumberPosition.HEADER_RIGHT].orEmpty(),
            marginX,
            headerY,
            width - 2 * marginX,
            fontSize,
            TextAlign.RIGHT
        )
        drawSlot(
            stream,
            font,
            slots[PageNumberPosition.FOOTER_LEFT].orEmpty(),
            marginX,
            footerY,
            width - 2 * marginX,
            fontSize,
            TextAlign.LEFT
        )
        drawSlot(
            stream,
            font,
            slots[PageNumberPosition.FOOTER_CENTER].orEmpty(),
            marginX,
            footerY,
            width - 2 * marginX,
            fontSize,
            TextAlign.CENTER
        )
        drawSlot(
            stream,
            font,
            slots[PageNumberPosition.FOOTER_RIGHT].orEmpty(),
            marginX,
            footerY,
            width - 2 * marginX,
            fontSize,
            TextAlign.RIGHT
        )

        if (settings.watermarkText.isNotBlank()) {
            val text = encodableText(font, settings.watermarkText)
            if (text.isNotBlank()) {
                val size = (minOf(width, height) * 0.075f)
                    .coerceIn(24f, 72f)
                val naturalWidth = runCatching {
                    font.getStringWidth(text) / 1000f * size
                }.getOrDefault(width * 0.5f)
                stream.saveGraphicsState()
                if (
                    compliance.pdfStandard ==
                    PdfStandard.PDF_A_1B
                ) {
                    val shade = (
                        0.92f -
                            settings.watermarkOpacity * 0.35f
                        ).coerceIn(0.55f, 0.92f)
                    stream.setNonStrokingColor(
                        shade,
                        shade,
                        shade
                    )
                } else {
                    val alpha = PDExtendedGraphicsState().apply {
                        nonStrokingAlphaConstant =
                            settings.watermarkOpacity
                    }
                    stream.setGraphicsStateParameters(alpha)
                    stream.setNonStrokingColor(
                        0.35f,
                        0.35f,
                        0.38f
                    )
                }
                stream.beginText()
                stream.setFont(font, size)
                stream.setTextMatrix(
                    Matrix.getRotateInstance(
                        Math.toRadians(settings.watermarkAngle.toDouble()),
                        width / 2f,
                        height / 2f
                    )
                )
                stream.newLineAtOffset(-naturalWidth / 2f, -size / 3f)
                stream.showText(text)
                stream.endText()
                stream.restoreGraphicsState()
            }
        }
    }

    private enum class TextAlign {
        LEFT,
        CENTER,
        RIGHT
    }

    private fun drawSlot(
        stream: PDPageContentStream,
        font: PDType0Font,
        value: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        fontSize: Float,
        align: TextAlign
    ) {
        val text = encodableText(font, value.trim())
        if (text.isBlank()) return
        val naturalWidth = runCatching {
            font.getStringWidth(text) / 1000f * fontSize
        }.getOrDefault(0f)
        val textX = when (align) {
            TextAlign.LEFT -> x
            TextAlign.CENTER -> x + (maxWidth - naturalWidth) / 2f
            TextAlign.RIGHT -> x + maxWidth - naturalWidth
        }.coerceAtLeast(x)

        stream.saveGraphicsState()
        stream.setNonStrokingColor(0.18f, 0.18f, 0.20f)
        stream.beginText()
        stream.setFont(font, fontSize)
        stream.setTextMatrix(Matrix.getTranslateInstance(textX, y))
        stream.showText(text)
        stream.endText()
        stream.restoreGraphicsState()
    }

    private fun encodableText(
        font: PDType0Font,
        value: String
    ): String = buildString {
        value.forEach { ch ->
            val text = ch.toString()
            if (runCatching { font.encode(text) }.isSuccess) {
                append(ch)
            }
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
