package com.thiepn.scan.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.thiepn.scan.data.DocumentEntity
import com.thiepn.scan.data.PageAssemblyMetadata
import com.thiepn.scan.data.PageNumberFormat
import com.thiepn.scan.data.PageNumberPosition
import com.thiepn.scan.data.PageNumberStyle
import com.thiepn.scan.data.PublishingSettings

@Composable
fun AssemblyPublishingTools(
    pageCount: Int,
    enabled: Boolean,
    onInsertPdf: (Int) -> Unit,
    onBlankPage: (Int) -> Unit,
    onDividerPage: (Int) -> Unit,
    onTransfer: (Int) -> Unit,
    onPublishing: () -> Unit
) {
    var afterPage by remember(pageCount) {
        mutableStateOf(pageCount.toString())
    }
    val insertIndex = afterPage.toIntOrNull()?.coerceIn(0, pageCount) ?: pageCount

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Assembly & publishing",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                "Insert after page 0 for the beginning, or after the last page for the end.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = afterPage,
                onValueChange = { value ->
                    afterPage = value.filter(Char::isDigit).take(4)
                },
                label = { Text("Insert after page (0–$pageCount)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number
                )
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onInsertPdf(insertIndex) },
                    enabled = enabled
                ) { Text("Insert PDF") }
                OutlinedButton(
                    onClick = { onBlankPage(insertIndex) },
                    enabled = enabled
                ) { Text("Blank page") }
                OutlinedButton(
                    onClick = { onDividerPage(insertIndex) },
                    enabled = enabled
                ) { Text("Divider") }
                OutlinedButton(
                    onClick = { onTransfer(insertIndex) },
                    enabled = enabled
                ) { Text("From document") }
                OutlinedButton(
                    onClick = onPublishing,
                    enabled = enabled
                ) { Text("Publishing") }
            }
        }
    }
}

