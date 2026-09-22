package com.thiepn.scan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thiepn.scan.data.PageEntity
import com.thiepn.scan.data.PdfQuality
import com.thiepn.scan.data.ScanRepository
import com.thiepn.scan.util.shareFile
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    documentId: String,
    repository: ScanRepository,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val document by repository.observeDocument(documentId).collectAsStateWithLifecycle(initialValue = null)
    val pages by repository.observePages(documentId).collectAsStateWithLifecycle(initialValue = emptyList())
    val deletedPages by repository.observeDeletedPages(documentId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var protectOpen by remember { mutableStateOf(false) }
    var extractOpen by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var deletedPagesOpen by remember { mutableStateOf(false) }
    var pageDeleteCandidate by remember { mutableStateOf<PageEntity?>(null) }

    val doc = document
    if (doc == null) {
        Column(Modifier.fillMaxSize().padding(contentPadding).padding(24.dp)) {
            Text("Document not found")
            TextButton(onClick = onBack) { Text("Back") }
        }
        return
    }

    Scaffold(
        modifier = Modifier.padding(contentPadding),
        topBar = {
            TopAppBar(
                title = { Text(doc.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch { repository.setFavorite(doc.id, !doc.favorite) }
                    }) {
                        Icon(
                            if (doc.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (doc.favorite) "Remove favorite" else "Favorite"
                        )
                    }
                    IconButton(onClick = { renameOpen = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename")
                    }
                    IconButton(onClick = { protectOpen = true }) {
                        Icon(Icons.Default.Lock, contentDescription = "Protect PDF")
                    }
                    IconButton(onClick = { exportOpen = true }) {
                        Icon(Icons.Default.Share, contentDescription = "Export PDF")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    if (doc.processing) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.height(20.dp))
                            Text("Recognizing text…", style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                val file = repository.createTextExport(doc.id)
                                if (file != null) shareFile(context, file, "text/plain")
                                else onMessage("No text export available")
                            }
                        }) { Text("Share text") }
                        OutlinedButton(onClick = {
                            scope.launch { repository.setArchived(doc.id, !doc.archived) }
                        }) {
                            Icon(
                                if (doc.archived) Icons.Default.Restore else Icons.Default.Archive,
                                contentDescription = null
                            )
                            Text(if (doc.archived) " Restore" else " Archive")
                        }
                        OutlinedButton(onClick = { deleteOpen = true }) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Text(" Delete")
                        }
                    }
                    if (pages.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { extractOpen = true }) {
                            Icon(Icons.Default.ContentCut, contentDescription = null)
                            Text(" Extract pages")
                        }
                    }
                    if (deletedPages.isNotEmpty() && !doc.processing) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { deletedPagesOpen = true }) {
                            Icon(Icons.Default.RestoreFromTrash, contentDescription = null)
                            Text(" Deleted pages (${deletedPages.size})")
                        }
                    }
                }
            }

            itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                PageCard(
                    page = page,
                    displayNumber = index + 1,
                    canMoveUp = !doc.processing && index > 0,
                    canMoveDown = !doc.processing && index < pages.lastIndex,
                    canDelete = !doc.processing && pages.size > 1,
                    onMoveUp = {
                        scope.launch {
                            runCatching { repository.movePage(doc.id, page.id, -1) }
                                .onFailure { onMessage(it.message ?: "Could not move page") }
                        }
                    },
                    onMoveDown = {
                        scope.launch {
                            runCatching { repository.movePage(doc.id, page.id, 1) }
                                .onFailure { onMessage(it.message ?: "Could not move page") }
                        }
                    },
                    onDelete = { pageDeleteCandidate = page }
                )
            }

            if (pages.isEmpty()) {
                item {
                    Text(
                        if (doc.processing) "Preparing pages…" else "No page previews available for this document.",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (renameOpen) {
        RenameDialog(
            current = doc.title,
            onDismiss = { renameOpen = false },
            onSave = { title ->
                renameOpen = false
                scope.launch { repository.rename(doc.id, title) }
            }
        )
    }

    if (exportOpen) {
        ExportPdfDialog(
            onDismiss = { exportOpen = false },
            onExport = { quality ->
                exportOpen = false
                scope.launch {
                    runCatching { repository.createPdfExport(doc.id, quality = quality) }
                        .onSuccess { file ->
                            if (file != null) shareFile(context, file, "application/pdf")
                            else onMessage("PDF is not available yet")
                        }
                        .onFailure { onMessage(it.message ?: "Could not create PDF") }
                }
            }
        )
    }

    if (protectOpen) {
        ProtectPdfDialog(
            onDismiss = { protectOpen = false },
            onProtect = { password ->
                protectOpen = false
                scope.launch {
                    runCatching { repository.createPdfExport(doc.id, password) }
                        .onSuccess { file ->
                            if (file != null) shareFile(context, file, "application/pdf")
                            else onMessage("PDF is not available yet")
                        }
                        .onFailure { onMessage(it.message ?: "Could not protect PDF") }
                }
            }
        )
    }

    if (extractOpen) {
        ExtractPagesDialog(
            pageCount = pages.size,
            onDismiss = { extractOpen = false },
            onExtract = { range ->
                extractOpen = false
                scope.launch {
                    runCatching { repository.extractPages(doc.id, range) }
                        .onSuccess { file ->
                            if (file != null) shareFile(context, file, "application/pdf")
                            else onMessage("Could not create extracted PDF")
                        }
                        .onFailure { onMessage(it.message ?: "Could not extract pages") }
                }
            }
        )
    }

    if (deletedPagesOpen) {
        DeletedPagesDialog(
            pages = deletedPages,
            onDismiss = { deletedPagesOpen = false },
            onRestore = { page ->
                scope.launch {
                    runCatching { repository.restorePage(doc.id, page.id) }
                        .onSuccess { onMessage("Page restored") }
                        .onFailure { onMessage(it.message ?: "Could not restore page") }
                }
            }
        )
    }

    pageDeleteCandidate?.let { page ->
        val displayNumber = pages.indexOfFirst { it.id == page.id }.let { if (it >= 0) it + 1 else page.position + 1 }
        AlertDialog(
            onDismissRequest = { pageDeleteCandidate = null },
            title = { Text("Delete page $displayNumber?") },
            text = { Text("The page will be hidden from this document but kept recoverable under Deleted pages.") },
            confirmButton = {
                TextButton(onClick = {
                    pageDeleteCandidate = null
                    scope.launch {
                        runCatching { repository.softDeletePage(doc.id, page.id) }
                            .onSuccess { onMessage("Page moved to Deleted pages") }
                            .onFailure { onMessage(it.message ?: "Could not delete page") }
                    }
                }) { Text("Delete page") }
            },
            dismissButton = {
                TextButton(onClick = { pageDeleteCandidate = null }) { Text("Cancel") }
            }
        )
    }

    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("Delete document?") },
            text = { Text("This removes the local document and its stored page images.") },
            confirmButton = {
                Button(onClick = {
                    deleteOpen = false
                    scope.launch {
                        repository.delete(doc.id)
                        onDeleted()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PageCard(
    page: PageEntity,
    displayNumber: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    "Page $displayNumber",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Move page up")
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Move page down")
                }
                IconButton(onClick = onDelete, enabled = canDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete page")
                }
            }
            FileImage(
                path = page.imagePath,
                modifier = Modifier.fillMaxWidth().height(460.dp),
                contentDescription = "Page $displayNumber"
            )
            if (page.ocrText.isNotBlank()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Recognized text",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    SelectionContainer {
                        Text(
                            page.ocrText,
                            modifier = Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename document") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("Title") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(title) }, enabled = title.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


@Composable
private fun ProtectPdfDialog(
    onDismiss: () -> Unit,
    onProtect: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = password.length >= 4 && password == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Protect PDF") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Creates a new AES-256 password-protected copy. Your local document remains unchanged.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    singleLine = true,
                    label = { Text("Confirm password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = {
                        if (confirm.isNotEmpty() && password != confirm) {
                            Text("Passwords do not match")
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onProtect(password) },
                enabled = valid
            ) { Text("Create protected PDF") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


@Composable
private fun ExtractPagesDialog(
    pageCount: Int,
    onDismiss: () -> Unit,
    onExtract: (String) -> Unit
) {
    var range by remember(pageCount) {
        mutableStateOf(if (pageCount > 1) "1-$pageCount" else "1")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Extract pages") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Create a new PDF from selected pages. Use ranges such as 1-3,5,8.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = range,
                    onValueChange = { range = it },
                    singleLine = true,
                    label = { Text("Pages") },
                    supportingText = { Text("Available pages: 1-$pageCount") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onExtract(range) },
                enabled = range.isNotBlank()
            ) { Text("Extract PDF") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


@Composable
private fun ExportPdfDialog(
    onDismiss: () -> Unit,
    onExport: (PdfQuality) -> Unit
) {
    var quality by remember { mutableStateOf(PdfQuality.ORIGINAL) }
    val options = listOf(
        PdfQuality.ORIGINAL to ("Original" to "Best quality; preserves imported native PDFs"),
        PdfQuality.HIGH to ("High" to "Up to 3000 px per page, high JPEG quality"),
        PdfQuality.BALANCED to ("Balanced" to "Up to 2200 px per page; good default for sharing"),
        PdfQuality.SMALL to ("Small" to "Up to 1400 px per page; smallest files")
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export PDF") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "All scan exports remain searchable. Reduced-size modes rebuild imported PDFs as searchable image pages.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                options.forEach { (option, labels) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { quality = option }
                            .padding(vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = quality == option,
                            onClick = { quality = option }
                        )
                        Column(Modifier.padding(start = 6.dp)) {
                            Text(labels.first, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                labels.second,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onExport(quality) }) { Text("Export") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


@Composable
private fun DeletedPagesDialog(
    pages: List<PageEntity>,
    onDismiss: () -> Unit,
    onRestore: (PageEntity) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Deleted pages") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Deleted pages are excluded from search and exports but their original page images are retained.",
                    style = MaterialTheme.typography.bodyMedium
                )
                pages.forEach { page ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(
                            "Original page ${page.position + 1}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { onRestore(page) }) {
                            Text("Restore")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}
