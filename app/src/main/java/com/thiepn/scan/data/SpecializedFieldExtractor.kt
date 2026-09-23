package com.thiepn.scan.data

data class ExtractedDocumentField(
    val key: String,
    val label: String,
    val value: String,
    val confidence: Float
)

object SpecializedFieldExtractor {
    private val emailRegex = Regex(
        """[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}""",
        RegexOption.IGNORE_CASE
    )
    private val phoneRegex = Regex(
        """(?:\+?\d[\d .()/-]{6,}\d)"""
    )
    private val websiteRegex = Regex(
        """(?:https?://|www\.)[^\s]+""",
        RegexOption.IGNORE_CASE
    )
    private val dateRegex = Regex(
        """\b(?:\d{1,2}[./-]\d{1,2}[./-]\d{2,4}|\d{4}[./-]\d{1,2}[./-]\d{1,2})\b"""
    )
    private val moneyRegex = Regex(
        """(?:EUR|USD|GBP|€|\$|£)?\s*\d{1,6}(?:[.,]\d{2})\s*(?:EUR|USD|GBP|€|\$|£)?""",
        RegexOption.IGNORE_CASE
    )

    fun extract(
        mode: ScanMode,
        title: String,
        ocrText: String
    ): List<ExtractedDocumentField> {
        val text = ocrText.trim()
        if (text.isBlank()) return emptyList()

        return when (mode) {
            ScanMode.RECEIPT -> extractReceipt(text)
            ScanMode.ID_CARD -> extractId(text)
            ScanMode.BUSINESS_CARD -> extractBusinessCard(text)
            ScanMode.FORM -> extractForm(text)
            ScanMode.CERTIFICATE -> extractCertificate(text)
            ScanMode.DOCUMENT,
            ScanMode.WHITEBOARD,
            ScanMode.PHOTO,
            ScanMode.NOTES -> emptyList()
        }
    }

    private fun extractReceipt(text: String): List<ExtractedDocumentField> {
        val lines = meaningfulLines(text)
        val fields = mutableListOf<ExtractedDocumentField>()

        lines.firstOrNull {
            it.length in 2..60 &&
                !moneyRegex.containsMatchIn(it) &&
                !it.lowercase().contains("receipt")
        }?.let {
            fields += field("merchant", "Merchant", it, 0.62f)
        }

        val totalLine = lines.lastOrNull { line ->
            val lower = line.lowercase()
            ("total" in lower || "amount due" in lower || "sum" in lower) &&
                moneyRegex.containsMatchIn(line)
        } ?: lines.lastOrNull { moneyRegex.containsMatchIn(it) }

        totalLine?.let { line ->
            moneyRegex.findAll(line).lastOrNull()?.value?.trim()?.let {
                fields += field("total", "Total", it, 0.88f)
            }
        }

        lines.firstNotNullOfOrNull { dateRegex.find(it)?.value }?.let {
            fields += field("date", "Date", it, 0.72f)
        }

        lines.firstOrNull { "vat" in it.lowercase() || "tax" in it.lowercase() }
            ?.let { line ->
                moneyRegex.findAll(line).lastOrNull()?.value?.trim()?.let {
                    fields += field("tax", "Tax / VAT", it, 0.72f)
                }
            }

        return fields.distinctBy { it.key }
    }

    private fun extractBusinessCard(text: String): List<ExtractedDocumentField> {
        val lines = meaningfulLines(text)
        val fields = mutableListOf<ExtractedDocumentField>()

        lines.firstOrNull {
            it.length in 2..50 &&
                !emailRegex.containsMatchIn(it) &&
                !phoneRegex.containsMatchIn(it) &&
                !websiteRegex.containsMatchIn(it)
        }?.let {
            fields += field("name", "Name", it, 0.55f)
        }

        emailRegex.find(text)?.value?.let {
            fields += field("email", "Email", it, 0.96f)
        }
        phoneRegex.find(text)?.value?.trim()?.let {
            fields += field("phone", "Phone", it, 0.90f)
        }
        websiteRegex.find(text)?.value?.trimEnd('.', ',', ';')?.let {
            fields += field("website", "Website", it, 0.90f)
        }

        return fields.distinctBy { it.key }
    }

    private fun extractId(text: String): List<ExtractedDocumentField> {
        val lines = meaningfulLines(text)
        val fields = mutableListOf<ExtractedDocumentField>()

        findValueAfterLabel(
            lines,
            listOf("date of birth", "birth", "geburtsdatum", "dob")
        )?.let {
            val value = dateRegex.find(it)?.value ?: it
            fields += field("date_of_birth", "Date of birth", value, 0.78f)
        }

        findValueAfterLabel(
            lines,
            listOf("expiry", "expires", "valid until", "gültig bis")
        )?.let {
            val value = dateRegex.find(it)?.value ?: it
            fields += field("expiry", "Expiry", value, 0.78f)
        }

        findValueAfterLabel(
            lines,
            listOf("nationality", "nationalität")
        )?.let {
            fields += field("nationality", "Nationality", it, 0.70f)
        }

        findValueAfterLabel(
            lines,
            listOf("document no", "document number", "passport no", "id no", "ausweisnummer")
        )?.let {
            fields += field("document_number", "Document number", it, 0.68f)
        }

        return fields.distinctBy { it.key }
    }

    private fun extractForm(text: String): List<ExtractedDocumentField> {
        val lines = meaningfulLines(text)
        val fields = mutableListOf<ExtractedDocumentField>()

        lines.firstOrNull { "signature" in it.lowercase() }?.let {
            fields += field("signature_field", "Signature field", "Detected", 0.65f)
        }
        lines.firstOrNull {
            val lower = it.lowercase()
            "application" in lower || "form" in lower
        }?.let {
            fields += field("form_title", "Form", it, 0.55f)
        }

        return fields
    }

    private fun extractCertificate(text: String): List<ExtractedDocumentField> {
        val lines = meaningfulLines(text)
        val fields = mutableListOf<ExtractedDocumentField>()

        val recipient = findValueAfterLabel(
            lines,
            listOf("awarded to", "presented to", "certifies that", "certify that")
        )
        recipient?.let {
            fields += field("recipient", "Recipient", it, 0.72f)
        }

        lines.firstNotNullOfOrNull { dateRegex.find(it)?.value }?.let {
            fields += field("date", "Date", it, 0.62f)
        }

        return fields
    }

    private fun meaningfulLines(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toList()

    private fun findValueAfterLabel(
        lines: List<String>,
        labels: List<String>
    ): String? {
        lines.forEachIndexed { index, line ->
            val lower = line.lowercase()
            val label = labels.firstOrNull { it in lower } ?: return@forEachIndexed
            val labelIndex = lower.indexOf(label)
            val after = line
                .substring((labelIndex + label.length).coerceAtMost(line.length))
                .trim(' ', ':', '-', '–')
            if (after.length >= 2) return after
            lines.getOrNull(index + 1)?.takeIf { it.length >= 2 }?.let { return it }
        }
        return null
    }

    private fun field(
        key: String,
        label: String,
        value: String,
        confidence: Float
    ): ExtractedDocumentField =
        ExtractedDocumentField(
            key = key,
            label = label,
            value = value.trim(),
            confidence = confidence.coerceIn(0f, 1f)
        )
}
