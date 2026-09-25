package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowAutomationTest {
    private fun snapshot(
        title: String = "Scan",
        text: String = "ACME Total 42.00",
        folderId: String? = null,
        fields: List<DocumentFieldEntity> = listOf(
            DocumentFieldEntity(
                documentId = "d1",
                fieldKey = "merchant",
                label = "Merchant",
                value = "ACME",
                confidence = 0.9f
            )
        )
    ) = AutomationDocumentSnapshot(
        document = DocumentEntity(
            id = "d1",
            title = title,
            createdAt = 1L,
            updatedAt = 1L,
            pdfPath = null,
            pageCount = 2,
            ocrText = text,
            folderId = folderId,
            documentType = DocumentType.RECEIPT.name,
            scanMode = ScanMode.RECEIPT.name
        ),
        fields = fields
    )

    @Test
    fun conditionCodecRoundTripsExtractionRule() {
        val original = AutomationCondition(
            scanMode = ScanMode.RECEIPT,
            documentType = DocumentType.RECEIPT,
            ocrContains = "total",
            fieldKey = "merchant",
            fieldContains = "acme",
            minPages = 1,
            maxPages = 3,
            needsReview = false,
            onlyUnfiled = true
        )

        assertEquals(
            original,
            AutomationConditionCodec.decode(AutomationConditionCodec.encode(original))
        )
    }

    @Test
    fun presetCodecRoundTripsPolicyAndFilingActions() {
        val original = DocumentProcessingPreset(
            renameTemplate = "{date} {field:merchant}",
            folderId = "receipts",
            tagIds = setOf("tax", "2026"),
            documentType = DocumentType.RECEIPT,
            needsReview = false,
            favorite = true,
            archive = false,
            complianceSettings = ComplianceSettings(
                pdfStandard = PdfStandard.PDF_A_2B
            ),
            securitySettings = DocumentSecuritySettings(
                vaultEnabled = true
            ),
            destinationId = "archive"
        )

        assertEquals(
            original,
            DocumentProcessingPresetCodec.decode(DocumentProcessingPresetCodec.encode(original))
        )
    }

    @Test
    fun presetCodecPreservesExplicitDefaultPolicies() {
        val original = DocumentProcessingPreset(
            complianceSettings = ComplianceSettings(),
            securitySettings = DocumentSecuritySettings()
        )

        val decoded = DocumentProcessingPresetCodec.decode(
            DocumentProcessingPresetCodec.encode(original)
        )

        assertEquals(ComplianceSettings(), decoded.complianceSettings)
        assertEquals(DocumentSecuritySettings(), decoded.securitySettings)
    }

    @Test
    fun matcherHonorsPageBounds() {
        assertTrue(
            WorkflowAutomationMatcher.matches(
                AutomationCondition(minPages = 2, maxPages = 2),
                snapshot()
            )
        )
        assertFalse(
            WorkflowAutomationMatcher.matches(
                AutomationCondition(minPages = 3),
                snapshot()
            )
        )
    }

    @Test
    fun matcherUsesOcrFieldsAndOrganizationState() {
        val condition = AutomationCondition(
            scanMode = ScanMode.RECEIPT,
            documentType = DocumentType.RECEIPT,
            ocrContains = "42.00",
            fieldKey = "merchant",
            fieldContains = "ACME",
            onlyUnfiled = true
        )

        assertTrue(WorkflowAutomationMatcher.matches(condition, snapshot()))
        assertFalse(
            WorkflowAutomationMatcher.matches(
                condition,
                snapshot(folderId = "already-filed")
            )
        )
    }

    @Test
    fun corruptedConditionFailsClosed() {
        val corrupted = AutomationConditionCodec.encode(
            AutomationCondition(ocrContains = "invoice")
        ).replaceAfter("ocr=", "%%%not-base64%%%")

        assertTrue(
            runCatching {
                AutomationConditionCodec.decode(corrupted)
            }.isFailure
        )
    }

    @Test
    fun corruptedSecurityPolicyFailsClosed() {
        val encoded = DocumentProcessingPresetCodec.encode(
            DocumentProcessingPreset(
                securitySettings = DocumentSecuritySettings(
                    vaultEnabled = true
                )
            )
        )
        val corrupted = encoded.lineSequence().joinToString("\n") { line ->
            if (line.startsWith("security=")) {
                "security=Z2FyYmFnZQ"
            } else {
                line
            }
        }

        assertTrue(
            runCatching {
                DocumentProcessingPresetCodec.decode(corrupted)
            }.isFailure
        )
    }

    @Test
    fun unknownConditionVersionIsRejected() {
        val encoded = AutomationConditionCodec.encode(AutomationCondition())
            .replaceFirst("v=MQ", "v=Mg")

        assertTrue(
            runCatching {
                AutomationConditionCodec.decode(encoded)
            }.isFailure
        )
    }

    @Test
    fun nameTemplateExpandsFieldAndDocumentTokens() {
        val rendered = WorkflowNameTemplate.render(
            "{type} · {field:merchant} · {title}",
            snapshot(title = "Original"),
            now = 1_790_000_000_000L
        )

        assertTrue(rendered.startsWith("Receipt · ACME · Original"))
    }
}
