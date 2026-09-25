package com.thiepn.scan.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64

@Entity(
    tableName = "processing_presets",
    indices = [Index(value = ["name"])]
)
data class ProcessingPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val definition: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "workflow_rules",
    indices = [
        Index("enabled"),
        Index("priority"),
        Index("trigger"),
        Index("presetId")
    ]
)
data class WorkflowRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean = true,
    val priority: Int = 100,
    val trigger: String = WorkflowTrigger.INTAKE.name,
    val condition: String,
    val presetId: String,
    val stopAfterMatch: Boolean = false,
    val maxAttempts: Int = 3,
    val retryBackoffMillis: Long = 30_000L,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "workflow_destinations",
    indices = [Index(value = ["name"])]
)
data class WorkflowDestinationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val treeUri: String,
    val exportFormat: String = WorkflowExportFormat.PDF.name,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "workflow_runs",
    indices = [
        Index("documentId"),
        Index("ruleId"),
        Index("status"),
        Index("nextRetryAt"),
        Index(value = ["documentId", "ruleId", "trigger"])
    ]
)
data class WorkflowRunEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val documentTitle: String,
    val ruleId: String?,
    val presetId: String,
    val trigger: String,
    val status: String = WorkflowRunStatus.PENDING.name,
    val attemptCount: Int = 0,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val nextRetryAt: Long? = null,
    val outputUri: String? = null,
    val summary: String = "",
    val lastError: String? = null
)

enum class WorkflowTrigger {
    INTAKE,
    MANUAL
}

enum class WorkflowRunStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

enum class WorkflowExportFormat(val label: String) {
    PDF("PDF"),
    PDF_STANDARDIZED("Standards PDF"),
    PDF_PRIVACY("Privacy PDF"),
    TEXT("Text"),
    CSV("CSV"),
    JSON("JSON"),
    XLSX("Excel")
}

data class AutomationCondition(
    val scanMode: ScanMode? = null,
    val documentType: DocumentType? = null,
    val titleContains: String = "",
    val ocrContains: String = "",
    val fieldKey: String = "",
    val fieldContains: String = "",
    val minPages: Int? = null,
    val maxPages: Int? = null,
    val needsReview: Boolean? = null,
    val onlyUnfiled: Boolean = false
)

data class DocumentProcessingPreset(
    val renameTemplate: String = "",
    val folderId: String? = null,
    val tagIds: Set<String> = emptySet(),
    val documentType: DocumentType? = null,
    val needsReview: Boolean? = null,
    val favorite: Boolean? = null,
    val archive: Boolean? = null,
    val extractionSchemaId: String? = null,
    val complianceSettings: ComplianceSettings? = null,
    val securitySettings: DocumentSecuritySettings? = null,
    val destinationId: String? = null
) {
    fun isEmpty(): Boolean =
        renameTemplate.isBlank() &&
            folderId == null &&
            tagIds.isEmpty() &&
            documentType == null &&
            needsReview == null &&
            favorite == null &&
            archive == null &&
            extractionSchemaId == null &&
            complianceSettings == null &&
            securitySettings == null &&
            destinationId == null
}

data class AutomationDocumentSnapshot(
    val document: DocumentEntity,
    val fields: List<DocumentFieldEntity>
)

data class AutomationBatchResult(
    val succeeded: Int,
    val failed: Int
)

object AutomationConditionCodec {
    fun encode(value: AutomationCondition): String = WorkflowKeyValueCodec.encode(
        buildMap {
            put("v", "1")
            value.scanMode?.let { put("scanMode", it.name) }
            value.documentType?.let { put("documentType", it.name) }
            value.titleContains.takeIf(String::isNotBlank)?.let { put("title", it) }
            value.ocrContains.takeIf(String::isNotBlank)?.let { put("ocr", it) }
            value.fieldKey.takeIf(String::isNotBlank)?.let { put("fieldKey", it) }
            value.fieldContains.takeIf(String::isNotBlank)?.let { put("fieldContains", it) }
            value.minPages?.let { put("minPages", it.toString()) }
            value.maxPages?.let { put("maxPages", it.toString()) }
            value.needsReview?.let { put("needsReview", if (it) "1" else "0") }
            if (value.onlyUnfiled) put("onlyUnfiled", "1")
        }
    )

    fun decode(encoded: String): AutomationCondition {
        val map = WorkflowKeyValueCodec.decode(encoded)
        return AutomationCondition(
            scanMode = map["scanMode"]?.let { ScanMode.fromStored(it) },
            documentType = map["documentType"]?.let { DocumentType.fromStored(it) },
            titleContains = map["title"].orEmpty(),
            ocrContains = map["ocr"].orEmpty(),
            fieldKey = map["fieldKey"].orEmpty(),
            fieldContains = map["fieldContains"].orEmpty(),
            minPages = map["minPages"]?.toIntOrNull(),
            maxPages = map["maxPages"]?.toIntOrNull(),
            needsReview = map["needsReview"]?.let { it == "1" },
            onlyUnfiled = map["onlyUnfiled"] == "1"
        )
    }
}

