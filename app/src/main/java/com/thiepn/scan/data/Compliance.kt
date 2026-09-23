package com.thiepn.scan.data

import java.util.Base64

enum class PdfStandard(val label: String) {
    STANDARD("Standard PDF"),
    PDF_A_1B("PDF/A-1b"),
    PDF_A_2B("PDF/A-2b")
}

enum class AccessibilityMode(val label: String) {
    NONE("None"),
    TAGGED_OCR("Tagged OCR reading order")
}

enum class ExportOptimization(val label: String) {
    ORIGINAL("Original quality"),
    HIGH("High quality"),
    BALANCED("Balanced"),
    COMPACT("Compact")
}

data class ComplianceSettings(
    val version: Int = 1,
    val pdfStandard: PdfStandard = PdfStandard.STANDARD,
    val accessibilityMode: AccessibilityMode = AccessibilityMode.NONE,
    val documentLanguage: String = "en",
    val optimization: ExportOptimization = ExportOptimization.HIGH,
    val validateAfterExport: Boolean = true
) {
    fun normalized(): ComplianceSettings = copy(
        version = 1,
        documentLanguage = normalizeLanguage(documentLanguage)
    )

    fun isPdfA(): Boolean = pdfStandard != PdfStandard.STANDARD

    fun requiresNormalizedRebuild(): Boolean =
        isPdfA() || accessibilityMode == AccessibilityMode.TAGGED_OCR

    fun allowsEncryption(): Boolean = !isPdfA()

    fun pdfQuality(): PdfQuality = when (optimization) {
        ExportOptimization.ORIGINAL -> PdfQuality.ORIGINAL
        ExportOptimization.HIGH -> PdfQuality.HIGH
        ExportOptimization.BALANCED -> PdfQuality.BALANCED
        ExportOptimization.COMPACT -> PdfQuality.SMALL
    }

    companion object {
        private val languagePattern = Regex(
            "^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$"
        )

        fun normalizeLanguage(value: String): String {
            val clean = value.trim().replace('_', '-').take(35)
            return clean.takeIf { languagePattern.matches(it) } ?: "en"
        }
    }
}

object ComplianceSettingsCodec {
    fun encode(value: ComplianceSettings?): String? {
        val normalized = value?.normalized() ?: return null
        if (
            normalized == ComplianceSettings().normalized()
        ) {
            return null
        }
        return listOf(
            "1",
            normalized.pdfStandard.name,
            normalized.accessibilityMode.name,
            enc(normalized.documentLanguage),
            normalized.optimization.name,
            if (normalized.validateAfterExport) "1" else "0"
        ).joinToString("\t")
    }

    fun decode(encoded: String?): ComplianceSettings {
        if (encoded.isNullOrBlank()) return ComplianceSettings()
        val p = encoded.split("\t")
        if (p.size != 6 || p[0] != "1") return ComplianceSettings()
        return runCatching {
            ComplianceSettings(
                pdfStandard = PdfStandard.valueOf(p[1]),
                accessibilityMode = AccessibilityMode.valueOf(p[2]),
                documentLanguage = dec(p[3]),
                optimization = ExportOptimization.valueOf(p[4]),
                validateAfterExport = p[5] == "1"
            ).normalized()
        }.getOrDefault(ComplianceSettings())
    }

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun dec(value: String): String =
        runCatching {
            Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)
        }.getOrDefault("en")
}

enum class ComplianceSeverity {
    ERROR,
    WARNING,
    INFO
}

data class ComplianceIssue(
    val code: String,
    val severity: ComplianceSeverity,
    val message: String
)

data class ComplianceReport(
    val standard: PdfStandard,
    val accessibilityMode: AccessibilityMode,
    val issues: List<ComplianceIssue>
) {
    val errorCount: Int
        get() = issues.count { it.severity == ComplianceSeverity.ERROR }

    val warningCount: Int
        get() = issues.count { it.severity == ComplianceSeverity.WARNING }

    val passed: Boolean
        get() = errorCount == 0
}

data class StandardsExportResult(
    val file: java.io.File,
    val report: ComplianceReport
)
