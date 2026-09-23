package com.thiepn.scan.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.thiepn.scan.data.ExtractionSchemaCodec
import com.thiepn.scan.data.ExtractionSchemaEntity
import com.thiepn.scan.data.OcrLayoutCodec
import com.thiepn.scan.data.PageEntity
import com.thiepn.scan.data.PageStructuredData
import com.thiepn.scan.data.PageStructuredDataCodec
import com.thiepn.scan.data.StructuredCell
import com.thiepn.scan.data.StructuredDataExport
import com.thiepn.scan.data.StructuredKeyValue
import com.thiepn.scan.data.StructuredTable
import java.util.UUID

@Composable
fun StructuredDataTools(
    tableCount: Int,
    fieldCount: Int,
    reviewCount: Int,
    staleCount: Int,
    schemaCount: Int,
    enabled: Boolean,
    hasData: Boolean,
    onExtract: () -> Unit,
    onReview: () -> Unit,
    onExport: () -> Unit,
    onSaveSchema: () -> Unit,
    onSchemas: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Document intelligence", style = MaterialTheme.typography.titleSmall)
            val summary = buildString {
                append(tableCount).append(" table")
                if (tableCount != 1) append("s")
                append(" · ").append(fieldCount).append(" field")
                if (fieldCount != 1) append("s")
                if (reviewCount > 0) append(" · ").append(reviewCount).append(" to review")
                if (staleCount > 0) {
                    append(" · ").append(staleCount).append(" stale page")
                    if (staleCount != 1) append("s")
                }
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (staleCount > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onExtract, enabled = enabled) { Text("Extract data") }
                OutlinedButton(
                    onClick = onReview,
                    enabled = enabled && hasData
                ) { Text("Review") }
                OutlinedButton(
                    onClick = onExport,
                    enabled = enabled && hasData
                ) { Text("Export") }
                OutlinedButton(
                    onClick = onSaveSchema,
                    enabled = enabled && hasData
                ) { Text("Save schema") }
                OutlinedButton(
                    onClick = onSchemas,
                    enabled = enabled && schemaCount > 0
                ) { Text("Schemas (" + schemaCount + ")") }
            }
        }
    }
}