object DocumentProcessingPresetCodec {
    fun encode(value: DocumentProcessingPreset): String = WorkflowKeyValueCodec.encode(
        buildMap {
            put("v", "1")
            value.renameTemplate.takeIf(String::isNotBlank)?.let { put("rename", it) }
            value.folderId?.let { put("folderId", it) }
            if (value.tagIds.isNotEmpty()) put("tagIds", value.tagIds.sorted().joinToString(","))
            value.documentType?.let { put("documentType", it.name) }
            value.needsReview?.let { put("needsReview", if (it) "1" else "0") }
            value.favorite?.let { put("favorite", if (it) "1" else "0") }
            value.archive?.let { put("archive", if (it) "1" else "0") }
            value.extractionSchemaId?.let { put("schemaId", it) }
            ComplianceSettingsCodec.encode(value.complianceSettings)?.let {
                put("compliance", it)
            }
            DocumentSecuritySettingsCodec.encode(value.securitySettings)?.let {
                put("security", it)
            }
            value.destinationId?.let { put("destinationId", it) }
        }
    )

    fun decode(encoded: String): DocumentProcessingPreset {
        val map = WorkflowKeyValueCodec.decode(encoded)
        return DocumentProcessingPreset(
            renameTemplate = map["rename"].orEmpty(),
            folderId = map["folderId"],
            tagIds = map["tagIds"]
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                ?.toSet()
                .orEmpty(),
            documentType = map["documentType"]?.let { DocumentType.fromStored(it) },
            needsReview = map["needsReview"]?.let { it == "1" },
            favorite = map["favorite"]?.let { it == "1" },
            archive = map["archive"]?.let { it == "1" },
            extractionSchemaId = map["schemaId"],
            complianceSettings = map["compliance"]?.let { ComplianceSettingsCodec.decode(it) },
            securitySettings = map["security"]?.let { DocumentSecuritySettingsCodec.decode(it) },
            destinationId = map["destinationId"]
        )
    }
}

object WorkflowAutomationMatcher {
    fun matches(
        condition: AutomationCondition,
        snapshot: AutomationDocumentSnapshot
    ): Boolean {
        val document = snapshot.document
        if (
            condition.scanMode != null &&
            ScanMode.fromStored(document.scanMode) != condition.scanMode
        ) return false
        if (
            condition.documentType != null &&
            DocumentType.fromStored(document.documentType) != condition.documentType
        ) return false
        if (!containsIgnoreCase(document.title, condition.titleContains)) return false
        if (!containsIgnoreCase(document.ocrText, condition.ocrContains)) return false
        if (condition.minPages != null && document.pageCount < condition.minPages) return false
        if (condition.maxPages != null && document.pageCount > condition.maxPages) return false
        if (condition.needsReview != null && document.needsReview != condition.needsReview) {
            return false
        }
        if (condition.onlyUnfiled && document.folderId != null) return false

        if (condition.fieldKey.isNotBlank()) {
            val field = snapshot.fields.firstOrNull {
                it.fieldKey.equals(condition.fieldKey.trim(), ignoreCase = true)
            } ?: return false
            if (!containsIgnoreCase(field.value, condition.fieldContains)) return false
        } else if (condition.fieldContains.isNotBlank()) {
            if (snapshot.fields.none { containsIgnoreCase(it.value, condition.fieldContains) }) {
                return false
            }
        }
        return true
    }

    private fun containsIgnoreCase(haystack: String, needle: String): Boolean =
        needle.isBlank() || haystack.contains(needle.trim(), ignoreCase = true)
}

object WorkflowNameTemplate {
    private val fieldPattern = Regex("""\{field:([^}]+)}""")
    private val invalidTitleCharacters = Regex("""[\\/:*?"<>|]""")
    private val whitespace = Regex("""\s+""")

    fun render(
        template: String,
        snapshot: AutomationDocumentSnapshot,
        now: Long = System.currentTimeMillis()
    ): String {
        if (template.isBlank()) return snapshot.document.title
        val instant = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val fields = snapshot.fields.associateBy { it.fieldKey.lowercase() }
        var rendered = template
            .replace("{date}", DateTimeFormatter.ofPattern("yyyy-MM-dd").format(instant))
            .replace("{time}", DateTimeFormatter.ofPattern("HHmm").format(instant))
            .replace(
                "{type}",
                DocumentType.fromStored(snapshot.document.documentType).label
            )
            .replace(
                "{mode}",
                ScanMode.fromStored(snapshot.document.scanMode).label
            )
            .replace("{title}", snapshot.document.title)

        rendered = fieldPattern.replace(rendered) { match ->
            val key = match.groupValues[1].trim().lowercase()
            fields[key]?.value.orEmpty()
        }
        return rendered
            .replace(invalidTitleCharacters, " ")
            .replace(whitespace, " ")
            .trim()
            .take(120)
            .ifBlank { snapshot.document.title }
    }
}

private object WorkflowKeyValueCodec {
    fun encode(values: Map<String, String>): String =
        values.toSortedMap().entries.joinToString("\n") { (key, value) ->
            key + "=" + encodeValue(value)
        }

    fun decode(encoded: String): Map<String, String> =
        encoded.lineSequence()
            .mapNotNull { line ->
                val split = line.indexOf('=')
                if (split <= 0) return@mapNotNull null
                val key = line.substring(0, split)
                val value = runCatching {
                    decodeValue(line.substring(split + 1))
                }.getOrNull() ?: return@mapNotNull null
                key to value
            }
            .toMap()

    private fun encodeValue(value: String): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeValue(value: String): String {
        if (value.isEmpty()) return ""
        return String(
            Base64.getUrlDecoder().decode(value),
            StandardCharsets.UTF_8
        )
    }
}
