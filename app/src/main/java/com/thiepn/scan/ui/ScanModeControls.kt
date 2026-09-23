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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thiepn.scan.data.DocumentFieldEntity
import com.thiepn.scan.data.ScanMode
import com.thiepn.scan.data.ScanModeProfiles

@Composable
fun ScanModeChooserDialog(
    onDismiss: () -> Unit,
    onChoose: (ScanMode) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose scan mode") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ScanMode.entries.forEachIndexed { index, mode ->
                    val profile = ScanModeProfiles.forMode(mode)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChoose(mode) }
                            .padding(vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                mode.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            if (profile.requiresTwoSidedCapture) {
                                Text(
                                    "Front + back",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                profile.pageLimit?.let { limit ->
                                    Text(
                                        if (limit == 1) "1 page" else "Up to $limit pages",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Text(
                            profile.description,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            profile.captureHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    if (index != ScanMode.entries.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
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

