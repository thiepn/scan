package com.thiepn.scan.data

import android.content.Context
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDNumberTreeNode
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDParentTreeValue
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot
import com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDOutputIntent
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.max

object PdfStandardsSupport {
    fun prepareDocument(
        context: Context,
        document: PDDocument,
        documentTitle: String,
        publishing: PublishingSettings,
        compliance: ComplianceSettings
    ) {
        val settings = compliance.normalized()
        if (settings.pdfStandard == PdfStandard.PDF_A_2B) {
            document.version = 1.7f
        }

        val catalog = document.documentCatalog
        if (settings.accessibilityMode == AccessibilityMode.TAGGED_OCR) {
            catalog.language = settings.documentLanguage
        }

        if (settings.isPdfA()) {
            context.assets.open("color/sRGB.icc").use { profile ->
                val intent = PDOutputIntent(document, profile).apply {
                    info = "sRGB IEC61966-2.1"
                    outputCondition = "sRGB IEC61966-2.1"
                    outputConditionIdentifier = "sRGB IEC61966-2.1"
                    registryName = "http://www.color.org"
                }
                catalog.addOutputIntent(intent)
            }
        }

        val xmp = buildXmp(
            documentTitle = publishing.metadataTitle.ifBlank {
                documentTitle
            },
            author = publishing.metadataAuthor,
            subject = publishing.metadataSubject,
            keywords = publishing.metadataKeywords,
            standard = settings.pdfStandard,
            language = settings.documentLanguage
        )
        catalog.metadata = PDMetadata(document).apply {
            importXMPMetadata(xmp.toByteArray(Charsets.UTF_8))
        }
    }

