package com.thiepn.scan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thiepn.scan.data.DocumentFieldEntity
import com.thiepn.scan.data.ScanMode
import com.thiepn.scan.data.ScanModeProfiles

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanModeChooserDialog(
    onDismiss: () -> Unit,
    onChoose: (ScanMode, Boolean) -> Unit
) {
    var rapidCapture by remember { mutableStateOf(false) }
    val frequent = listOf(
        ScanMode.DOCUMENT,
        ScanMode.RECEIPT,
        ScanMode.BOOK,
        ScanMode.ID_CARD,
        ScanMode.NOTES
    )
    val modes = frequent + ScanMode.entries.filterNot { it in frequent }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("scan-mode-sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Scan type",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Document is the best choice for most pages.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            modes.forEach { mode ->
                val profile = ScanModeProfiles.forMode(mode)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("scan-mode-${mode.name.lowercase()}")
                        .clickable {
                            onChoose(
                                mode,
                                rapidCapture && profile.supportsHighSpeedCapture
                            )
                        }
                        .padding(vertical = 11.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            mode.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            profile.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (profile.requiresTwoSidedCapture) {
                        Text(
                            "2 sides",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (profile.pageLimit == 1) {
                        Text(
                            "1 page",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { rapidCapture = !rapidCapture }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Continuous capture",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "For documents, books, forms, and notes. Save each batch before opening the scanner again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Checkbox(
                    checked = rapidCapture,
                    onCheckedChange = null
                )
            }
            TextButton(
                modifier = Modifier.align(Alignment.End),
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        }
    }
}
 
@Composable
fun ChangeScanModeDialog(
    current: ScanMode,
    onDismiss: () -> Unit,
    onApply: (ScanMode, Boolean) -> Unit
) {
    var selected by remember(current) { mutableStateOf(current) }
    var applyDefaults by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan mode") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ScanMode.entries.forEach { mode ->
                    val profile = ScanModeProfiles.forMode(mode)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = mode }
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selected == mode,
                            onClick = { selected = mode }
                        )
                        Column {
                            Text(mode.label, fontWeight = FontWeight.SemiBold)
                            Text(
                                profile.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { applyDefaults = !applyDefaults }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = applyDefaults,
                        onCheckedChange = { applyDefaults = it }
                    )
                    Column {
                        Text("Apply mode enhancement defaults")
                        Text(
                            "Crop geometry and page order stay unchanged.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(selected, applyDefaults) }) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun SpecializedModeSummary(
    mode: ScanMode,
    pageCount: Int,
    fields: List<DocumentFieldEntity>,
    modifier: Modifier = Modifier
) {
    val profile = ScanModeProfiles.forMode(mode)
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "${mode.label} mode",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            profile.description,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            when {
                profile.requiresTwoSidedCapture && pageCount >= 2 ->
                    "Front and back captured."
                profile.requiresTwoSidedCapture ->
                    "Front captured. Add or insert the back side to complete the ID."
                !profile.ocrEnabled ->
                    "OCR is disabled in Photo mode to preserve a visual-first workflow."
                else -> profile.captureHint
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (fields.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 3.dp))
            Text(
                "Extracted details",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            fields.forEach { field ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            field.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (field.fieldKey == "capture_warning") {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Text(
                            field.value,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (field.fieldKey != "capture_warning") {
                        TextButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(field.value))
                            }
                        ) {
                            Text("Copy")
                        }
                    }
                }
            }
        }
    }
}

