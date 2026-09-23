package com.thiepn.scan.data

data class ScanModeProfile(
    val mode: ScanMode,
    val description: String,
    val captureHint: String,
    val pageLimit: Int?,
    val defaultPreset: ScanPreset,
    val defaultDocumentType: DocumentType,
    val defaultPdfQuality: PdfQuality,
    val requiresTwoSidedCapture: Boolean = false,
    val expectedAspectRatioRange: ClosedFloatingPointRange<Float>? = null
)

object ScanModeProfiles {
    private val profiles = mapOf(
        ScanMode.DOCUMENT to ScanModeProfile(
            mode = ScanMode.DOCUMENT,
            description = "General multipage documents",
            captureHint = "Keep the full page visible and avoid shadows.",
            pageLimit = null,
            defaultPreset = ScanPreset.AUTO,
            defaultDocumentType = DocumentType.UNSPECIFIED,
            defaultPdfQuality = PdfQuality.ORIGINAL
        ),
        ScanMode.RECEIPT to ScanModeProfile(
            mode = ScanMode.RECEIPT,
            description = "Receipts and narrow purchase slips",
            captureHint = "Capture the whole receipt from top to bottom on a contrasting surface.",
            pageLimit = 1,
            defaultPreset = ScanPreset.RECEIPT,
            defaultDocumentType = DocumentType.RECEIPT,
            defaultPdfQuality = PdfQuality.BALANCED,
            expectedAspectRatioRange = 1.35f..8.0f
        ),
        ScanMode.ID_CARD to ScanModeProfile(
            mode = ScanMode.ID_CARD,
            description = "Front and back of an identity card",
            captureHint = "Scan the front first, then the back. Keep all four card edges visible.",
            pageLimit = 1,
            defaultPreset = ScanPreset.COLOR,
            defaultDocumentType = DocumentType.ID,
            defaultPdfQuality = PdfQuality.HIGH,
            requiresTwoSidedCapture = true,
            expectedAspectRatioRange = 1.35f..1.85f
        ),
        ScanMode.BUSINESS_CARD to ScanModeProfile(
            mode = ScanMode.BUSINESS_CARD,
            description = "Single business card with contact extraction",
            captureHint = "Fill the frame with the card and avoid glare over contact details.",
            pageLimit = 1,
            defaultPreset = ScanPreset.COLOR,
            defaultDocumentType = DocumentType.BUSINESS_CARD,
            defaultPdfQuality = PdfQuality.HIGH,
            expectedAspectRatioRange = 1.35f..2.15f
        ),
        ScanMode.WHITEBOARD to ScanModeProfile(
            mode = ScanMode.WHITEBOARD,
            description = "Boards, diagrams, and meeting notes",
            captureHint = "Capture the entire board straight-on and minimize reflections.",
            pageLimit = 1,
            defaultPreset = ScanPreset.WHITEBOARD,
            defaultDocumentType = DocumentType.WHITEBOARD,
            defaultPdfQuality = PdfQuality.BALANCED
        ),
        ScanMode.FORM to ScanModeProfile(
            mode = ScanMode.FORM,
            description = "Forms and fillable paperwork",
            captureHint = "Keep boxes, labels, signatures, and page margins fully visible.",
            pageLimit = 20,
            defaultPreset = ScanPreset.CLEAN,
            defaultDocumentType = DocumentType.FORM,
            defaultPdfQuality = PdfQuality.ORIGINAL
        ),
        ScanMode.PHOTO to ScanModeProfile(
            mode = ScanMode.PHOTO,
            description = "Photos where visual fidelity matters more than OCR",
            captureHint = "Avoid reflections and keep the original photo colors intact.",
            pageLimit = 1,
            defaultPreset = ScanPreset.ORIGINAL,
            defaultDocumentType = DocumentType.UNSPECIFIED,
            defaultPdfQuality = PdfQuality.HIGH
        ),
        ScanMode.NOTES to ScanModeProfile(
            mode = ScanMode.NOTES,
            description = "Handouts, handwritten notes, and study pages",
            captureHint = "Keep handwriting sharp and evenly lit.",
            pageLimit = 20,
            defaultPreset = ScanPreset.NOTES,
            defaultDocumentType = DocumentType.NOTES,
            defaultPdfQuality = PdfQuality.BALANCED
        ),
        ScanMode.CERTIFICATE to ScanModeProfile(
            mode = ScanMode.CERTIFICATE,
            description = "Certificates and official single-page documents",
            captureHint = "Preserve borders, seals, signatures, and the complete page.",
            pageLimit = 1,
            defaultPreset = ScanPreset.COLOR,
            defaultDocumentType = DocumentType.CERTIFICATE,
            defaultPdfQuality = PdfQuality.HIGH,
            expectedAspectRatioRange = 1.15f..1.65f
        )
    )

    fun forMode(mode: ScanMode): ScanModeProfile =
        requireNotNull(profiles[mode])

    fun aspectRatioWarning(
        mode: ScanMode,
        width: Int,
        height: Int
    ): String? {
        if (width <= 0 || height <= 0) return null
        val expected = forMode(mode).expectedAspectRatioRange ?: return null
        val ratio = maxOf(width, height).toFloat() / minOf(width, height).toFloat()
        return if (ratio in expected) {
            null
        } else {
            when (mode) {
                ScanMode.ID_CARD -> "Card proportions look unusual; verify that all four edges were captured."
                ScanMode.BUSINESS_CARD -> "Business-card proportions look unusual; verify the crop."
                ScanMode.RECEIPT -> "Receipt proportions look unusual; verify the full receipt was captured."
                ScanMode.CERTIFICATE -> "Certificate proportions look unusual; verify the page crop."
                else -> null
            }
        }
    }
}
