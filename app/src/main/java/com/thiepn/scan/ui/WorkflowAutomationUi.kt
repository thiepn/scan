package com.thiepn.scan.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thiepn.scan.data.AccessibilityMode
import com.thiepn.scan.data.AutomationCondition
import com.thiepn.scan.data.ComplianceSettings
import com.thiepn.scan.data.DocumentProcessingPreset
import com.thiepn.scan.data.DocumentSecuritySettings
import com.thiepn.scan.data.DocumentType
import com.thiepn.scan.data.ExtractionSchemaEntity
import com.thiepn.scan.data.FolderEntity
import com.thiepn.scan.data.PdfStandard
import com.thiepn.scan.data.ProcessingPresetEntity
import com.thiepn.scan.data.ScanMode
import com.thiepn.scan.data.ScanRepository
import com.thiepn.scan.data.TagEntity
import com.thiepn.scan.data.WorkflowExportFormat
import com.thiepn.scan.data.WorkflowRunStatus
import kotlinx.coroutines.launch

private enum class AutomationSection(val label: String) {
    PRESETS("Presets"),
    RULES("Rules"),
    DESTINATIONS("Destinations"),
    HISTORY("History")
}

@Composable
fun WorkflowAutomationDialog(
    repository: ScanRepository,
    folders: List<FolderEntity>,
    tags: List<TagEntity>,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val presets by repository.observeProcessingPresets()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val rules by repository.observeWorkflowRules()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val destinations by repository.observeWorkflowDestinations()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val runs by repository.observeWorkflowRuns()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val schemas by repository.observeExtractionSchemas()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val vaultState by repository.observeVaultState()
        .collectAsStateWithLifecycle()

    var section by remember { mutableStateOf(AutomationSection.PRESETS) }

    var presetName by remember { mutableStateOf("") }
    var renameTemplate by remember { mutableStateOf("") }
    var presetFolderId by remember { mutableStateOf<String?>(null) }
    var presetTagIds by remember { mutableStateOf(setOf<String>()) }
    var presetType by remember { mutableStateOf<DocumentType?>(null) }
    var presetReview by remember { mutableStateOf<Boolean?>(null) }
    var presetFavorite by remember { mutableStateOf(false) }
    var presetArchive by remember { mutableStateOf(false) }
    var presetSchemaId by remember { mutableStateOf<String?>(null) }
    var presetPdfStandard by remember { mutableStateOf<PdfStandard?>(null) }
    var presetAccessiblePdf by remember { mutableStateOf(false) }
    var presetVault by remember { mutableStateOf(false) }
    var presetDestinationId by remember { mutableStateOf<String?>(null) }

    var ruleName by remember { mutableStateOf("") }
    var rulePresetId by remember { mutableStateOf<String?>(null) }
    var ruleScanMode by remember { mutableStateOf<ScanMode?>(null) }
    var ruleDocumentType by remember { mutableStateOf<DocumentType?>(null) }
    var ruleTitleContains by remember { mutableStateOf("") }
    var ruleOcrContains by remember { mutableStateOf("") }
    var ruleFieldKey by remember { mutableStateOf("") }
    var ruleFieldContains by remember { mutableStateOf("") }
    var ruleNeedsReview by remember { mutableStateOf<Boolean?>(null) }
    var ruleOnlyUnfiled by remember { mutableStateOf(false) }
    var ruleStopAfterMatch by remember { mutableStateOf(false) }
    var rulePriority by remember { mutableStateOf("100") }

    var destinationName by remember { mutableStateOf("") }
    var destinationUri by remember { mutableStateOf<Uri?>(null) }
    var destinationFormat by remember {
        mutableStateOf(WorkflowExportFormat.PDF)
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            destinationUri = uri
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Automation Center") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AutomationSection.entries.forEach { item ->
                        FilterChip(
                            selected = section == item,
                            onClick = { section = item },
                            label = { Text(item.label) }
                        )
                    }
                }
                HorizontalDivider()

                when (section) {
                    AutomationSection.PRESETS -> {
                        SectionTitle(
                            "Processing presets",
                            "Bundle naming, filing, extraction, compliance, security, delivery, and lifecycle actions."
                        )
                        presets.forEach { preset ->
                            AutomationRow(
                                title = preset.name,
                                subtitle = summarizePreset(preset),
                                actionLabel = "Delete",
                                onAction = {
                                    scope.launch {
                                        runCatching {
                                            repository.deleteProcessingPreset(preset.id)
                                        }.onFailure {
                                            onMessage(it.message ?: "Could not delete preset")
                                        }
                                    }
                                }
                            )
                        }
                        if (presets.isNotEmpty()) HorizontalDivider()

                        OutlinedTextField(
                            value = presetName,
                            onValueChange = { presetName = it },
                            label = { Text("Preset name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = renameTemplate,
                            onValueChange = { renameTemplate = it },
                            label = { Text("Automatic name template") },
                            supportingText = {
                                Text("{date}, {time}, {type}, {mode}, {title}, {field:key}")
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        ChoiceMenu(
                            label = "Folder",
                            selected = presetFolderId,
                            options = folders.map { it.id to it.name },
                            noneLabel = "No folder action",
                            onSelected = { presetFolderId = it }
                        )
                        ChoiceMenu(
                            label = "Document type",
                            selected = presetType,
                            options = DocumentType.entries.map { it to it.label },
                            noneLabel = "No classification action",
                            onSelected = { presetType = it }
                        )
                        ChoiceMenu(
                            label = "Review state",
                            selected = presetReview,
                            options = listOf(
                                true to "Mark needs review",
                                false to "Clear needs review"
                            ),
                            noneLabel = "No review action",
                            onSelected = { presetReview = it }
                        )
                        ChoiceMenu(
                            label = "Extraction schema",
                            selected = presetSchemaId,
                            options = schemas.map { it.id to it.name },
                            noneLabel = "No extraction schema",
                            onSelected = { presetSchemaId = it }
                        )
                        ChoiceMenu(
                            label = "PDF compliance",
                            selected = presetPdfStandard,
                            options = PdfStandard.entries.map { it to it.label },
                            noneLabel = "No compliance action",
                            onSelected = { presetPdfStandard = it }
                        )
                        ChoiceMenu(
                            label = "Scan-to destination",
                            selected = presetDestinationId,
                            options = destinations.map { it.id to it.name },
                            noneLabel = "No automatic delivery",
                            onSelected = { presetDestinationId = it }
                        )

                        if (tags.isNotEmpty()) {
                            Text(
                                "Tags",
                                style = MaterialTheme.typography.labelLarge
                            )
                            tags.forEach { tag ->
                                ToggleRow(
                                    checked = tag.id in presetTagIds,
                                    label = tag.name,
                                    onCheckedChange = { checked ->
                                        presetTagIds = if (checked) {
                                            presetTagIds + tag.id
                                        } else {
                                            presetTagIds - tag.id
                                        }
                                    }
                                )
                            }
                        }
                        ToggleRow(
                            checked = presetFavorite,
                            label = "Mark as favorite",
                            onCheckedChange = { presetFavorite = it }
                        )
                        ToggleRow(
                            checked = presetArchive,
                            label = "Archive after processing",
                            onCheckedChange = { presetArchive = it }
                        )
                        ToggleRow(
                            checked = presetAccessiblePdf,
                            label = "Require tagged OCR accessibility",
                            onCheckedChange = { presetAccessiblePdf = it }
                        )
                        ToggleRow(
                            checked = presetVault,
                            label = "Protect in vault after workflow",
                            onCheckedChange = { presetVault = it }
                        )
                        TextButton(
                            onClick = {
                                val compliance = if (
                                    presetPdfStandard != null || presetAccessiblePdf
                                ) {
                                    ComplianceSettings(
                                        pdfStandard = presetPdfStandard
                                            ?: PdfStandard.STANDARD,
                                        accessibilityMode = if (presetAccessiblePdf) {
                                            AccessibilityMode.TAGGED_OCR
                                        } else {
                                            AccessibilityMode.NONE
                                        }
                                    )
                                } else {
                                    null
                                }
                                val security = if (presetVault) {
                                    DocumentSecuritySettings(vaultEnabled = true)
                                } else {
                                    null
                                }
                                val preset = DocumentProcessingPreset(
                                    renameTemplate = renameTemplate,
                                    folderId = presetFolderId,
                                    tagIds = presetTagIds,
                                    documentType = presetType,
                                    needsReview = presetReview,
                                    favorite = true.takeIf { presetFavorite },
                                    archive = true.takeIf { presetArchive },
                                    extractionSchemaId = presetSchemaId,
                                    complianceSettings = compliance,
                                    securitySettings = security,
                                    destinationId = presetDestinationId
                                )
                                scope.launch {
                                    runCatching {
                                        repository.saveProcessingPreset(
                                            presetName,
                                            preset
                                        )
                                    }.onSuccess {
                                        presetName = ""
                                        renameTemplate = ""
                                        presetFolderId = null
                                        presetTagIds = emptySet()
                                        presetType = null
                                        presetReview = null
                                        presetFavorite = false
                                        presetArchive = false
                                        presetSchemaId = null
                                        presetPdfStandard = null
                                        presetAccessiblePdf = false
                                        presetVault = false
                                        presetDestinationId = null
                                        onMessage("Processing preset saved")
                                    }.onFailure {
                                        onMessage(it.message ?: "Could not save preset")
                                    }
                                }
                            }
                        ) {
                            Text("Save preset")
                        }
                    }

                    AutomationSection.RULES -> {
                        SectionTitle(
                            "Intake rules",
                            "Rules run once after OCR and field extraction. Lower priority numbers run first."
                        )
                        val presetNames = presets.associate { it.id to it.name }
                        val selectedRulePresetLocksVault = presets
                            .firstOrNull { it.id == rulePresetId }
                            ?.let { preset ->
                                runCatching {
                                    com.thiepn.scan.data.DocumentProcessingPresetCodec
                                        .decode(preset.definition)
                                        .securitySettings
                                        ?.vaultEnabled == true
                                }.getOrDefault(false)
                            } == true
                        rules.forEach { rule ->
                            AutomationRow(
                                title = rule.name,
                                subtitle = (if (rule.enabled) "Enabled" else "Disabled") +
                                    " · priority " + rule.priority +
                                    " · " + (presetNames[rule.presetId] ?: "Missing preset"),
                                actionLabel = if (rule.enabled) "Disable" else "Enable",
                                onAction = {
                                    scope.launch {
                                        runCatching {
                                            repository.setWorkflowRuleEnabled(
                                                rule.id,
                                                !rule.enabled
                                            )
                                        }.onFailure {
                                            onMessage(it.message ?: "Could not update rule")
                                        }
                                    }
                                },
                                secondaryActionLabel = "Delete",
                                onSecondaryAction = {
                                    scope.launch {
                                        repository.deleteWorkflowRule(rule.id)
                                    }
                                }
                            )
                        }
                        if (rules.isNotEmpty()) HorizontalDivider()

                        OutlinedTextField(
                            value = ruleName,
                            onValueChange = { ruleName = it },
                            label = { Text("Rule name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        ChoiceMenu(
                            label = "Processing preset",
                            selected = rulePresetId,
                            options = presets.map { it.id to it.name },
                            noneLabel = "Choose a preset",
                            onSelected = { rulePresetId = it }
                        )
                        ChoiceMenu(
                            label = "Scan mode",
                            selected = ruleScanMode,
                            options = ScanMode.entries.map { it to it.label },
                            noneLabel = "Any scan mode",
                            onSelected = { ruleScanMode = it }
                        )
                        ChoiceMenu(
                            label = "Document type",
                            selected = ruleDocumentType,
                            options = DocumentType.entries.map { it to it.label },
                            noneLabel = "Any document type",
                            onSelected = { ruleDocumentType = it }
                        )
                        OutlinedTextField(
                            value = ruleTitleContains,
                            onValueChange = { ruleTitleContains = it },
                            label = { Text("Title contains") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = ruleOcrContains,
                            onValueChange = { ruleOcrContains = it },
                            label = { Text("OCR text contains") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = ruleFieldKey,
                            onValueChange = { ruleFieldKey = it },
                            label = { Text("Extracted field key") },
                            supportingText = {
                                Text("Example: merchant, total, invoice_number")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = ruleFieldContains,
                            onValueChange = { ruleFieldContains = it },
                            label = { Text("Extracted field contains") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        ChoiceMenu(
                            label = "Review condition",
                            selected = ruleNeedsReview,
                            options = listOf(
                                true to "Needs review",
                                false to "Does not need review"
                            ),
                            noneLabel = "Any review state",
                            onSelected = { ruleNeedsReview = it }
                        )
                        OutlinedTextField(
                            value = rulePriority,
                            onValueChange = { rulePriority = it.filter(Char::isDigit) },
                            label = { Text("Priority") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        ToggleRow(
                            checked = ruleOnlyUnfiled,
                            label = "Only unfiled documents",
                            onCheckedChange = { ruleOnlyUnfiled = it }
                        )
                        ToggleRow(
                            checked = ruleStopAfterMatch || selectedRulePresetLocksVault,
                            label = "Stop after this rule matches",
                            onCheckedChange = {
                                if (!selectedRulePresetLocksVault) {
                                    ruleStopAfterMatch = it
                                }
                            }
                        )
                        if (selectedRulePresetLocksVault) {
                            Text(
                                "Vault protection ends rule chaining so later rules are not blocked by a newly locked document.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(
                            enabled = rulePresetId != null,
                            onClick = {
                                val presetId = rulePresetId ?: return@TextButton
                                scope.launch {
                                    runCatching {
                                        repository.saveWorkflowRule(
                                            name = ruleName,
                                            condition = AutomationCondition(
                                                scanMode = ruleScanMode,
                                                documentType = ruleDocumentType,
                                                titleContains = ruleTitleContains,
                                                ocrContains = ruleOcrContains,
                                                fieldKey = ruleFieldKey,
                                                fieldContains = ruleFieldContains,
                                                needsReview = ruleNeedsReview,
                                                onlyUnfiled = ruleOnlyUnfiled
                                            ),
                                            presetId = presetId,
                                            priority = rulePriority.toIntOrNull() ?: 100,
                                            stopAfterMatch =
                                                ruleStopAfterMatch ||
                                                    selectedRulePresetLocksVault
                                        )
                                    }.onSuccess {
                                        ruleName = ""
                                        rulePresetId = null
                                        ruleScanMode = null
                                        ruleDocumentType = null
                                        ruleTitleContains = ""
                                        ruleOcrContains = ""
                                        ruleFieldKey = ""
                                        ruleFieldContains = ""
                                        ruleNeedsReview = null
                                        ruleOnlyUnfiled = false
                                        ruleStopAfterMatch = false
                                        rulePriority = "100"
                                        onMessage("Automation rule saved")
                                    }.onFailure {
                                        onMessage(it.message ?: "Could not save rule")
                                    }
                                }
                            }
                        ) {
                            Text("Save rule")
                        }
                    }

                    AutomationSection.DESTINATIONS -> {
                        SectionTitle(
                            "Scan-to destinations",
                            "Deliver generated output to a persisted Android document-provider folder."
                        )
                        destinations.forEach { destination ->
                            AutomationRow(
                                title = destination.name,
                                subtitle = WorkflowExportFormat.entries
                                    .firstOrNull {
                                        it.name == destination.exportFormat
                                    }?.label ?: destination.exportFormat,
                                actionLabel = "Delete",
                                onAction = {
                                    scope.launch {
                                        runCatching {
                                            repository.deleteWorkflowDestination(
                                                destination.id
                                            )
                                        }.onSuccess {
                                            onMessage("Destination deleted")
                                        }.onFailure {
                                            onMessage(
                                                it.message ?: "Could not delete destination"
                                            )
                                        }
                                    }
                                }
                            )
                        }
                        if (destinations.isNotEmpty()) HorizontalDivider()

                        OutlinedTextField(
                            value = destinationName,
                            onValueChange = { destinationName = it },
                            label = { Text("Destination name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        ChoiceMenu(
                            label = "Export format",
                            selected = destinationFormat,
                            options = WorkflowExportFormat.entries.map { it to it.label },
                            noneLabel = "PDF",
                            allowNone = false,
                            onSelected = { selected ->
                                if (selected != null) destinationFormat = selected
                            }
                        )
                        OutlinedButton(
                            onClick = { folderLauncher.launch(null) }
                        ) {
                            Text(
                                destinationUri?.lastPathSegment
                                    ?: "Choose destination folder"
                            )
                        }
                        TextButton(
                            enabled = destinationUri != null,
                            onClick = {
                                val uri = destinationUri ?: return@TextButton
                                scope.launch {
                                    runCatching {
                                        repository.saveWorkflowDestination(
                                            destinationName,
                                            uri,
                                            destinationFormat
                                        )
                                    }.onSuccess {
                                        destinationName = ""
                                        destinationUri = null
                                        destinationFormat = WorkflowExportFormat.PDF
                                        onMessage("Workflow destination saved")
                                    }.onFailure {
                                        onMessage(
                                            it.message ?: "Could not save destination"
                                        )
                                    }
                                }
                            }
                        ) {
                            Text("Save destination")
                        }
                    }

                    AutomationSection.HISTORY -> {
                        SectionTitle(
                            "Workflow history",
                            "Recent automatic and manual runs, including failures and retry state."
                        )
                        if (runs.isEmpty()) {
                            Text("No workflow runs yet.")
                        }
                        runs.forEach { run ->
                            val locked = run.documentId in vaultState.lockedDocumentIds
                            val title = if (locked) "Locked document" else run.documentTitle
                            val subtitle = buildString {
                                append(run.status)
                                if (run.attemptCount > 0) {
                                    append(" · attempt ")
                                    append(run.attemptCount)
                                }
                                if (run.summary.isNotBlank()) {
                                    append(" · ")
                                    append(run.summary)
                                }
                                run.lastError?.takeIf(String::isNotBlank)?.let {
                                    append(" · ")
                                    append(it)
                                }
                            }
                            AutomationRow(
                                title = title,
                                subtitle = subtitle,
                                actionLabel = "Retry",
                                actionEnabled =
                                    run.status == WorkflowRunStatus.FAILED.name,
                                onAction = {
                                    scope.launch {
                                        runCatching {
                                            repository.retryWorkflowRun(run.id)
                                        }.onSuccess {
                                            onMessage("Workflow retried")
                                        }.onFailure {
                                            onMessage(it.message ?: "Could not retry workflow")
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
fun ProcessingPresetPickerDialog(
    repository: ScanRepository,
    selectedDocumentIds: List<String>,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val presets by repository.observeProcessingPresets()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedPresetId by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Batch workflow") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    selectedDocumentIds.size.toString() + " document(s) selected",
                    style = MaterialTheme.typography.bodyMedium
                )
                ChoiceMenu(
                    label = "Processing preset",
                    selected = selectedPresetId,
                    options = presets.map { it.id to it.name },
                    noneLabel = "Choose a preset",
                    onSelected = { selectedPresetId = it }
                )
                TextButton(
                    enabled = selectedPresetId != null,
                    onClick = {
                        val presetId = selectedPresetId ?: return@TextButton
                        scope.launch {
                            runCatching {
                                repository.applyProcessingPresetToDocuments(
                                    presetId,
                                    selectedDocumentIds
                                )
                            }.onSuccess { result ->
                                onMessage(
                                    "Batch complete: " + result.succeeded +
                                        " succeeded, " + result.failed + " failed"
                                )
                                onDismiss()
                            }.onFailure {
                                onMessage(it.message ?: "Batch workflow failed")
                            }
                        }
                    }
                ) {
                    Text("Apply preset")
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                repository.evaluateAutomationRulesForDocuments(
                                    selectedDocumentIds
                                )
                            }.onSuccess { result ->
                                onMessage(
                                    "Rules complete: " + result.succeeded +
                                        " matched successfully, " + result.failed + " failed"
                                )
                                onDismiss()
                            }.onFailure {
                                onMessage(it.message ?: "Could not run intake rules")
                            }
                        }
                    }
                ) {
                    Text("Run intake rules now")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ToggleRow(
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Text(
            label,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

@Composable
private fun AutomationRow(
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onAction,
                enabled = actionEnabled
            ) {
                Text(actionLabel)
            }
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                TextButton(onClick = onSecondaryAction) {
                    Text(secondaryActionLabel)
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun <T> ChoiceMenu(
    label: String,
    selected: T?,
    options: List<Pair<T, String>>,
    noneLabel: String,
    allowNone: Boolean = true,
    onSelected: (T?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second
        ?: noneLabel

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(label + ": " + selectedLabel)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (allowNone) {
                DropdownMenuItem(
                    text = { Text(noneLabel) },
                    onClick = {
                        onSelected(null)
                        expanded = false
                    }
                )
            }
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.second) },
                    onClick = {
                        onSelected(option.first)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun summarizePreset(preset: ProcessingPresetEntity): String {
    val decoded = runCatching {
        com.thiepn.scan.data.DocumentProcessingPresetCodec.decode(
            preset.definition
        )
    }.getOrNull() ?: return "Invalid preset"
    val actions = mutableListOf<String>()
    if (decoded.renameTemplate.isNotBlank()) actions += "rename"
    if (decoded.folderId != null) actions += "file"
    if (decoded.tagIds.isNotEmpty()) actions += "tag"
    if (decoded.documentType != null) actions += "classify"
    if (decoded.extractionSchemaId != null) actions += "extract"
    if (decoded.complianceSettings != null) actions += "compliance"
    if (decoded.securitySettings != null) actions += "security"
    if (decoded.destinationId != null) actions += "deliver"
    if (decoded.archive != null) actions += "lifecycle"
    return actions.joinToString(" · ").ifBlank { "No actions" }
}
