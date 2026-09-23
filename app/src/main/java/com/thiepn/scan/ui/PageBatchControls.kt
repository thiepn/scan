package com.thiepn.scan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.thiepn.scan.data.PdfQuality
import com.thiepn.scan.data.ScanPreset

@Composable
fun BatchActionBar(
    selectedCount: Int,
    canDelete: Boolean,
    onRotate: () -> Unit,
    onFilter: () -> Unit,
    onCleanup: () -> Unit,
    onMove: () -> Unit,
    onDuplicate: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = onRotate, enabled = selectedCount > 0) {
            androidx.compose.material3.Icon(Icons.Default.RotateRight, contentDescription = null)
            Text(" Rotate")
        }
        OutlinedButton(onClick = onFilter, enabled = selectedCount > 0) {
            androidx.compose.material3.Icon(Icons.Default.FilterAlt, contentDescription = null)
            Text(" Filter")
        }
        OutlinedButton(onClick = onCleanup, enabled = selectedCount > 0) {
            androidx.compose.material3.Icon(Icons.Default.AutoFixHigh, contentDescription = null)
            Text(" Auto clean")
        }
        OutlinedButton(onClick = onMove, enabled = selectedCount > 0) {
            androidx.compose.material3.Icon(Icons.Default.DriveFileMove, contentDescription = null)
            Text(" Move")
        }
        OutlinedButton(onClick = onDuplicate, enabled = selectedCount > 0) {
            androidx.compose.material3.Icon(Icons.Default.ContentCopy, contentDescription = null)
            Text(" Duplicate")
        }
        OutlinedButton(onClick = onReset, enabled = selectedCount > 0) {
            androidx.compose.material3.Icon(Icons.Default.RestartAlt, contentDescription = null)
            Text(" Reset")
        }
        OutlinedButton(onClick = onExport, enabled = selectedCount > 0) {
            androidx.compose.material3.Icon(Icons.Default.IosShare, contentDescription = null)
            Text(" Export")
        }
        OutlinedButton(
            onClick = onDelete,
            enabled = selectedCount > 0 && canDelete
        ) {
            androidx.compose.material3.Icon(Icons.Default.Delete, contentDescription = null)
            Text(" Delete")
        }
    }
}

@Composable
fun BatchFilterDialog(
    onDismiss: () -> Unit,
    onApply: (ScanPreset) -> Unit
) {
    var preset by remember { mutableStateOf(ScanPreset.AUTO) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply filter to selected pages") },
        text = {
            Column {
                batchPresetLabels.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { preset = value }
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = preset == value,
                            onClick = { preset = value }
                        )
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(preset) }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun MovePagesDialog(
    pageCount: Int,
    selectedCount: Int,
    onDismiss: () -> Unit,
    onMove: (Int) -> Unit
) {
    val maxPosition = (pageCount - selectedCount + 1).coerceAtLeast(1)
    var value by remember(pageCount, selectedCount) { mutableStateOf("1") }
    val position = value.toIntOrNull()
    val valid = position != null && position in 1..maxPosition

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move selected pages") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Selected pages keep their relative order. Position 1 moves them to the beginning; position $maxPosition moves them to the end."
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.filter(Char::isDigit) },
                    singleLine = true,
                    label = { Text("Position") },
                    supportingText = { Text("1–$maxPosition") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onMove(position!! - 1) },
                enabled = valid
            ) { Text("Move") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun InsertPagesDialog(
    pageCount: Int,
    onDismiss: () -> Unit,
    onInsert: (Int) -> Unit
) {
    val maxPosition = pageCount + 1
    var value by remember(pageCount) { mutableStateOf(maxPosition.toString()) }
    val position = value.toIntOrNull()
    val valid = position != null && position in 1..maxPosition

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert scans") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Choose where the first new page should appear. Existing pages at that position and after it will move down."
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.filter(Char::isDigit) },
                    singleLine = true,
                    label = { Text("Insert at page") },
                    supportingText = { Text("1–$maxPosition") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onInsert(position!! - 1) },
                enabled = valid
            ) { Text("Scan") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun SelectedExportDialog(
    selectedCount: Int,
    defaultQuality: PdfQuality,
    textExportEnabled: Boolean,
    onDismiss: () -> Unit,
    onPdfSave: (PdfQuality) -> Unit,
    onPdfShare: (PdfQuality) -> Unit,
    onTextSave: () -> Unit,
    onTextShare: () -> Unit
) {
    var quality by remember(defaultQuality) { mutableStateOf(defaultQuality) }
    val options = listOf(
        PdfQuality.ORIGINAL to "Original",
        PdfQuality.HIGH to "High",
        PdfQuality.BALANCED to "Balanced",
        PdfQuality.SMALL to "Small"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export $selectedCount selected page${if (selectedCount == 1) "" else "s"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("PDF quality")
                options.forEach { (option, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { quality = option },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = quality == option,
                            onClick = { quality = option }
                        )
                        Text(label)
                    }
                }
                Text(
                    if (textExportEnabled) {
                        "Text export contains OCR from only the selected pages, in current page order."
                    } else {
                        "OCR text export is disabled for this scan mode."
                    },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Row {
                    TextButton(
                        onClick = onTextSave,
                        enabled = textExportEnabled
                    ) { Text("Save text") }
                    TextButton(
                        onClick = onTextShare,
                        enabled = textExportEnabled
                    ) { Text("Share text") }
                }
                Row {
                    TextButton(onClick = { onPdfSave(quality) }) { Text("Save PDF") }
                    TextButton(onClick = { onPdfShare(quality) }) { Text("Share PDF") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private val batchPresetLabels = listOf(
    ScanPreset.ORIGINAL to "Original",
    ScanPreset.AUTO to "Auto",
    ScanPreset.CLEAN to "Clean",
    ScanPreset.COLOR to "Color",
    ScanPreset.GRAYSCALE to "Grayscale",
    ScanPreset.BLACK_WHITE to "B&W",
    ScanPreset.NOTES to "Notes",
    ScanPreset.RECEIPT to "Receipt",
    ScanPreset.WHITEBOARD to "Whiteboard"
)