@Composable
fun DividerPageDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var subtitle by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert divider page") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(240) },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = subtitle,
                    onValueChange = { subtitle = it.take(500) },
                    label = { Text("Subtitle (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 4
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(title.trim(), subtitle.trim()) },
                enabled = title.isNotBlank()
            ) { Text("Insert") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun TransferPagesDialog(
    documents: List<DocumentEntity>,
    currentDocumentId: String,
    targetInsertIndex: Int,
    onDismiss: () -> Unit,
    onTransfer: (
        sourceDocumentId: String,
        range: String,
        insertIndex: Int,
        move: Boolean
    ) -> Unit
) {
    val candidates = documents.filter { it.id != currentDocumentId }
    var selectedId by remember(candidates) {
        mutableStateOf(candidates.firstOrNull()?.id.orEmpty())
    }
    var range by remember { mutableStateOf("1") }
    var move by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transfer pages") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Pages are copied with OCR, text edits, markup, form data, structured data, labels, and bookmarks.",
                    style = MaterialTheme.typography.bodySmall
                )
                candidates.forEach { document ->
                    FilterChip(
                        selected = selectedId == document.id,
                        onClick = { selectedId = document.id },
                        label = {
                            Text(
                                document.title + " · " +
                                    document.pageCount + " page" +
                                    if (document.pageCount == 1) "" else "s"
                            )
                        }
                    )
                }
                if (candidates.isEmpty()) {
                    Text("No other active documents are available.")
                }
                OutlinedTextField(
                    value = range,
                    onValueChange = { range = it.take(120) },
                    label = { Text("Pages (for example 1-3, 5)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(
                        checked = move,
                        onCheckedChange = { move = it }
                    )
                    Text(
                        "Move instead of copy",
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                Text(
                    "Insert position: after page $targetInsertIndex",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onTransfer(
                        selectedId,
                        range.trim(),
                        targetInsertIndex,
                        move
                    )
                },
                enabled = selectedId.isNotBlank() && range.isNotBlank()
            ) { Text(if (move) "Move" else "Copy") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun PageAssemblyMetadataDialog(
    initial: PageAssemblyMetadata,
    onDismiss: () -> Unit,
    onSave: (PageAssemblyMetadata) -> Unit
) {
    var label by remember(initial) { mutableStateOf(initial.label) }
    var bookmark by remember(initial) {
        mutableStateOf(initial.bookmarkTitle)
    }
    var level by remember(initial) {
        mutableStateOf(initial.bookmarkLevel)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page label & bookmark") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(120) },
                    label = { Text("Page label") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = bookmark,
                    onValueChange = { bookmark = it.take(180) },
                    label = { Text("Bookmark title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    "Bookmark level",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    (0..3).forEach { option ->
                        FilterChip(
                            selected = level == option,
                            onClick = { level = option },
                            label = { Text("Level $option") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        initial.copy(
                            label = label,
                            bookmarkTitle = bookmark,
                            bookmarkLevel = level
                        ).normalized()
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun PublishingSettingsDialog(
    initial: PublishingSettings,
    documentTitle: String,
    onDismiss: () -> Unit,
    onSave: (PublishingSettings) -> Unit
) {
    var headerLeft by remember(initial) { mutableStateOf(initial.headerLeft) }
    var headerCenter by remember(initial) {
        mutableStateOf(initial.headerCenter)
    }
    var headerRight by remember(initial) {
        mutableStateOf(initial.headerRight)
    }
    var footerLeft by remember(initial) { mutableStateOf(initial.footerLeft) }
    var footerCenter by remember(initial) {
        mutableStateOf(initial.footerCenter)
    }
    var footerRight by remember(initial) {
        mutableStateOf(initial.footerRight)
    }
    var numberPosition by remember(initial) {
        mutableStateOf(initial.pageNumberPosition)
    }
    var numberStyle by remember(initial) {
        mutableStateOf(initial.pageNumberStyle)
    }
    var numberFormat by remember(initial) {
        mutableStateOf(initial.pageNumberFormat)
    }
    var numberStart by remember(initial) {
        mutableStateOf(initial.pageNumberStart.toString())
    }
    var watermark by remember(initial) {
        mutableStateOf(initial.watermarkText)
    }
    var watermarkOpacity by remember(initial) {
        mutableStateOf(initial.watermarkOpacity.toString())
    }
    var watermarkAngle by remember(initial) {
        mutableStateOf(initial.watermarkAngle.toString())
    }
    var metadataTitle by remember(initial, documentTitle) {
        mutableStateOf(initial.metadataTitle.ifBlank { documentTitle })
    }
    var author by remember(initial) {
        mutableStateOf(initial.metadataAuthor)
    }
    var subject by remember(initial) {
        mutableStateOf(initial.metadataSubject)
    }
    var keywords by remember(initial) {
        mutableStateOf(initial.metadataKeywords)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Publishing settings") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 760.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Placeholders: {title}, {page}, {pages}, {label}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Header", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    headerLeft,
                    { headerLeft = it.take(300) },
                    label = { Text("Left") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    headerCenter,
                    { headerCenter = it.take(300) },
                    label = { Text("Center") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    headerRight,
                    { headerRight = it.take(300) },
                    label = { Text("Right") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Footer", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    footerLeft,
                    { footerLeft = it.take(300) },
                    label = { Text("Left") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    footerCenter,
                    { footerCenter = it.take(300) },
                    label = { Text("Center") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    footerRight,
                    { footerRight = it.take(300) },
                    label = { Text("Right") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "Page number position",
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PageNumberPosition.entries.forEach { option ->
                        FilterChip(
                            selected = numberPosition == option,
                            onClick = { numberPosition = option },
                            label = {
                                Text(
                                    option.name
                                        .lowercase()
                                        .replace('_', ' ')
                                )
                            }
                        )
                    }
                }

                Text(
                    "Page number style",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PageNumberStyle.entries.forEach { option ->
                        FilterChip(
                            selected = numberStyle == option,
                            onClick = { numberStyle = option },
                            label = { Text(option.name.lowercase()) }
                        )
                    }
                }

                Text(
                    "Page number format",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PageNumberFormat.entries.forEach { option ->
                        FilterChip(
                            selected = numberFormat == option,
                            onClick = { numberFormat = option },
                            label = {
                                Text(
                                    option.name
                                        .lowercase()
                                        .replace('_', ' ')
                                )
                            }
                        )
                    }
                }
                OutlinedTextField(
                    value = numberStart,
                    onValueChange = {
                        numberStart = it.filter { ch ->
                            ch.isDigit() || ch == '-'
                        }.take(7)
                    },
                    label = { Text("Starting number") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    )
                )

                Text("Watermark", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    watermark,
                    { watermark = it.take(300) },
                    label = { Text("Watermark text") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    watermarkOpacity,
                    { watermarkOpacity = it.take(5) },
                    label = { Text("Opacity (0.04–0.75)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    watermarkAngle,
                    { watermarkAngle = it.take(6) },
                    label = { Text("Angle (-75–75)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text(
                    "PDF metadata",
                    style = MaterialTheme.typography.titleSmall
                )
                OutlinedTextField(
                    metadataTitle,
                    { metadataTitle = it.take(300) },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    author,
                    { author = it.take(300) },
                    label = { Text("Author") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    subject,
                    { subject = it.take(500) },
                    label = { Text("Subject") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    keywords,
                    { keywords = it.take(500) },
                    label = { Text("Keywords") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        PublishingSettings(
                            headerLeft = headerLeft,
                            headerCenter = headerCenter,
                            headerRight = headerRight,
                            footerLeft = footerLeft,
                            footerCenter = footerCenter,
                            footerRight = footerRight,
                            pageNumberPosition = numberPosition,
                            pageNumberStyle = numberStyle,
                            pageNumberFormat = numberFormat,
                            pageNumberStart = numberStart.toIntOrNull() ?: 1,
                            watermarkText = watermark,
                            watermarkOpacity = watermarkOpacity.toFloatOrNull()
                                ?: 0.14f,
                            watermarkAngle = watermarkAngle.toFloatOrNull()
                                ?: -35f,
                            metadataTitle = metadataTitle,
                            metadataAuthor = author,
                            metadataSubject = subject,
                            metadataKeywords = keywords
                        ).normalized()
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
