package com.thiepn.scan.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thiepn.scan.data.DocumentEntity
import com.thiepn.scan.data.LibraryFilter
import com.thiepn.scan.data.ScanRepository
import com.thiepn.scan.util.shareFile
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: ScanRepository,
    contentPadding: PaddingValues,
    busy: Boolean,
    onOpenDocument: (String) -> Unit,
    onScan: () -> Unit,
    onImportPdf: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingMergedSavePath by rememberSaveable { mutableStateOf<String?>(null) }

    val saveMergedPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val path = pendingMergedSavePath
        pendingMergedSavePath = null
        if (uri != null && path != null) {
            scope.launch {
                runCatching { repository.saveExportToUri(File(path), uri) }
                    .onSuccess { onMessage("Merged PDF saved") }
                    .onFailure { onMessage(it.message ?: "Could not save merged PDF") }
            }
        }
    }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(LibraryFilter.ACTIVE) }
    var mergeOpen by remember { mutableStateOf(false) }
    var mergeBusy by remember { mutableStateOf(false) }
    var searchBusy by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<DocumentEntity>>(emptyList()) }
    val documentsFlow = remember(filter) { repository.observeDocuments(filter, "") }
    val liveDocuments by documentsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    LaunchedEffect(query, filter, liveDocuments.map { it.updatedAt }) {
        if (query.isBlank()) {
            searchBusy = false
            searchResults = emptyList()
        } else {
            searchBusy = true
            searchResults = runCatching {
                repository.searchDocuments(filter, query)
            }.getOrDefault(emptyList())
            searchBusy = false
        }
    }

    val documents = if (query.isBlank()) liveDocuments else searchResults
    val mergeCandidates = if (filter == LibraryFilter.TRASH) emptyList() else documents.filter { !it.processing }

    Scaffold(
        modifier = Modifier.padding(contentPadding),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Scan", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Local-first document scanner",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (mergeCandidates.size >= 2) {
                        IconButton(onClick = { mergeOpen = true }, enabled = !busy && !mergeBusy) {
                            Icon(Icons.Default.MergeType, contentDescription = "Merge PDFs")
                        }
                    }
                    IconButton(onClick = onImportPdf, enabled = !busy && !mergeBusy) {
                        Icon(Icons.Default.FileOpen, contentDescription = "Import PDF")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                expanded = true,
                icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                text = { Text("Scan") }
            )
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            Column(Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text("Search OCR, phrases, or prefixes*") },
                    supportingText = {
                        if (query.isNotBlank()) {
                            Text("Use quotes for an exact phrase; append * for prefix search")
                        }
                    }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = filter == LibraryFilter.ACTIVE,
                        onClick = { filter = LibraryFilter.ACTIVE },
                        label = { Text("Documents") }
                    )
                    FilterChip(
                        selected = filter == LibraryFilter.FAVORITES,
                        onClick = { filter = LibraryFilter.FAVORITES },
                        label = { Text("Favorites") }
                    )
                    FilterChip(
                        selected = filter == LibraryFilter.ARCHIVED,
                        onClick = { filter = LibraryFilter.ARCHIVED },
                        label = { Text("Archive") }
                    )
                    FilterChip(
                        selected = filter == LibraryFilter.TRASH,
                        onClick = { filter = LibraryFilter.TRASH },
                        label = { Text("Trash") }
                    )
                }

                Spacer(Modifier.height(8.dp))

                if (documents.isEmpty()) {
                    EmptyLibrary(query = query, filter = filter)
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 104.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(documents, key = { it.id }) { document ->
                            DocumentCard(
                                document = document,
                                repository = repository,
                                onClick = { onOpenDocument(document.id) }
                            )
                        }
                    }
                }
            }

            if (busy || mergeBusy || searchBusy) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }

    if (mergeOpen) {
        MergeDocumentsDialog(
            documents = mergeCandidates,
            onDismiss = { mergeOpen = false },
            onShare = { ids ->
                mergeOpen = false
                scope.launch {
                    mergeBusy = true
                    runCatching { repository.mergeDocuments(ids) }
                        .onSuccess { file ->
                            if (file != null) shareFile(context, file, "application/pdf")
                            else onMessage("Could not create merged PDF")
                        }
                        .onFailure { onMessage(it.message ?: "Could not merge PDFs") }
                    mergeBusy = false
                }
            },
            onSave = { ids ->
                mergeOpen = false
                scope.launch {
                    mergeBusy = true
                    runCatching { repository.mergeDocuments(ids) }
                        .onSuccess { file ->
                            if (file != null) {
                                pendingMergedSavePath = file.absolutePath
                                saveMergedPdfLauncher.launch(file.name)
                            } else {
                                onMessage("Could not create merged PDF")
                            }
                        }
                        .onFailure { onMessage(it.message ?: "Could not merge PDFs") }
                    mergeBusy = false
                }
            }
        )
    }
}