@Composable
fun StructuredDataReviewDialog(
    page: PageEntity,
    pageNumber: Int,
    onDismiss: () -> Unit,
    onSave: (PageStructuredData) -> Unit,
    onRedetect: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?
) {
    val context = LocalContext.current
    val layout = remember(page.ocrLayout, page.ocrBaseLayout) {
        OcrLayoutCodec.decode(page.ocrLayout ?: page.ocrBaseLayout)
    }
    var data by remember(page.id, page.structuredData) {
        mutableStateOf(PageStructuredDataCodec.decode(page.structuredData))
    }
    var undo by remember(page.id) { mutableStateOf<List<PageStructuredData>>(emptyList()) }
    var redo by remember(page.id) { mutableStateOf<List<PageStructuredData>>(emptyList()) }
    var newLabel by remember(page.id) { mutableStateOf("") }
    var newValue by remember(page.id) { mutableStateOf("") }

    fun commit(next: PageStructuredData) {
        undo = undo + data
        data = next.normalized()
        redo = emptyList()
    }
    fun replaceKey(item: StructuredKeyValue) {
        commit(data.copy(keyValues = data.keyValues.map { if (it.id == item.id) item else it }))
    }
    fun replaceTable(table: StructuredTable) {
        commit(data.copy(tables = data.tables.map { if (it.id == table.id) table else it }))
    }

    val stale = data.isStale(layout)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Structured data · Page " + pageNumber) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 780.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onSave(data.normalized())
                            onPrevious?.invoke()
                        },
                        enabled = onPrevious != null
                    ) { Text("Previous") }
                    OutlinedButton(
                        onClick = {
                            onSave(data.normalized())
                            onNext?.invoke()
                        },
                        enabled = onNext != null
                    ) { Text("Next") }
                    OutlinedButton(onClick = onRedetect) {
                        Text(if (stale) "Re-extract stale data" else "Re-extract")
                    }
                    OutlinedButton(
                        onClick = {
                            undo.lastOrNull()?.let { previous ->
                                redo = redo + data
                                data = previous
                                undo = undo.dropLast(1)
                            }
                        },
                        enabled = undo.isNotEmpty()
                    ) { Text("Undo") }
                    OutlinedButton(
                        onClick = {
                            redo.lastOrNull()?.let { next ->
                                undo = undo + data
                                data = next
                                redo = redo.dropLast(1)
                            }
                        },
                        enabled = redo.isNotEmpty()
                    ) { Text("Redo") }
                }

                if (stale) {
                    Text(
                        "The OCR source changed after extraction. Review or re-extract this page.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Text(
                    "Key-value fields · " + data.keyValues.size,
                    style = MaterialTheme.typography.titleSmall
                )
                data.keyValues.forEach { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = item.label,
                                onValueChange = { value ->
                                    replaceKey(
                                        item.copy(
                                            label = value,
                                            key = StructuredKeyValue.normalizeKey(value),
                                            reviewed = true
                                        )
                                    )
                                },
                                label = { Text("Label") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = item.value,
                                onValueChange = { value ->
                                    replaceKey(item.copy(value = value, reviewed = true))
                                },
                                label = { Text("Value") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 1,
                                maxLines = 4
                            )
                            Text(
                                (item.confidence * 100).toInt().toString() +
                                    "% confidence · " + item.source,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Checkbox(
                                    checked = item.reviewed,
                                    onCheckedChange = { replaceKey(item.copy(reviewed = it)) }
                                )
                                Text("Reviewed", modifier = Modifier.padding(top = 12.dp))
                                TextButton(
                                    onClick = {
                                        commit(
                                            data.copy(
                                                keyValues = data.keyValues.filterNot { it.id == item.id }
                                            )
                                        )
                                    }
                                ) { Text("Delete") }
                            }
                        }
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = newLabel,
                            onValueChange = { newLabel = it.take(180) },
                            label = { Text("New field label") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newValue,
                            onValueChange = { newValue = it.take(4000) },
                            label = { Text("New field value") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = {
                                if (newLabel.isNotBlank()) {
                                    val key = StructuredKeyValue.normalizeKey(newLabel)
                                    commit(
                                        data.copy(
                                            keyValues = data.keyValues + StructuredKeyValue(
                                                id = UUID.randomUUID().toString(),
                                                key = key,
                                                label = newLabel.trim(),
                                                value = newValue,
                                                confidence = 1f,
                                                reviewed = true,
                                                source = "MANUAL"
                                            )
                                        )
                                    )
                                    newLabel = ""
                                    newValue = ""
                                }
                            },
                            enabled = newLabel.isNotBlank()
                        ) { Text("Add field") }
                    }
                }

                Text(
                    "Tables · " + data.tables.size,
                    style = MaterialTheme.typography.titleSmall
                )
                data.tables.forEach { table ->
                    StructuredTableEditor(
                        table = table,
                        onChange = ::replaceTable,
                        onDelete = {
                            commit(
                                data.copy(
                                    tables = data.tables.filterNot { it.id == table.id }
                                )
                            )
                        },
                        onCopy = {
                            val clipboard = context.getSystemService(
                                Context.CLIPBOARD_SERVICE
                            ) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    table.title,
                                    StructuredDataExport.tableAsTsv(table)
                                )
                            )
                        }
                    )
                }

                OutlinedButton(
                    onClick = {
                        val cells = listOf(
                            StructuredCell(0, 0, "Column 1", 1f, true, null),
                            StructuredCell(0, 1, "Column 2", 1f, true, null),
                            StructuredCell(1, 0, "", 1f, true, null),
                            StructuredCell(1, 1, "", 1f, true, null)
                        )
                        commit(
                            data.copy(
                                tables = data.tables + StructuredTable(
                                    id = UUID.randomUUID().toString(),
                                    title = "Manual table",
                                    rowCount = 2,
                                    columnCount = 2,
                                    cells = cells,
                                    confidence = 1f,
                                    reviewed = true,
                                    source = "MANUAL"
                                )
                            )
                        )
                    }
                ) { Text("Add 2×2 table") }

                if (data.reviewCount() > 0) {
                    val count = data.reviewCount()
                    Text(
                        count.toString() + " low-confidence item" +
                            if (count == 1) "" else "s" +
                            " still need review",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(data.normalized()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun StructuredTableEditor(
    table: StructuredTable,
    onChange: (StructuredTable) -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit
) {
    fun updateCell(row: Int, col: Int, value: String) {
        val existing = table.cell(row, col)
        val cell = (existing ?: StructuredCell(row, col, "", 1f, true, null))
            .copy(text = value, reviewed = true)
        onChange(
            table.copy(
                cells = table.cells.filterNot { it.row == row && it.column == col } + cell
            )
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = table.title,
                onValueChange = { onChange(table.copy(title = it, reviewed = true)) },
                label = { Text("Table title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Text(
                table.rowCount.toString() + "×" + table.columnCount +
                    " · " + (table.confidence * 100).toInt() +
                    "% confidence · " + table.source,
                style = MaterialTheme.typography.bodySmall
            )
            Column(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (row in 0 until table.rowCount) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (col in 0 until table.columnCount) {
                            val cell = table.cell(row, col)
                            OutlinedTextField(
                                value = cell?.text.orEmpty(),
                                onValueChange = { updateCell(row, col, it) },
                                label = {
                                    if (
                                        cell != null &&
                                        cell.confidence < 0.82f &&
                                        !cell.reviewed
                                    ) {
                                        Text((cell.confidence * 100).toInt().toString() + "%")
                                    }
                                },
                                modifier = Modifier.width(150.dp),
                                minLines = 1,
                                maxLines = 3
                            )
                        }
                    }
                }
            }
            SelectionContainer {
                Text(
                    StructuredDataExport.tableAsTsv(table),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onCopy) { Text("Copy TSV") }
                OutlinedButton(
                    onClick = {
                        val row = table.rowCount
                        val additions = (0 until table.columnCount).map { col ->
                            StructuredCell(row, col, "", 1f, true, null)
                        }
                        onChange(
                            table.copy(
                                rowCount = row + 1,
                                cells = table.cells + additions,
                                reviewed = true
                            )
                        )
                    }
                ) { Text("Add row") }
                OutlinedButton(
                    onClick = {
                        val col = table.columnCount
                        val additions = (0 until table.rowCount).map { row ->
                            StructuredCell(row, col, "", 1f, true, null)
                        }
                        onChange(
                            table.copy(
                                columnCount = col + 1,
                                cells = table.cells + additions,
                                reviewed = true
                            )
                        )
                    }
                ) { Text("Add column") }
                OutlinedButton(
                    onClick = {
                        val row = table.rowCount - 1
                        onChange(
                            table.copy(
                                rowCount = row,
                                cells = table.cells.filterNot { it.row == row },
                                reviewed = true
                            )
                        )
                    },
                    enabled = table.rowCount > 1
                ) { Text("Remove last row") }
                OutlinedButton(
                    onClick = {
                        val col = table.columnCount - 1
                        onChange(
                            table.copy(
                                columnCount = col,
                                cells = table.cells.filterNot { it.column == col },
                                reviewed = true
                            )
                        )
                    },
                    enabled = table.columnCount > 1
                ) { Text("Remove last column") }
                OutlinedButton(
                    onClick = {
                        onChange(
                            table.copy(
                                reviewed = true,
                                cells = table.cells.map { it.copy(reviewed = true) }
                            )
                        )
                    }
                ) { Text("Mark reviewed") }
                TextButton(onClick = onDelete) { Text("Delete table") }
            }
        }
    }
}

@Composable
fun StructuredExportDialog(
    onDismiss: () -> Unit,
    onCsv: () -> Unit,
    onXlsx: () -> Unit,
    onJson: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export structured data") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onXlsx,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Excel workbook (.xlsx)") }
                OutlinedButton(
                    onClick = onCsv,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("CSV (.csv)") }
                OutlinedButton(
                    onClick = onJson,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("JSON (.json)") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ExtractionSchemaNameDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save extraction schema") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text("Schema name") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ExtractionSchemaPickerDialog(
    schemas: List<ExtractionSchemaEntity>,
    onDismiss: () -> Unit,
    onApply: (ExtractionSchemaEntity) -> Unit,
    onDelete: (ExtractionSchemaEntity) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Extraction schemas") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                schemas.forEach { schema ->
                    val definition = remember(schema.definition) {
                        ExtractionSchemaCodec.decode(schema.definition)
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(schema.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                (definition?.fields?.size ?: 0).toString() +
                                    " field definitions · " +
                                    (definition?.tables?.size ?: 0) +
                                    " table definitions",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onApply(schema) }) { Text("Apply") }
                                TextButton(onClick = { onDelete(schema) }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