    private fun buildXmp(
        documentTitle: String,
        author: String,
        subject: String,
        keywords: String,
        standard: PdfStandard,
        language: String
    ): String {
        val pdfa = when (standard) {
            PdfStandard.PDF_A_1B ->
                "<pdfaid:part>1</pdfaid:part><pdfaid:conformance>B</pdfaid:conformance>"
            PdfStandard.PDF_A_2B ->
                "<pdfaid:part>2</pdfaid:part><pdfaid:conformance>B</pdfaid:conformance>"
            PdfStandard.STANDARD -> ""
        }
        val pdfaNamespace = if (standard == PdfStandard.STANDARD) {
            ""
        } else {
            " xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\""
        }
        val title = xml(documentTitle)
        val creator = xml(author)
        val description = xml(subject)
        val safeKeywords = xml(keywords)
        val safeLanguage = xml(language)

        return buildString {
            append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF ")
            append("xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
            append("<rdf:Description rdf:about=\"\" ")
            append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
            append("xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" ")
            append("xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\"")
            append(pdfaNamespace)
            append(">")
            if (title.isNotBlank()) {
                append("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">")
                append(title)
                append("</rdf:li></rdf:Alt></dc:title>")
            }
            if (creator.isNotBlank()) {
                append("<dc:creator><rdf:Seq><rdf:li>")
                append(creator)
                append("</rdf:li></rdf:Seq></dc:creator>")
            }
            if (description.isNotBlank()) {
                append("<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">")
                append(description)
                append("</rdf:li></rdf:Alt></dc:description>")
            }
            append("<dc:format>application/pdf</dc:format>")
            append("<dc:language><rdf:Bag><rdf:li>")
            append(safeLanguage)
            append("</rdf:li></rdf:Bag></dc:language>")
            append("<xmp:CreatorTool>Scan</xmp:CreatorTool>")
            append("<pdf:Producer>Scan</pdf:Producer>")
            if (safeKeywords.isNotBlank()) {
                append("<pdf:Keywords>")
                append(safeKeywords)
                append("</pdf:Keywords>")
            }
            append(pdfa)
            append("</rdf:Description></rdf:RDF></x:xmpmeta>")
            append("<?xpacket end=\"w\"?>")
        }
    }

    private fun xml(value: String): String {
        val clean = buildString {
            value.forEach { ch ->
                val code = ch.code
                if (
                    code == 0x9 ||
                    code == 0xA ||
                    code == 0xD ||
                    code in 0x20..0xD7FF ||
                    code in 0xE000..0xFFFD
                ) {
                    append(ch)
                }
            }
        }
        return clean
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}

class TaggedOcrBuilder(
    private val document: PDDocument,
    private val language: String
) {
    private val root = PDStructureTreeRoot()
    private val documentElement = PDStructureElement("Document", root)
    private val parentTree = PDNumberTreeNode(PDParentTreeValue::class.java)
    private val parentValues = linkedMapOf<Int, PDParentTreeValue>()

    init {
        document.documentCatalog.markInfo = PDMarkInfo().apply {
            setMarked(true)
        }
        document.documentCatalog.structureTreeRoot = root
        document.documentCatalog.language = language
        root.appendKid(documentElement)
        root.parentTree = parentTree
    }

    fun addPage(
        pageKey: Int,
        page: PDPage,
        recognition: OcrPageResult,
        stream: PDPageContentStream,
        font: PDType0Font,
        pageWidth: Float,
        pageHeight: Float
    ) {
        page.structParents = pageKey
        val parents = COSArray()
        var mcid = 0

        recognition.lines
            .sortedBy { it.readingOrder }
            .forEach { line ->
                val text = encodableText(font, line.text.trim())
                if (text.isBlank()) return@forEach

                val paragraph = PDStructureElement(
                    "P",
                    documentElement
                ).apply {
                    setPage(page)
                    setLanguage(language)
                }
                documentElement.appendKid(paragraph)

                val reference = PDMarkedContentReference().apply {
                    setPage(page)
                    setMCID(mcid)
                }
                paragraph.appendKid(reference)
                parents.add(paragraph.cosObject)

                val properties = COSDictionary().apply {
                    setInt(COSName.MCID, mcid)
                }
                stream.beginMarkedContent(
                    COSName.getPDFName("P"),
                    PDPropertyList.create(properties)
                )
                addInvisibleLine(
                    stream = stream,
                    font = font,
                    line = line,
                    text = text,
                    sourceWidth = recognition.sourceWidth,
                    sourceHeight = recognition.sourceHeight,
                    pageWidth = pageWidth,
                    pageHeight = pageHeight
                )
                stream.endMarkedContent()
                mcid++
            }

        parentValues[pageKey] = PDParentTreeValue(parents)
    }

    fun finish() {
        parentTree.numbers = parentValues
        root.parentTreeNextKey =
            (parentValues.keys.maxOrNull() ?: -1) + 1
    }

    private fun addInvisibleLine(
        stream: PDPageContentStream,
        font: PDType0Font,
        line: OcrTextLine,
        text: String,
        sourceWidth: Int,
        sourceHeight: Int,
        pageWidth: Float,
        pageHeight: Float
    ) {
        if (
            sourceWidth <= 0 ||
            sourceHeight <= 0 ||
            line.right <= line.left ||
            line.bottom <= line.top
        ) return

        val x = line.left.toFloat() / sourceWidth * pageWidth
        val y = pageHeight -
            (line.bottom.toFloat() / sourceHeight * pageHeight)
        val desiredWidth =
            (line.right - line.left).toFloat() /
                sourceWidth * pageWidth
        val desiredHeight =
            (line.bottom - line.top).toFloat() /
                sourceHeight * pageHeight
        val fontSize = max(1f, desiredHeight * 0.86f)
        val naturalWidth = runCatching {
            font.getStringWidth(text) / 1000f * fontSize
        }.getOrDefault(desiredWidth).coerceAtLeast(0.1f)
        val horizontalScale =
            (desiredWidth / naturalWidth * 100f)
                .coerceIn(35f, 260f)

        runCatching {
            stream.beginText()
            stream.setRenderingMode(RenderingMode.NEITHER)
            stream.setFont(font, fontSize)
            stream.setHorizontalScaling(horizontalScale)
            stream.setTextMatrix(
                Matrix.getTranslateInstance(x, y)
            )
            stream.showText(text)
            stream.endText()
        }.onFailure {
            runCatching { stream.endText() }
        }
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
}

object PdfComplianceValidator {
    fun validate(
        file: File,
        settings: ComplianceSettings
    ): ComplianceReport {
        val normalized = settings.normalized()
        val issues = mutableListOf<ComplianceIssue>()

        if (!file.isFile || file.length() == 0L) {
            return ComplianceReport(
                normalized.pdfStandard,
                normalized.accessibilityMode,
                listOf(
                    ComplianceIssue(
                        "FILE_MISSING",
                        ComplianceSeverity.ERROR,
                        "Exported PDF is unavailable."
                    )
                )
            )
        }

        runCatching {
            PDDocument.load(file).use { document ->
                val catalog = document.documentCatalog

                if (normalized.isPdfA()) {
                    if (document.isEncrypted) {
                        issues += ComplianceIssue(
                            "PDFA_ENCRYPTED",
                            ComplianceSeverity.ERROR,
                            "PDF/A documents must not be encrypted."
                        )
                    }

                    if (
                        normalized.pdfStandard ==
                        PdfStandard.PDF_A_2B &&
                        document.version < 1.7f
                    ) {
                        issues += ComplianceIssue(
                            "PDFA_VERSION",
                            ComplianceSeverity.ERROR,
                            "PDF/A-2b export requires PDF 1.7."
                        )
                    }
                    if (
                        normalized.pdfStandard ==
                        PdfStandard.PDF_A_1B &&
                        document.version > 1.4f
                    ) {
                        issues += ComplianceIssue(
                            "PDFA_VERSION",
                            ComplianceSeverity.ERROR,
                            "PDF/A-1b requires PDF 1.4."
                        )
                    }

                    if (catalog.outputIntents.isEmpty()) {
                        issues += ComplianceIssue(
                            "PDFA_OUTPUT_INTENT",
                            ComplianceSeverity.ERROR,
                            "PDF/A export is missing an output intent."
                        )
                    }

                    val xmp = catalog.metadata
                        ?.exportXMPMetadata()
                        ?.use {
                            it.readBytes().toString(Charsets.UTF_8)
                        }
                        .orEmpty()
                    val expectedPart =
                        if (
                            normalized.pdfStandard ==
                            PdfStandard.PDF_A_1B
                        ) {
                            "<pdfaid:part>1</pdfaid:part>"
                        } else {
                            "<pdfaid:part>2</pdfaid:part>"
                        }
                    if (
                        expectedPart !in xmp ||
                        "<pdfaid:conformance>B</pdfaid:conformance>" !in xmp
                    ) {
                        issues += ComplianceIssue(
                            "PDFA_XMP",
                            ComplianceSeverity.ERROR,
                            "PDF/A identification metadata is missing or inconsistent."
                        )
                    }

                    document.pages.forEachIndexed { index, page ->
                        val resources = page.resources
                            ?: return@forEachIndexed
                        resources.fontNames.forEach { name ->
                            val font = runCatching {
                                resources.getFont(name)
                            }.getOrNull() ?: return@forEach
                            if (!font.isEmbedded) {
                                issues += ComplianceIssue(
                                    "FONT_NOT_EMBEDDED",
                                    ComplianceSeverity.ERROR,
                                    "Page ${index + 1} uses a non-embedded font."
                                )
                            }
                        }
                        if (
                            normalized.pdfStandard ==
                            PdfStandard.PDF_A_1B
                        ) {
                            resources.extGStateNames.forEach { name ->
                                val state = runCatching {
                                    resources.getExtGState(name)
                                }.getOrNull() ?: return@forEach
                                val strokeAlpha =
                                    state.strokingAlphaConstant
                                        ?: 1f
                                val fillAlpha =
                                    state.nonStrokingAlphaConstant
                                        ?: 1f
                                if (
                                    strokeAlpha < 0.999f ||
                                    fillAlpha < 0.999f ||
                                    state.softMask != null
                                ) {
                                    issues += ComplianceIssue(
                                        "PDFA1_TRANSPARENCY",
                                        ComplianceSeverity.ERROR,
                                        "Page ${index + 1} uses transparency, which PDF/A-1b does not permit."
                                    )
                                }
                            }
                        }
                    }
                }

                if (
                    normalized.accessibilityMode ==
                    AccessibilityMode.TAGGED_OCR
                ) {
                    if (catalog.language.isNullOrBlank()) {
                        issues += ComplianceIssue(
                            "LANGUAGE_MISSING",
                            ComplianceSeverity.ERROR,
                            "Tagged PDF is missing a document language."
                        )
                    }
                    if (catalog.markInfo?.isMarked != true) {
                        issues += ComplianceIssue(
                            "MARK_INFO",
                            ComplianceSeverity.ERROR,
                            "Tagged PDF is not marked as structured content."
                        )
                    }
                    if (catalog.structureTreeRoot == null) {
                        issues += ComplianceIssue(
                            "STRUCTURE_TREE",
                            ComplianceSeverity.ERROR,
                            "Tagged PDF is missing a structure tree."
                        )
                    }
                    document.pages.forEachIndexed { index, page ->
                        if (page.structParents < 0) {
                            issues += ComplianceIssue(
                                "STRUCT_PARENTS",
                                ComplianceSeverity.ERROR,
                                "Page ${index + 1} is missing structure-parent metadata."
                            )
                        }
                    }
                }

                if (issues.isEmpty()) {
                    issues += ComplianceIssue(
                        "VALIDATION_OK",
                        ComplianceSeverity.INFO,
                        "Configured PDF standards checks passed."
                    )
                }
            }
        }.onFailure { error ->
            issues += ComplianceIssue(
                "VALIDATION_EXCEPTION",
                ComplianceSeverity.ERROR,
                error.message ?: "PDF validation failed."
            )
        }

        return ComplianceReport(
            normalized.pdfStandard,
            normalized.accessibilityMode,
            issues
        )
    }
}