@Composable
private fun MergeDocumentsDialog(
    documents: List<DocumentEntity>,
    onDismiss: () -> Unit,
    onShare: (List<String>) -> Unit,
    onSave: (List<String>) -> Unit
) {
    var selected by remember(documents) { mutableStateOf<Set<String>>(emptySet()) }
    val toggle: (String) -> Unit = { id ->
        selected = if (id in selected) selected - id else selected + id
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge PDFs") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Choose at least two documents. They will be merged in the order shown.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                documents.forEach { document ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { toggle(document.id) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = document.id in selected,
                            onCheckedChange = { toggle(document.id) }
                        )
                        Column(Modifier.padding(start = 6.dp)) {
                            Text(
                                document.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${document.pageCount} page${if (document.pageCount == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val selectedIds = documents.filter { it.id in selected }.map { it.id }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onSave(selectedIds) },
                    enabled = selected.size >= 2
                ) { Text("Save") }
                TextButton(
                    onClick = { onShare(selectedIds) },
                    enabled = selected.size >= 2
                ) { Text("Share") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EmptyLibrary(query: String, filter: LibraryFilter) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (query.isNotBlank()) "No matches"
                else if (filter == LibraryFilter.TRASH) "Trash is empty"
                else "No documents yet",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                if (query.isNotBlank()) "Try a different search term."
                else if (filter == LibraryFilter.TRASH) "Documents you move to Trash can be restored from here."
                else "Tap Scan to create your first searchable document.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
private fun DocumentCard(
    document: DocumentEntity,
    repository: ScanRepository,
    onClick: () -> Unit
) {
    val coverFlow = remember(document.id) { repository.observeCoverPage(document.id) }
    val cover by coverFlow.collectAsStateWithLifecycle(initialValue = null)

    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val coverPage = cover
            if (coverPage != null) {
                Box(
                    modifier = Modifier.width(76.dp).height(104.dp)
                ) {
                    FileImage(
                        path = coverPage.imagePath,
                        modifier = Modifier.fillMaxSize(),
                        maxDecodeEdge = 480,
                        rotationDegrees = coverPage.rotationDegrees,
                        cropQuad = coverPage.cropQuad,
                        visualRecipe = coverPage.visualRecipe,
                        contentDescription = "Preview of ${document.title}"
                    )
                    if (document.favorite) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = "Favorite",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(18.dp)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier.width(76.dp).height(104.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        tint = if (document.favorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(
                    document.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append("${document.pageCount} page")
                        if (document.pageCount != 1) append('s')
                        append(" · ")
                        append(formatDate(document.updatedAt))
                        if (document.processing) append(" · Processing")
                        if (document.trashedAt != null) append(" · In Trash")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (document.ocrText.isNotBlank()) {
                    Text(
                        document.ocrText.replace('\n', ' '),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

private fun formatDate(time: Long): String = DateTimeFormatter.ofPattern("MMM d")
    .format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
