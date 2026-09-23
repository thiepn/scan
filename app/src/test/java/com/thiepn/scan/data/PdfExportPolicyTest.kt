package com.thiepn.scan.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfExportPolicyTest {
    private val page = PageEntity(
        id = "page",
        documentId = "doc",
        position = 0,
        imagePath = "/tmp/page.jpg",
        width = 1000,
        height = 1400
    )

    @Test
    fun unchangedPageAndRotationOnlyCanUseNativePdfPath() {
        assertFalse(PdfExportPolicy.requiresRasterization(page))
        assertFalse(
            PdfExportPolicy.requiresRasterization(
                page.copy(rotationDegrees = 90)
            )
        )
    }

    @Test
    fun secureRedactionAlwaysForcesRasterizedExport() {
        val redaction = PageMarkupRecipe(
            items = listOf(
                PageMarkupItem(
                    id = "redact",
                    kind = PageMarkupKind.REDACTION,
                    left = 0.1f,
                    top = 0.1f,
                    right = 0.5f,
                    bottom = 0.2f
                )
            )
        )
        assertTrue(
            PdfExportPolicy.requiresRasterization(
                page.copy(
                    markupRecipe = PageMarkupRecipeCodec.encode(redaction)
                )
            )
        )
    }

    @Test
    fun ordinaryAnnotationAlsoForcesRasterizedExport() {
        val annotation = PageMarkupRecipe(
            items = listOf(
                PageMarkupItem(
                    id = "note",
                    kind = PageMarkupKind.TEXT,
                    left = 0.1f,
                    top = 0.1f,
                    right = 0.4f,
                    bottom = 0.2f,
                    text = "Reviewed"
                )
            )
        )
        assertTrue(
            PdfExportPolicy.requiresRasterization(
                page.copy(
                    markupRecipe = PageMarkupRecipeCodec.encode(annotation)
                )
            )
        )
    }

    @Test
    fun cropCleanupAndOcrTextReplacementForceRasterization() {
        assertTrue(
            PdfExportPolicy.requiresRasterization(
                page.copy(
                    cropQuad = "0.05;0.05,0.95;0.05,0.95;0.95,0.05;0.95"
                )
            )
        )
        assertTrue(
            PdfExportPolicy.requiresRasterization(
                page.copy(
                    cleanupRecipe = PageCleanupRecipeCodec.encode(
                        PageCleanupRecipe(
                            strokes = listOf(
                                CleanupStroke(
                                    kind = CleanupKind.MANUAL,
                                    points = listOf(NormalizedPoint(0.5f, 0.5f)),
                                    radius = 0.02f
                                )
                            )
                        )
                    )
                )
            )
        )
        val base = OcrEditableRegion(
            id = "W:0:0:0",
            target = OcrTextEditTarget.WORD,
            blockIndex = 0,
            lineIndex = 0,
            wordIndex = 0,
            text = "old",
            left = 0.1f,
            top = 0.1f,
            right = 0.2f,
            bottom = 0.15f,
            angleDegrees = 0f
        )
        assertTrue(
            PdfExportPolicy.requiresRasterization(
                page.copy(
                    textEditRecipe = PageTextEditRecipeCodec.encode(
                        PageTextEditRecipe(
                            edits = listOf(
                                OcrTextEditEngine.createEdit(base, "new")
                            )
                        )
                    )
                )
            )
        )
    }
}
