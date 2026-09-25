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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thiepn.scan.data.DocumentEntity
import com.thiepn.scan.data.DocumentSecuritySettingsCodec
import com.thiepn.scan.data.DocumentTagCrossRef
import com.thiepn.scan.data.DocumentType
import com.thiepn.scan.data.FolderEntity
import com.thiepn.scan.data.LibraryFilter
import com.thiepn.scan.data.LibrarySort
import com.thiepn.scan.data.ScanMode
import com.thiepn.scan.data.ScanRepository
import com.thiepn.scan.data.SmartCollection
import com.thiepn.scan.data.TagEntity
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
    onScan: (ScanMode, Boolean) -> Unit,
    onImportPdf: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingMergedSavePath by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreBackupUri by remember {
        mutableStateOf<android.net.Uri?>(null)
    }
    var restoreBackupDialogOpen by remember {
        mutableStateOf(false)
    }

    val restoreBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            restoreBackupUri = uri
            restoreBackupDialogOpen = true
        }
    }

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
    var organizationFilter by remember {
        mutableStateOf(OrganizationFilterState())
    }
    var organizationFilterOpen by remember { mutableStateOf(false) }
    var folderManagerOpen by remember { mutableStateOf(false) }
    var tagManagerOpen by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedDocumentIds by remember { mutableStateOf(emptySet<String>()) }
    var bulkOrganizeOpen by remember { mutableStateOf(false) }
    var automationCenterOpen by remember { mutableStateOf(false) }
    var bulkAutomationOpen by remember { mutableStateOf(false) }
    var scanModeOpen by remember { mutableStateOf(false) }

    val documentsFlow = remember(filter) { repository.observeDocuments(filter, "") }
    val liveDocuments by documentsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val folders by repository.observeFolders().collectAsStateWithLifecycle(initialValue = emptyList())
    val tags by repository.observeTags().collectAsStateWithLifecycle(initialValue = emptyList())
    val documentTags by repository.observeDocumentTags()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val vaultState by repository.observeVaultState()
        .collectAsStateWithLifecycle()

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

    LaunchedEffect(liveDocuments.map { it.id }) {
        val validIds = liveDocuments.map { it.id }.toSet()
        selectedDocumentIds = selectedDocumentIds.filterTo(linkedSetOf()) { it in validIds }
        if (selectedDocumentIds.isEmpty() && selectionMode && liveDocuments.isEmpty()) {
            selectionMode = false
        }
    }

    LaunchedEffect(folders.map { it.id }, tags.map { it.id }) {
        val folderIds = folders.map { it.id }.toSet()
        val tagIds = tags.map { it.id }.toSet()
        if (
            organizationFilter.folderId != null &&
            organizationFilter.folderId !in folderIds
        ) {
            organizationFilter = organizationFilter.copy(folderId = null)
        }
        if (
            organizationFilter.tagId != null &&
            organizationFilter.tagId !in tagIds
        ) {
            organizationFilter = organizationFilter.copy(tagId = null)
        }
    }

    val sourceDocuments = if (query.isBlank()) liveDocuments else searchResults
    val tagIdsByDocument = documentTags.groupBy { it.documentId }
        .mapValues { (_, links) -> links.map { it.tagId }.toSet() }
    val selectedFolderIds = organizationFilter.folderId?.let {
        folderAndDescendantIds(it, folders)
    }
    val recentThreshold = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L

    val filteredDocuments = sourceDocuments.filter { document ->
        val smartMatch = when (organizationFilter.smartCollection) {
            SmartCollection.ALL -> true
            SmartCollection.RECENT -> document.updatedAt >= recentThreshold
            SmartCollection.UNFILED -> document.folderId == null
            SmartCollection.NEEDS_REVIEW -> document.needsReview
        }
        val folderMatch = selectedFolderIds == null || document.folderId in selectedFolderIds
        val tagMatch = organizationFilter.tagId == null ||
            organizationFilter.tagId in tagIdsByDocument[document.id].orEmpty()
        val typeMatch = organizationFilter.documentType == null ||
            DocumentType.fromStored(document.documentType) == organizationFilter.documentType
        smartMatch && folderMatch && tagMatch && typeMatch
    }

    val documents = when (organizationFilter.sort) {
        LibrarySort.RELEVANCE -> if (query.isNotBlank()) filteredDocuments
            else filteredDocuments.sortedByDescending { it.updatedAt }
        LibrarySort.UPDATED_DESC -> filteredDocuments.sortedByDescending { it.updatedAt }
        LibrarySort.CREATED_DESC -> filteredDocuments.sortedByDescending { it.createdAt }
        LibrarySort.TITLE_ASC -> filteredDocuments.sortedBy { it.title.lowercase() }
        LibrarySort.TITLE_DESC -> filteredDocuments.sortedByDescending { it.title.lowercase() }
        LibrarySort.PAGE_COUNT_DESC -> filteredDocuments.sortedByDescending { it.pageCount }
        LibrarySort.PAGE_COUNT_ASC -> filteredDocuments.sortedBy { it.pageCount }
    }
    val mergeCandidates = if (filter == LibraryFilter.TRASH) {
        emptyList()
    } else {
        documents.filter {
            !it.processing &&
                it.id !in vaultState.lockedDocumentIds
        }
    }

    Scaffold(
        modifier = Modifier.padding(contentPadding),
        topBar = {
            TopAppBar(
                title = {
                    if (selectionMode) {
                        Text("${selectedDocumentIds.size} selected")
                    } else {
                        Column {
                            Text("Scan", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Local-first document scanner",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = {
                            selectionMode = false
                            selectedDocumentIds = emptySet()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection")
                        }
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(
                            onClick = {
                                selectedDocumentIds = if (
                                    selectedDocumentIds.size == documents.size
                                ) {
                                    emptySet()
                                } else {
                                    documents.map { it.id }.toSet()
                                }
                            },
                            enabled = documents.isNotEmpty()
                        ) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select all documents")
                        }
                        IconButton(
                            onClick = { bulkOrganizeOpen = true },
                            enabled = selectedDocumentIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Label, contentDescription = "Organize selected documents")
                        }
                        IconButton(
                            onClick = { bulkAutomationOpen = true },
                            enabled = selectedDocumentIds.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = "Run workflow on selected documents"
                            )
                        }
                        IconButton(
                            onClick = {
                                val ids = selectedDocumentIds.toList()
                                val favorite = filter != LibraryFilter.FAVORITES
                                scope.launch {
                                    runCatching {
                                        repository.setDocumentsFavorite(ids, favorite)
                                    }
                                        .onSuccess {
                                            selectionMode = false
                                            selectedDocumentIds = emptySet()
                                            onMessage(
                                                if (favorite) "Documents favorited"
                                                else "Documents removed from favorites"
                                            )
                                        }
                                        .onFailure {
                                            onMessage(it.message ?: "Could not update favorites")
                                        }
                                }
                            },
                            enabled = selectedDocumentIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Favorite, contentDescription = "Toggle favorites")
                        }
                        IconButton(
                            onClick = {
                                val ids = selectedDocumentIds.toList()
                                val archived = filter != LibraryFilter.ARCHIVED
                                scope.launch {
                                    runCatching {
                                        repository.setDocumentsArchived(ids, archived)
                                    }
                                        .onSuccess {
                                            selectionMode = false
                                            selectedDocumentIds = emptySet()
                                            onMessage(
                                                if (archived) "Documents archived"
                                                else "Documents restored from archive"
                                            )
                                        }
                                        .onFailure {
                                            onMessage(it.message ?: "Could not update archive")
                                        }
                                }
                            },
                            enabled = selectedDocumentIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Archive, contentDescription = "Toggle archive")
                        }
                    } else {
                        IconButton(onClick = { organizationFilterOpen = true }) {
                            Icon(Icons.Default.FilterList, contentDescription = "Sort and filter")
                        }
                        IconButton(onClick = { folderManagerOpen = true }) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = "Manage folders")
                        }
                        IconButton(onClick = { tagManagerOpen = true }) {
                            Icon(Icons.Default.Label, contentDescription = "Manage tags")
                        }
                        IconButton(onClick = { automationCenterOpen = true }) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = "Automation Center"
                            )
                        }
                        IconButton(
                            onClick = {
                                selectionMode = true
                                selectedDocumentIds = emptySet()
                            },
                            enabled = documents.isNotEmpty() && filter != LibraryFilter.TRASH
                        ) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select documents")
                        }
                        IconButton(
                            onClick = {
                                restoreBackupLauncher.launch(
                                    arrayOf(
                                        "application/octet-stream",
                                        "application/zip"
                                    )
                                )
                            },
                            enabled = !busy && !mergeBusy
                        ) {
                            Icon(
                                Icons.Default.SettingsBackupRestore,
                                contentDescription = "Restore encrypted backup"
                            )
                        }
                        if (mergeCandidates.size >= 2) {
                            IconButton(
                                onClick = { mergeOpen = true },
                                enabled = !busy && !mergeBusy
                            ) {
                                Icon(Icons.Default.MergeType, contentDescription = "Merge PDFs")
                            }
                        }
                        IconButton(onClick = onImportPdf, enabled = !busy && !mergeBusy) {
                            Icon(Icons.Default.FileOpen, contentDescription = "Import PDF")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (!selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { scanModeOpen = true },
                    expanded = true,
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                    text = { Text("Scan") }
                )
            }
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

                if (filter != LibraryFilter.TRASH) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SmartCollection.entries.forEach { smart ->
                            FilterChip(
                                selected = organizationFilter.smartCollection == smart,
                                onClick = {
                                    organizationFilter = organizationFilter.copy(
                                        smartCollection = smart
                                    )
                                },
                                label = { Text(smart.label) }
                            )
                        }
                    }
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
                            val documentTagList = tagIdsByDocument[document.id]
                                .orEmpty()
                                .mapNotNull { id -> tags.firstOrNull { it.id == id } }
                            DocumentCard(
                                document = document,
                                repository = repository,
                                locked =
                                    document.id in
                                        vaultState.lockedDocumentIds,
                                folders = folders,
                                tags = documentTagList,
                                selectionMode = selectionMode,
                                selected = document.id in selectedDocumentIds,
                                onToggleSelected = {
                                    selectedDocumentIds = if (
                                        document.id in selectedDocumentIds
                                    ) {
                                        selectedDocumentIds - document.id
                                    } else {
                                        selectedDocumentIds + document.id
                                    }
                                },
                                onAcceptSuggestion = {
                                    scope.launch {
                                        runCatching {
                                            repository.acceptSuggestedType(document.id)
                                        }
                                            .onSuccess {
                                                onMessage("Document type updated")
                                            }
                                            .onFailure {
                                                onMessage(
                                                    it.message ?: "Could not update document type"
                                                )
                                            }
                                    }
                                },
                                onClick = {
                                    if (selectionMode) {
                                        selectedDocumentIds = if (
                                            document.id in selectedDocumentIds
                                        ) {
                                            selectedDocumentIds - document.id
                                        } else {
                                            selectedDocumentIds + document.id
                                        }
                                    } else {
                                        onOpenDocument(document.id)
                                    }
                                }
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

    if (
        restoreBackupDialogOpen &&
        restoreBackupUri != null
    ) {
        RestoreSecureBackupDialog(
            onDismiss = {
                restoreBackupDialogOpen = false
                restoreBackupUri = null
            },
            onRestore = { password ->
                val uri = restoreBackupUri
                restoreBackupDialogOpen = false
                restoreBackupUri = null
                if (uri != null) {
                    scope.launch {
                        runCatching {
                            repository.restoreSecureBackup(
                                uri,
                                password
                            )
                        }
                            .onSuccess { restoredId ->
                                onMessage(
                                    "Encrypted backup restored"
                                )
                                onOpenDocument(restoredId)
                            }
                            .onFailure {
                                onMessage(
                                    it.message
                                        ?: "Could not restore encrypted backup"
                                )
                            }
                    }
                } else {
                    password.fill('\u0000')
                }
            }
        )
    }

    if (scanModeOpen) {
        ScanModeChooserDialog(
            onDismiss = { scanModeOpen = false },
            onChoose = { mode, rapid ->
                scanModeOpen = false
                onScan(mode, rapid)
            }
        )
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

    if (organizationFilterOpen) {
        OrganizationFilterDialog(
            state = organizationFilter,
            folders = folders,
            tags = tags,
            queryActive = query.isNotBlank(),
            onDismiss = { organizationFilterOpen = false },
            onApply = { state ->
                organizationFilter = state
                organizationFilterOpen = false
            }
        )
    }

    if (folderManagerOpen) {
        FolderManagerDialog(
            folders = folders,
            onDismiss = { folderManagerOpen = false },
            onCreate = { name, parentId ->
                scope.launch {
                    runCatching { repository.createFolder(name, parentId) }
                        .onSuccess { onMessage("Folder created") }
                        .onFailure {
                            onMessage(it.message ?: "Could not create folder")
                        }
                }
            },
            onRename = { folderId, name ->
                scope.launch {
                    runCatching { repository.renameFolder(folderId, name) }
                        .onSuccess { onMessage("Folder renamed") }
                        .onFailure {
                            onMessage(it.message ?: "Could not rename folder")
                        }
                }
            },
            onDelete = { folderId ->
                scope.launch {
                    runCatching { repository.deleteFolder(folderId) }
                        .onSuccess { onMessage("Folder deleted; contents preserved") }
                        .onFailure {
                            onMessage(it.message ?: "Could not delete folder")
                        }
                }
            }
        )
    }

    if (tagManagerOpen) {
        TagManagerDialog(
            tags = tags,
            onDismiss = { tagManagerOpen = false },
            onCreate = { name ->
                scope.launch {
                    runCatching { repository.createTag(name) }
                        .onSuccess { onMessage("Tag ready") }
                        .onFailure { onMessage(it.message ?: "Could not create tag") }
                }
            },
            onDelete = { tagId ->
                scope.launch {
                    runCatching { repository.deleteTag(tagId) }
                        .onSuccess { onMessage("Tag deleted") }
                        .onFailure { onMessage(it.message ?: "Could not delete tag") }
                }
            }
        )
    }

    if (automationCenterOpen) {
        WorkflowAutomationDialog(
            repository = repository,
            folders = folders,
            tags = tags,
            onDismiss = { automationCenterOpen = false },
            onMessage = onMessage
        )
    }

    if (bulkAutomationOpen) {
        ProcessingPresetPickerDialog(
            repository = repository,
            selectedDocumentIds = selectedDocumentIds.toList(),
            onDismiss = {
                bulkAutomationOpen = false
                selectionMode = false
                selectedDocumentIds = emptySet()
            },
            onMessage = onMessage
        )
    }

    if (bulkOrganizeOpen) {
        BulkOrganizeDialog(
            selectedCount = selectedDocumentIds.size,
            folders = folders,
            tags = tags,
            onDismiss = { bulkOrganizeOpen = false },
            onApply = { change ->
                bulkOrganizeOpen = false
                val ids = selectedDocumentIds.toList()
                scope.launch {
                    runCatching {
                        if (change.changeFolder) {
                            repository.setDocumentFolder(ids, change.folderId)
                        }
                        if (change.changeType) {
                            repository.setDocumentType(
                                ids,
                                requireNotNull(change.documentType)
                            )
                        }
                        if (change.changeTags) {
                            repository.replaceDocumentTags(ids, change.tagIds)
                        }
                        if (change.changeReview) {
                            repository.setDocumentsNeedsReview(
                                ids,
                                change.needsReview
                            )
                        }
                    }
                        .onSuccess {
                            selectionMode = false
                            selectedDocumentIds = emptySet()
                            onMessage("Documents organized")
                        }
                        .onFailure {
                            onMessage(it.message ?: "Could not organize documents")
                        }
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
    locked: Boolean,
    folders: List<FolderEntity>,
    tags: List<TagEntity>,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    onAcceptSuggestion: () -> Unit,
    onClick: () -> Unit
) {
    val coverFlow = remember(document.id) {
        repository.observeCoverPage(document.id)
    }
    val cover by coverFlow.collectAsStateWithLifecycle(
        initialValue = null
    )
    val security = DocumentSecuritySettingsCodec.decode(
        document.securityRecipe
    )
    val hideLockedMetadata =
        locked && security.hideMetadataWhenLocked
    val displayTitle = if (hideLockedMetadata) {
        "Secure document"
    } else {
        document.title
    }
    val largeText = LocalDensity.current.fontScale >= 1.30f
    val previewWidth = if (largeText) 52.dp else 76.dp
    val previewHeight = if (largeText) 72.dp else 104.dp
    val selectionModifier = if (selectionMode) {
        Modifier
            .selectable(
                selected = selected,
                onClick = onToggleSelected,
                role = Role.Checkbox
            )
            .semantics {
                stateDescription = if (selected) {
                    "Selected"
                } else {
                    "Not selected"
                }
            }
    } else {
        Modifier.clickable(onClick = onClick)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(selectionModifier)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clearAndSetSemantics { }
                )
            }
            val coverPage = cover
            if (coverPage != null && !locked) {
                Box(
                    modifier = Modifier
                        .width(previewWidth)
                        .height(previewHeight)
                ) {
                    FileImage(
                        path = coverPage.imagePath,
                        modifier = Modifier.fillMaxSize(),
                        maxDecodeEdge = 480,
                        rotationDegrees = coverPage.rotationDegrees,
                        cropQuad = coverPage.cropQuad,
                        visualRecipe = coverPage.visualRecipe,
                        cleanupRecipe = coverPage.cleanupRecipe,
                        contentDescription = "Preview of $displayTitle"
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
                    modifier = Modifier
                        .width(previewWidth)
                        .height(previewHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (locked) {
                            Icons.Default.Lock
                        } else {
                            Icons.Default.Description
                        },
                        contentDescription = null,
                        tint = if (
                            !hideLockedMetadata &&
                            document.favorite
                        ) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(
                    displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = if (largeText) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    if (hideLockedMetadata) {
                        "Protected details hidden"
                    } else {
                        buildString {
                            append("${document.pageCount} page")
                            if (document.pageCount != 1) {
                                append('s')
                            }
                            append(" · ")
                            append(
                                formatDate(document.updatedAt)
                            )
                            if (document.processing) {
                                append(" · Processing")
                            }
                            if (document.trashedAt != null) {
                                append(" · In Trash")
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val folderLabel = if (hideLockedMetadata) {
                    null
                } else {
                    folderPath(document.folderId, folders)
                }
                val type = if (hideLockedMetadata) {
                    DocumentType.UNSPECIFIED
                } else {
                    DocumentType.fromStored(
                        document.documentType
                    )
                }
                val cardScanMode = if (hideLockedMetadata) {
                    ScanMode.DOCUMENT
                } else {
                    ScanMode.fromStored(document.scanMode)
                }
                val organizationLine = buildString {
                    if (locked) {
                        append("Vault locked")
                    }
                    if (cardScanMode != ScanMode.DOCUMENT) {
                        append(cardScanMode.label)
                    }
                    if (folderLabel != null) {
                        if (isNotEmpty()) append(" · ")
                        append(folderLabel)
                    }
                    if (type != DocumentType.UNSPECIFIED) {
                        if (isNotEmpty()) append(" · ")
                        append(type.label)
                    }
                    if (
                        !hideLockedMetadata &&
                        document.needsReview
                    ) {
                        if (isNotEmpty()) append(" · ")
                        append("Needs review")
                    }
                }
                if (organizationLine.isNotBlank()) {
                    Text(
                        organizationLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = if (largeText) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (tags.isNotEmpty() && !hideLockedMetadata) {
                    Text(
                        tags.joinToString("  ") { "#${it.name}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }

                val suggestion = if (
                    hideLockedMetadata
                ) {
                    null
                } else {
                    document.suggestedType
                        ?.let {
                            DocumentType.fromStored(it)
                        }
                        ?.takeIf {
                            it !=
                                DocumentType.UNSPECIFIED
                        }
                }
                if (suggestion != null && !selectionMode) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            "Suggested: ${suggestion.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = onAcceptSuggestion) {
                            Text("Accept")
                        }
                    }
                }

                if (document.ocrText.isNotBlank()) {
                    Text(
                        remember(
                            document.id,
                            document.updatedAt,
                            document.ocrText
                        ) {
                            document.ocrText
                                .take(800)
                                .replace('\n', ' ')
                        },
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
