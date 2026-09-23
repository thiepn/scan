package com.thiepn.scan.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RestorePage
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thiepn.scan.data.BookPageSide
import com.thiepn.scan.data.BookReviewPolicy
import com.thiepn.scan.data.BookSpreadAnalysis
import com.thiepn.scan.data.CropQuadCodec
import com.thiepn.scan.data.DocumentFieldEntity
import com.thiepn.scan.data.DocumentPageSearchHit
import com.thiepn.scan.data.OcrLayoutCodec
import com.thiepn.scan.data.OcrScript
import com.thiepn.scan.data.OcrSearchTerms
import com.thiepn.scan.data.PageEntity
import com.thiepn.scan.data.PageVisualRecipeCodec
import com.thiepn.scan.data.PdfQuality
import com.thiepn.scan.data.ScanMode
import com.thiepn.scan.data.ScanModeProfiles
import com.thiepn.scan.data.ScanRepository
import com.thiepn.scan.util.shareFile
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    documentId: String,
    repository: ScanRepository,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onAddPages: (ScanMode) -> Unit,
    onInsertPages: (Int, ScanMode) -> Unit,
    onRetakePage: (String, ScanMode) -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingPdfSavePath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingTextSavePath by rememberSaveable { mutableStateOf<String?>(null) }

    val savePdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val path = pendingPdfSavePath
        pendingPdfSavePath = null
        if (uri != null && path != null) {
            scope.launch {
                runCatching { repository.saveExportToUri(File(path), uri) }
                    .onSuccess { onMessage("PDF saved") }
                    .onFailure { onMessage(it.message ?: "Could not save PDF") }
            }
        }
    }

    val saveTextLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val path = pendingTextSavePath
        pendingTextSavePath = null
        if (uri != null && path != null) {
            scope.launch {
                runCatching { repository.saveExportToUri(File(path), uri) }
                    .onSuccess { onMessage("Text saved") }
                    .onFailure { onMessage(it.message ?: "Could not save text") }
            }
        }
    }

    var replacePageId by rememberSaveable { mutableStateOf<String?>(null) }
    val replaceImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val pageId = replacePageId
        replacePageId = null
        if (uri != null && pageId != null) {
            scope.launch {
                runCatching { repository.replacePageFromUri(documentId, pageId, uri) }
                    .onSuccess { onMessage("Page replaced") }
                    .onFailure { onMessage(it.message ?: "Could not replace page") }
            }
        }
    }
    val document by repository.observeDocument(documentId).collectAsStateWithLifecycle(initialValue = null)
    val pages by repository.observePages(documentId).collectAsStateWithLifecycle(initialValue = emptyList())
    val deletedPages by repository.observeDeletedPages(documentId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val folders by repository.observeFolders()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val tags by repository.observeTags()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val documentFields by repository.observeDocumentFields(documentId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var protectOpen by remember { mutableStateOf(false) }
    var extractOpen by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var textExportOpen by remember { mutableStateOf(false) }
    var deletedPagesOpen by remember { mutableStateOf(false) }
    var pageDeleteCandidate by remember { mutableStateOf<PageEntity?>(null) }
    var cropPageId by rememberSaveable { mutableStateOf<String?>(null) }
    var enhancePageId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedPageIds by remember { mutableStateOf(emptyList<String>()) }
    var batchFilterOpen by remember { mutableStateOf(false) }
    var batchMoveOpen by remember { mutableStateOf(false) }
    var batchDeleteOpen by remember { mutableStateOf(false) }
    var batchExportOpen by remember { mutableStateOf(false) }
    var insertPagesOpen by remember { mutableStateOf(false) }
    var resetAllEditsOpen by remember { mutableStateOf(false) }
    var organizeDocumentOpen by remember { mutableStateOf(false) }
    var scanModeOpen by remember { mutableStateOf(false) }
    var documentSearchOpen by remember { mutableStateOf(false) }
    var documentSearchQuery by remember { mutableStateOf("") }
    var documentSearchHits by remember { mutableStateOf<List<DocumentPageSearchHit>>(emptyList()) }
    var documentSearchBusy by remember { mutableStateOf(false) }
    var ocrScriptOpen by remember { mutableStateOf(false) }
    var bookReviewPageId by rememberSaveable { mutableStateOf<String?>(null) }
    var bookReviewAnalysis by remember { mutableStateOf<BookSpreadAnalysis?>(null) }
    var bookReviewBusy by remember { mutableStateOf(false) }
    val pageListState = rememberLazyListState()

    LaunchedEffect(
        documentSearchOpen,
        documentSearchQuery,
        pages.map { it.ocrFingerprint }
    ) {
        if (!documentSearchOpen || documentSearchQuery.isBlank()) {
            documentSearchBusy = false
            documentSearchHits = emptyList()
        } else {
            documentSearchBusy = true
            documentSearchHits = runCatching {
                repository.searchDocumentPages(documentId, documentSearchQuery)
            }.getOrDefault(emptyList())
            documentSearchBusy = false
        }
    }

    LaunchedEffect(pages.map { it.id }) {
        val activeIds = pages.map { it.id }.toSet()
        selectedPageIds = selectedPageIds.filter { it in activeIds }
    }

    val doc = document
    LaunchedEffect(
        doc?.id,
        doc?.processing,
        pages.map { it.ocrLayout to it.ocrScript }
    ) {
        if (doc != null && !doc.processing && pages.isNotEmpty()) {
            runCatching { repository.ensureSpatialOcr(doc.id) }
                .onFailure { onMessage(it.message ?: "Could not upgrade OCR data") }
        }
    }

    if (doc == null) {
        Column(Modifier.fillMaxSize().padding(contentPadding).padding(24.dp)) {
            Text("Document not found")
            TextButton(onClick = onBack) { Text("Back") }
        }
        return
    }

    val scanMode = ScanMode.fromStored(doc.scanMode)
    val scanProfile = ScanModeProfiles.forMode(scanMode)

    val bookReviewPages = if (scanMode == ScanMode.BOOK) {
        pages.filter(BookReviewPolicy::needsManualReview)
    } else {
        emptyList()
    }
    val bookReviewCount = bookReviewPages.size
    val bookSplitCount = if (scanMode == ScanMode.BOOK) {
        pages.mapNotNull { it.sourceSpreadPageId }.distinct().size
    } else {
        0
    }

    val hasAnyPageEdits = pages.any { page ->
        page.rotationDegrees != 0 ||
            !CropQuadCodec.decode(page.cropQuad).isFullFrame() ||
            !PageVisualRecipeCodec.decode(page.visualRecipe).isOriginal()
    }

    Scaffold(
        modifier = Modifier.padding(contentPadding),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) {
                            "${selectedPageIds.size} selected"
                        } else {
                            doc.title
                        },
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = {
                            selectionMode = false
                            selectedPageIds = emptyList()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Exit page selection")
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(
                            onClick = {
                                selectedPageIds = if (selectedPageIds.size == pages.size) {
                                    emptyList()
                                } else {
                                    pages.map { it.id }
                                }
                            },
                            enabled = pages.isNotEmpty()
                        ) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select all pages")
                        }
                    } else if (doc.trashedAt == null) {
                        IconButton(
                            onClick = { scanModeOpen = true },
                            enabled = !doc.processing
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "Scan mode")
                        }
                        IconButton(
                            onClick = { organizeDocumentOpen = true },
                            enabled = !doc.processing
                        ) {
                            Icon(Icons.Default.Label, contentDescription = "Organize document")
                        }
                        IconButton(
                            onClick = { documentSearchOpen = true },
                            enabled = !doc.processing &&
                                scanProfile.ocrEnabled &&
                                pages.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Find in document")
                        }
                        IconButton(
                            onClick = { ocrScriptOpen = true },
                            enabled = !doc.processing && scanProfile.ocrEnabled
                        ) {
                            Icon(Icons.Default.Language, contentDescription = "OCR language model")
                        }
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
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = pageListState,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    if (doc.trashedAt != null) {
                        Text(
                            "This document is in Trash. Restore it to edit, export, or change its pages.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    runCatching { repository.restoreDocument(doc.id) }
                                        .onSuccess { onDeleted() }
                                        .onFailure { onMessage(it.message ?: "Could not restore document") }
                                }
                            }) {
                                Icon(Icons.Default.RestoreFromTrash, contentDescription = null)
                                Text(" Restore")
                            }
                            OutlinedButton(onClick = { deleteOpen = true }) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Text(" Delete forever")
                            }
                        }
                    } else {
                        if (doc.processing) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(modifier = Modifier.height(20.dp))
                                Text(
                                    if (scanProfile.ocrEnabled) "Recognizing text…"
                                    else "Preparing scan…",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        Card(Modifier.fillMaxWidth()) {
                            SpecializedModeSummary(
                                mode = scanMode,
                                pageCount = pages.size,
                                fields = documentFields,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                        if (scanMode == ScanMode.BOOK) {
                            Spacer(Modifier.height(8.dp))
                            Card(Modifier.fillMaxWidth()) {
                                BookToolsBar(
                                    reviewCount = bookReviewCount,
                                    splitCount = bookSplitCount,
                                    enabled = !doc.processing,
                                    onAutoProcess = {
                                        scope.launch {
                                            runCatching {
                                                repository.autoProcessBookSpreads(doc.id)
                                            }
                                                .onSuccess { count ->
                                                    onMessage(
                                                        if (count == 0) {
                                                            "Book analysis complete · no new spreads auto-split"
                                                        } else {
                                                            "Book analysis complete · $count new spread${if (count == 1) "" else "s"} auto-split"
                                                        }
                                                    )
                                                }
                                                .onFailure {
                                                    onMessage(
                                                        it.message ?: "Could not analyze book spreads"
                                                    )
                                                }
                                        }
                                    },
                                    onReviewNext = {
                                        val next = bookReviewPages.firstOrNull()
                                        if (next != null) {
                                            scope.launch {
                                                bookReviewBusy = true
                                                runCatching {
                                                    repository.analyzeBookSpread(
                                                        doc.id,
                                                        next.id
                                                    )
                                                }
                                                    .onSuccess { analysis ->
                                                        bookReviewPageId = next.id
                                                        bookReviewAnalysis = analysis
                                                    }
                                                    .onFailure {
                                                        onMessage(
                                                            it.message ?: "Could not analyze book spread"
                                                        )
                                                    }
                                                bookReviewBusy = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { textExportOpen = true },
                                enabled = scanProfile.ocrEnabled && !doc.processing
                            ) {
                                Text("Text")
                            }
                            OutlinedButton(onClick = {
                                scope.launch { repository.setArchived(doc.id, !doc.archived) }
                            }) {
                                Icon(
                                    if (doc.archived) Icons.Default.Restore else Icons.Default.Archive,
                                    contentDescription = null
                                )
                                Text(if (doc.archived) " Restore" else " Archive")
                            }
                            OutlinedButton(
                                onClick = { deleteOpen = true },
                                enabled = !doc.processing
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Text(" Trash")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onAddPages(scanMode) },
                                enabled = !doc.processing
                            ) {
                                Icon(Icons.Default.AddAPhoto, contentDescription = null)
                                Text(
                                    if (scanMode == ScanMode.ID_CARD && pages.size == 1) {
                                        " Scan back"
                                    } else {
                                        " Add pages"
                                    }
                                )
                            }
                            OutlinedButton(
                                onClick = { insertPagesOpen = true },
                                enabled = !doc.processing
                            ) {
                                Text("Insert scans")
                            }
                            OutlinedButton(
                                onClick = {
                                    selectionMode = true
                                    selectedPageIds = emptyList()
                                },
                                enabled = !doc.processing && pages.isNotEmpty()
                            ) {
                                Icon(Icons.Default.SelectAll, contentDescription = null)
                                Text(" Select pages")
                            }
                            if (hasAnyPageEdits) {
                                OutlinedButton(
                                    onClick = { resetAllEditsOpen = true },
                                    enabled = !doc.processing
                                ) {
                                    Icon(Icons.Default.RestartAlt, contentDescription = null)
                                    Text(" Reset all edits")
                                }
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
            }

            if (selectionMode && doc.trashedAt == null) {
                item {
                    BatchActionBar(
                        selectedCount = selectedPageIds.size,
                        canDelete = pages.size - selectedPageIds.size >= 1,
                        onRotate = {
                            val selected = selectedPageIds
                            scope.launch {
                                runCatching { repository.rotatePages(doc.id, selected) }
                                    .onSuccess { onMessage("Selected pages rotated") }
                                    .onFailure { onMessage(it.message ?: "Could not rotate pages") }
                            }
                        },
                        onFilter = { batchFilterOpen = true },
                        onMove = { batchMoveOpen = true },
                        onDuplicate = {
                            val selected = selectedPageIds
                            scope.launch {
                                runCatching { repository.duplicatePages(doc.id, selected) }
                                    .onSuccess { count ->
                                        selectionMode = false
                                        selectedPageIds = emptyList()
                                        onMessage("$count page${if (count == 1) "" else "s"} duplicated")
                                    }
                                    .onFailure { onMessage(it.message ?: "Could not duplicate pages") }
                            }
                        },
                        onReset = {
                            val selected = selectedPageIds
                            scope.launch {
                                runCatching { repository.resetPageEdits(doc.id, selected) }
                                    .onSuccess { onMessage("Selected page edits reset") }
                                    .onFailure { onMessage(it.message ?: "Could not reset page edits") }
                            }
                        },
                        onDelete = { batchDeleteOpen = true },
                        onExport = { batchExportOpen = true }
                    )
                }
            }

            itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                val editable = doc.trashedAt == null && !doc.processing
                val pageHasEdits =
                    page.rotationDegrees != 0 ||
                        !CropQuadCodec.decode(page.cropQuad).isFullFrame() ||
                        !PageVisualRecipeCodec.decode(page.visualRecipe).isOriginal()
                PageCard(
                    page = page,
                    displayNumber = index + 1,
                    displayLabel = when {
                        scanMode == ScanMode.ID_CARD && index == 0 -> "ID front"
                        scanMode == ScanMode.ID_CARD && index == 1 -> "ID back"
                        scanMode == ScanMode.BOOK &&
                            page.bookSide == BookPageSide.LEFT.name ->
                            "Book left · Page ${index + 1}"
                        scanMode == ScanMode.BOOK &&
                            page.bookSide == BookPageSide.RIGHT.name ->
                            "Book right · Page ${index + 1}"
                        else -> "Page ${index + 1}"
                    },
                    selectionMode = selectionMode,
                    selected = page.id in selectedPageIds,
                    highlightQuery = documentSearchQuery.takeIf {
                        page.id in documentSearchHits.map { hit -> hit.pageId }
                    },
                    canMoveUp = editable && index > 0,
                    canMoveDown = editable && index < pages.lastIndex,
                    canRotate = editable,
                    canCrop = editable,
                    canEnhance = editable,
                    canDuplicate = editable,
                    canReplace = editable,
                    canRetake = editable,
                    canReset = editable && pageHasEdits,
                    canDelete = editable && pages.size > 1,
                    canDragReorder = editable && pages.size > 1 && !selectionMode,
                    canReviewBookSpread = editable &&
                        scanMode == ScanMode.BOOK &&
                        BookReviewPolicy.canOpenManualReview(page) &&
                        !bookReviewBusy,
                    canRestoreBookSpread = editable &&
                        page.sourceSpreadPageId != null,
                    onToggleSelected = {
                        selectedPageIds = if (page.id in selectedPageIds) {
                            selectedPageIds - page.id
                        } else {
                            selectedPageIds + page.id
                        }
                    },
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
                    onDragReorder = { offset ->
                        val target = (index + offset).coerceIn(0, pages.lastIndex)
                        if (target != index) {
                            scope.launch {
                                runCatching {
                                    repository.movePages(doc.id, listOf(page.id), target)
                                }
                                    .onFailure {
                                        onMessage(it.message ?: "Could not reorder page")
                                    }
                            }
                        }
                    },
                    onRotate = {
                        scope.launch {
                            runCatching { repository.rotatePage(doc.id, page.id) }
                                .onFailure { onMessage(it.message ?: "Could not rotate page") }
                        }
                    },
                    onCrop = { cropPageId = page.id },
                    onEnhance = { enhancePageId = page.id },
                    onDuplicate = {
                        scope.launch {
                            runCatching { repository.duplicatePage(doc.id, page.id) }
                                .onSuccess { onMessage("Page duplicated") }
                                .onFailure { onMessage(it.message ?: "Could not duplicate page") }
                        }
                    },
                    onReplace = {
                        replacePageId = page.id
                        replaceImageLauncher.launch(arrayOf("image/*"))
                    },
                    onRetake = { onRetakePage(page.id, scanMode) },
                    onReviewBookSpread = {
                        scope.launch {
                            bookReviewBusy = true
                            runCatching {
                                repository.analyzeBookSpread(doc.id, page.id)
                            }
                                .onSuccess { analysis ->
                                    bookReviewPageId = page.id
                                    bookReviewAnalysis = analysis
                                }
                                .onFailure {
                                    onMessage(
                                        it.message ?: "Could not analyze book spread"
                                    )
                                }
                            bookReviewBusy = false
                        }
                    },
                    onRestoreBookSpread = {
                        val sourceId = page.sourceSpreadPageId
                        if (sourceId != null) {
                            scope.launch {
                                runCatching {
                                    repository.restoreBookSpread(doc.id, sourceId)
                                }
                                    .onSuccess {
                                        onMessage("Original book spread restored")
                                    }
                                    .onFailure {
                                        onMessage(
                                            it.message ?: "Could not restore original spread"
                                        )
                                    }
                            }
                        }
                    },
                    onReset = {
                        scope.launch {
                            runCatching { repository.resetPageEdits(doc.id, listOf(page.id)) }
                                .onSuccess { onMessage("Page edits reset") }
                                .onFailure { onMessage(it.message ?: "Could not reset page edits") }
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

    val enhancePage = enhancePageId?.let { id -> pages.firstOrNull { it.id == id } }
    if (enhancePage != null) {
        EnhancementEditorDialog(
            page = enhancePage,
            onDismiss = { enhancePageId = null },
            onSave = { recipe ->
                enhancePageId = null
                scope.launch {
                    runCatching {
                        repository.updatePageVisualRecipe(doc.id, enhancePage.id, recipe)
                    }
                        .onSuccess { onMessage("Enhancement updated") }
                        .onFailure { onMessage(it.message ?: "Could not update enhancement") }
                }
            }
        )
    }

    val cropPage = cropPageId?.let { id -> pages.firstOrNull { it.id == id } }
    if (cropPage != null) {
        CropEditorDialog(
            page = cropPage,
            onDismiss = { cropPageId = null },
            onAutoDetect = {
                repository.detectPageCrop(doc.id, cropPage.id)
            },
            onSave = { quad ->
                cropPageId = null
                scope.launch {
                    runCatching {
                        repository.updatePageCrop(doc.id, cropPage.id, quad)
                    }
                        .onSuccess { onMessage("Crop updated; refreshing OCR") }
                        .onFailure { onMessage(it.message ?: "Could not update crop") }
                }
            }
        )
    }

    val bookReviewPage = bookReviewPageId?.let { id ->
        pages.firstOrNull { it.id == id }
    }
    val activeBookAnalysis = bookReviewAnalysis
    if (bookReviewPage != null && activeBookAnalysis != null) {
        val pageIndex = pages.indexOfFirst { it.id == bookReviewPage.id }
        BookSpreadReviewDialog(
            pageLabel = if (pageIndex >= 0) "Page ${pageIndex + 1}" else "book page",
            imagePath = bookReviewPage.imagePath,
            rotationDegrees = bookReviewPage.rotationDegrees,
            cropQuad = bookReviewPage.cropQuad,
            visualRecipe = bookReviewPage.visualRecipe,
            analysis = activeBookAnalysis,
            onDismiss = {
                bookReviewPageId = null
                bookReviewAnalysis = null
            },
            onKeepSingle = {
                val pageId = bookReviewPage.id
                bookReviewPageId = null
                bookReviewAnalysis = null
                scope.launch {
                    runCatching {
                        repository.keepBookPageSingle(doc.id, pageId)
                    }
                        .onSuccess {
                            onMessage("Kept as a single book page")
                        }
                        .onFailure {
                            onMessage(
                                it.message ?: "Could not resolve book-page review"
                            )
                        }
                }
            },
            onSplit = { gutterX, dewarp ->
                val pageId = bookReviewPage.id
                bookReviewPageId = null
                bookReviewAnalysis = null
                scope.launch {
                    runCatching {
                        repository.splitBookPage(
                            documentId = doc.id,
                            pageId = pageId,
                            dewarp = dewarp,
                            force = true,
                            gutterX = gutterX
                        )
                    }
                        .onSuccess {
                            onMessage(
                                if (dewarp) {
                                    "Spread split and flattened"
                                } else {
                                    "Spread split"
                                }
                            )
                        }
                        .onFailure {
                            onMessage(it.message ?: "Could not split book spread")
                        }
                }
            }
        )
    }

    if (scanModeOpen) {
        ChangeScanModeDialog(
            current = scanMode,
            onDismiss = { scanModeOpen = false },
            onApply = { mode, applyDefaults ->
                scanModeOpen = false
                if (mode != scanMode || applyDefaults) {
                    scope.launch {
                        runCatching {
                            repository.setScanMode(
                                documentId = doc.id,
                                scanMode = mode,
                                applyEnhancementDefaults = applyDefaults
                            )
                        }
                            .onSuccess {
                                documentSearchHits = emptyList()
                                onMessage(
                                    "Scan mode changed to ${mode.label}" +
                                        if (applyDefaults) " with mode defaults" else ""
                                )
                            }
                            .onFailure {
                                onMessage(it.message ?: "Could not change scan mode")
                            }
                    }
                }
            }
        )
    }

    if (organizeDocumentOpen) {
        BulkOrganizeDialog(
            selectedCount = 1,
            folders = folders,
            tags = tags,
            onDismiss = { organizeDocumentOpen = false },
            onApply = { change ->
                organizeDocumentOpen = false
                scope.launch {
                    runCatching {
                        val ids = listOf(doc.id)
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
                            repository.setDocumentsNeedsReview(ids, change.needsReview)
                        }
                    }
                        .onSuccess { onMessage("Document organized") }
                        .onFailure {
                            onMessage(it.message ?: "Could not organize document")
                        }
                }
            }
        )
    }

    if (documentSearchOpen) {
        DocumentSearchDialog(
            query = documentSearchQuery,
            hits = documentSearchHits,
            searching = documentSearchBusy,
            onQueryChange = { documentSearchQuery = it },
            onDismiss = { documentSearchOpen = false },
            onOpenHit = { hit ->
                documentSearchOpen = false
                val pageIndex = pages.indexOfFirst { it.id == hit.pageId }
                if (pageIndex >= 0) {
                    scope.launch {
                        pageListState.animateScrollToItem(pageIndex + 1)
                    }
                }
            }
        )
    }

    if (ocrScriptOpen) {
        OcrScriptDialog(
            current = OcrScript.fromStored(doc.ocrScript),
            onDismiss = { ocrScriptOpen = false },
            onApply = { script ->
                ocrScriptOpen = false
                if (script.name != doc.ocrScript) {
                    scope.launch {
                        runCatching { repository.setOcrScript(doc.id, script) }
                            .onSuccess {
                                documentSearchHits = emptyList()
                                onMessage("OCR model changed to ${script.label}; recognizing pages…")
                            }
                            .onFailure {
                                onMessage(it.message ?: "Could not change OCR model")
                            }
                    }
                }
            }
        )
    }

    if (batchFilterOpen) {
        BatchFilterDialog(
            onDismiss = { batchFilterOpen = false },
            onApply = { preset ->
                batchFilterOpen = false
                val selected = selectedPageIds
                scope.launch {
                    runCatching { repository.applyPresetToPages(doc.id, selected, preset) }
                        .onSuccess { onMessage("Filter applied to selected pages") }
                        .onFailure { onMessage(it.message ?: "Could not apply filter") }
                }
            }
        )
    }

    if (batchMoveOpen) {
        MovePagesDialog(
            pageCount = pages.size,
            selectedCount = selectedPageIds.size,
            onDismiss = { batchMoveOpen = false },
            onMove = { targetIndex ->
                batchMoveOpen = false
                val selected = selectedPageIds
                scope.launch {
                    runCatching { repository.movePages(doc.id, selected, targetIndex) }
                        .onSuccess {
                            selectionMode = false
                            selectedPageIds = emptyList()
                            onMessage("Selected pages moved")
                        }
                        .onFailure { onMessage(it.message ?: "Could not move pages") }
                }
            }
        )
    }

    if (insertPagesOpen) {
        InsertPagesDialog(
            pageCount = pages.size,
            onDismiss = { insertPagesOpen = false },
            onInsert = { index ->
                insertPagesOpen = false
                onInsertPages(index, scanMode)
            }
        )
    }

    if (resetAllEditsOpen) {
        AlertDialog(
            onDismissRequest = { resetAllEditsOpen = false },
            title = { Text("Reset all page edits?") },
            text = {
                Text(
                    "Rotation, crop/perspective, and enhancement settings will return to their original values. Page order, OCR source files, replacements, duplicates, and deleted-page history are not changed."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    resetAllEditsOpen = false
                    scope.launch {
                        runCatching { repository.resetAllPageEdits(doc.id) }
                            .onSuccess { onMessage("All page edits reset") }
                            .onFailure { onMessage(it.message ?: "Could not reset page edits") }
                    }
                }) { Text("Reset all") }
            },
            dismissButton = {
                TextButton(onClick = { resetAllEditsOpen = false }) { Text("Cancel") }
            }
        )
    }

    if (batchDeleteOpen) {
        AlertDialog(
            onDismissRequest = { batchDeleteOpen = false },
            title = { Text("Delete ${selectedPageIds.size} selected pages?") },
            text = {
                Text(
                    "The selected pages will move to Deleted pages and remain recoverable. At least one active page must remain."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    batchDeleteOpen = false
                    val selected = selectedPageIds
                    scope.launch {
                        runCatching { repository.softDeletePages(doc.id, selected) }
                            .onSuccess {
                                selectionMode = false
                                selectedPageIds = emptyList()
                                onMessage("Selected pages moved to Deleted pages")
                            }
                            .onFailure { onMessage(it.message ?: "Could not delete pages") }
                    }
                }) { Text("Delete pages") }
            },
            dismissButton = {
                TextButton(onClick = { batchDeleteOpen = false }) { Text("Cancel") }
            }
        )
    }

    if (batchExportOpen) {
        val selected = selectedPageIds
        SelectedExportDialog(
            selectedCount = selected.size,
            defaultQuality = scanProfile.defaultPdfQuality,
            textExportEnabled = scanProfile.ocrEnabled,
            onDismiss = { batchExportOpen = false },
            onPdfSave = { quality ->
                batchExportOpen = false
                scope.launch {
                    runCatching { repository.createPdfExportForPages(doc.id, selected, quality) }
                        .onSuccess { file ->
                            if (file != null) {
                                pendingPdfSavePath = file.absolutePath
                                savePdfLauncher.launch(file.name)
                            } else {
                                onMessage("PDF is not available yet")
                            }
                        }
                        .onFailure { onMessage(it.message ?: "Could not export selected pages") }
                }
            },
            onPdfShare = { quality ->
                batchExportOpen = false
                scope.launch {
                    runCatching { repository.createPdfExportForPages(doc.id, selected, quality) }
                        .onSuccess { file ->
                            if (file != null) shareFile(context, file, "application/pdf")
                            else onMessage("PDF is not available yet")
                        }
                        .onFailure { onMessage(it.message ?: "Could not export selected pages") }
                }
            },
            onTextSave = {
                batchExportOpen = false
                scope.launch {
                    runCatching { repository.createTextExportForPages(doc.id, selected) }
                        .onSuccess { file ->
                            if (file != null) {
                                pendingTextSavePath = file.absolutePath
                                saveTextLauncher.launch(file.name)
                            } else {
                                onMessage("No text export available")
                            }
                        }
                        .onFailure { onMessage(it.message ?: "Could not export selected text") }
                }
            },
            onTextShare = {
                batchExportOpen = false
                scope.launch {
                    runCatching { repository.createTextExportForPages(doc.id, selected) }
                        .onSuccess { file ->
                            if (file != null) shareFile(context, file, "text/plain")
                            else onMessage("No text export available")
                        }
                        .onFailure { onMessage(it.message ?: "Could not export selected text") }
                }
            }
        )
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
            defaultQuality = scanProfile.defaultPdfQuality,
            onDismiss = { exportOpen = false },
            onShare = { quality ->
                exportOpen = false
                scope.launch {
                    runCatching { repository.createPdfExport(doc.id, quality = quality) }
                        .onSuccess { file ->
                            if (file != null) shareFile(context, file, "application/pdf")
                            else onMessage("PDF is not available yet")
                        }
                        .onFailure { onMessage(it.message ?: "Could not create PDF") }
                }
            },
            onSave = { quality ->
                exportOpen = false
                scope.launch {
                    runCatching { repository.createPdfExport(doc.id, quality = quality) }
                        .onSuccess { file ->
                            if (file != null) {
                                pendingPdfSavePath = file.absolutePath
                                savePdfLauncher.launch(file.name)
                            } else {
                                onMessage("PDF is not available yet")
                            }
                        }
                        .onFailure { onMessage(it.message ?: "Could not create PDF") }
                }
            }
        )
    }

    if (textExportOpen) {
        TextExportDialog(
            onDismiss = { textExportOpen = false },
            onShare = {
                textExportOpen = false
                scope.launch {
                    runCatching { repository.createTextExport(doc.id) }
                        .onSuccess { file ->
                            if (file != null) shareFile(context, file, "text/plain")
                            else onMessage("No text export available")
                        }
                        .onFailure { onMessage(it.message ?: "Could not create text export") }
                }
            },
            onSave = {
                textExportOpen = false
                scope.launch {
                    runCatching { repository.createTextExport(doc.id) }
                        .onSuccess { file ->
                            if (file != null) {
                                pendingTextSavePath = file.absolutePath
                                saveTextLauncher.launch(file.name)
                            } else {
                                onMessage("No text export available")
                            }
                        }
                        .onFailure { onMessage(it.message ?: "Could not create text export") }
                }
            }
        )
    }

    if (protectOpen) {
        ProtectPdfDialog(
            onDismiss = { protectOpen = false },
            onShare = { password ->
                protectOpen = false
                scope.launch {
                    runCatching {
                        repository.createPdfExport(
                            doc.id,
                            password,
                            scanProfile.defaultPdfQuality
                        )
                    }
                        .onSuccess { file ->
                            if (file != null) shareFile(context, file, "application/pdf")
                            else onMessage("PDF is not available yet")
                        }
                        .onFailure { onMessage(it.message ?: "Could not protect PDF") }
                }
            },
            onSave = { password ->
                protectOpen = false
                scope.launch {
                    runCatching {
                        repository.createPdfExport(
                            doc.id,
                            password,
                            scanProfile.defaultPdfQuality
                        )
                    }
                        .onSuccess { file ->
                            if (file != null) {
                                pendingPdfSavePath = file.absolutePath
                                savePdfLauncher.launch(file.name)
                            } else {
                                onMessage("PDF is not available yet")
                            }
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
            onShare = { range ->
                extractOpen = false
                scope.launch {
                    runCatching { repository.extractPages(doc.id, range) }
                        .onSuccess { file ->
                            if (file != null) shareFile(context, file, "application/pdf")
                            else onMessage("Could not create extracted PDF")
                        }
                        .onFailure { onMessage(it.message ?: "Could not extract pages") }
                }
            },
            onSave = { range ->
                extractOpen = false
                scope.launch {
                    runCatching { repository.extractPages(doc.id, range) }
                        .onSuccess { file ->
                            if (file != null) {
                                pendingPdfSavePath = file.absolutePath
                                savePdfLauncher.launch(file.name)
                            } else {
                                onMessage("Could not create extracted PDF")
                            }
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
            },
            onRestoreAll = {
                val ids = deletedPages.map { it.id }
                scope.launch {
                    runCatching { repository.restorePages(doc.id, ids) }
                        .onSuccess {
                            deletedPagesOpen = false
                            onMessage("${ids.size} page${if (ids.size == 1) "" else "s"} restored")
                        }
                        .onFailure { onMessage(it.message ?: "Could not restore pages") }
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
        val permanent = doc.trashedAt != null
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = {
                Text(if (permanent) "Delete forever?" else "Move document to Trash?")
            },
            text = {
                Text(
                    if (permanent) {
                        "This permanently removes the document, its source PDF and page images, OCR data, metadata, and direct export copies created for this document. This cannot be undone. Previously shared, saved, or merged PDF copies are not affected."
                    } else {
                        "The document will move to Trash and can be restored later. Its files are not deleted yet."
                    }
                )
            },
            confirmButton = {
                Button(onClick = {
                    deleteOpen = false
                    scope.launch {
                        runCatching {
                            if (permanent) repository.deleteForever(doc.id)
                            else repository.trashDocument(doc.id)
                        }
                            .onSuccess { onDeleted() }
                            .onFailure {
                                onMessage(
                                    it.message ?: if (permanent) "Could not delete document" else "Could not move document to Trash"
                                )
                            }
                    }
                }) {
                    Text(if (permanent) "Delete forever" else "Move to Trash")
                }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PageCard(
    page: PageEntity,
    displayNumber: Int,
    displayLabel: String,
    selectionMode: Boolean,
    selected: Boolean,
    highlightQuery: String?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRotate: Boolean,
    canCrop: Boolean,
    canEnhance: Boolean,
    canDuplicate: Boolean,
    canReplace: Boolean,
    canRetake: Boolean,
    canReset: Boolean,
    canDelete: Boolean,
    canDragReorder: Boolean,
    canReviewBookSpread: Boolean,
    canRestoreBookSpread: Boolean,
    onToggleSelected: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDragReorder: (Int) -> Unit,
    onRotate: () -> Unit,
    onCrop: () -> Unit,
    onEnhance: () -> Unit,
    onDuplicate: () -> Unit,
    onReplace: () -> Unit,
    onRetake: () -> Unit,
    onReviewBookSpread: () -> Unit,
    onRestoreBookSpread: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit
) {
    val density = LocalDensity.current
    val dragThresholdPx = with(density) { 92.dp.toPx() }
    var dragDistance by remember(page.id) { mutableStateOf(0f) }
    val highlightLayout = remember(page.ocrLayout) {
        OcrLayoutCodec.decode(page.ocrLayout)
    }
    val highlightWords = remember(highlightLayout, highlightQuery) {
        OcrSearchTerms.matchingWords(highlightLayout, highlightQuery.orEmpty())
    }

    val dragModifier = if (canDragReorder) {
        Modifier.pointerInput(page.id, canDragReorder) {
            detectDragGesturesAfterLongPress(
                onDragStart = { dragDistance = 0f },
                onDragCancel = { dragDistance = 0f },
                onDragEnd = {
                    val steps = (dragDistance / dragThresholdPx).roundToInt()
                    dragDistance = 0f
                    if (steps != 0) onDragReorder(steps)
                },
                onDrag = { _, amount ->
                    dragDistance += amount.y
                }
            )
        }
    } else {
        Modifier
    }

    Card(
        Modifier
            .fillMaxWidth()
            .then(
                if (selectionMode) {
                    Modifier.clickable(onClick = onToggleSelected)
                } else {
                    Modifier
                }
            )
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelected() }
                    )
                }
                Text(
                    displayLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (!selectionMode) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = "Drag to reorder page",
                            modifier = dragModifier.padding(12.dp),
                            tint = if (canDragReorder) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            }
                        )
                        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Move page up")
                        }
                        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Move page down")
                        }
                        IconButton(onClick = onRotate, enabled = canRotate) {
                            Icon(Icons.Default.RotateRight, contentDescription = "Rotate page clockwise")
                        }
                        IconButton(onClick = onCrop, enabled = canCrop) {
                            Icon(Icons.Default.CropFree, contentDescription = "Crop and perspective")
                        }
                        IconButton(onClick = onEnhance, enabled = canEnhance) {
                            Icon(Icons.Default.Tune, contentDescription = "Enhance and filters")
                        }
                        IconButton(onClick = onDuplicate, enabled = canDuplicate) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate page")
                        }
                        IconButton(onClick = onReplace, enabled = canReplace) {
                            Icon(Icons.Default.Image, contentDescription = "Replace page from image")
                        }
                        IconButton(onClick = onRetake, enabled = canRetake) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "Retake page")
                        }
                        if (canReviewBookSpread) {
                            IconButton(onClick = onReviewBookSpread) {
                                Icon(
                                    Icons.Default.MenuBook,
                                    contentDescription = "Review book spread"
                                )
                            }
                        }
                        if (canRestoreBookSpread) {
                            IconButton(onClick = onRestoreBookSpread) {
                                Icon(
                                    Icons.Default.RestorePage,
                                    contentDescription = "Restore original book spread"
                                )
                            }
                        }
                        IconButton(onClick = onReset, enabled = canReset) {
                            Icon(Icons.Default.RestartAlt, contentDescription = "Reset page edits")
                        }
                        IconButton(onClick = onDelete, enabled = canDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete page")
                        }
                    }
                }
            }
            FileImage(
                path = page.imagePath,
                modifier = Modifier.fillMaxWidth().height(460.dp),
                rotationDegrees = page.rotationDegrees,
                cropQuad = page.cropQuad,
                visualRecipe = page.visualRecipe,
                highlightWords = highlightWords,
                highlightSourceWidth = highlightLayout?.sourceWidth ?: 0,
                highlightSourceHeight = highlightLayout?.sourceHeight ?: 0,
                contentDescription = displayLabel
            )
            if (page.ocrText.isNotBlank()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Recognized text",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    SelectionContainer {
                        HighlightedOcrText(
                            page = page,
                            query = highlightQuery.orEmpty(),
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
    onShare: (String) -> Unit,
    onSave: (String) -> Unit
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
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onSave(password) },
                    enabled = valid
                ) { Text("Save") }
                TextButton(
                    onClick = { onShare(password) },
                    enabled = valid
                ) { Text("Share") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


@Composable
private fun ExtractPagesDialog(
    pageCount: Int,
    onDismiss: () -> Unit,
    onShare: (String) -> Unit,
    onSave: (String) -> Unit
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
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onSave(range) },
                    enabled = range.isNotBlank()
                ) { Text("Save") }
                TextButton(
                    onClick = { onShare(range) },
                    enabled = range.isNotBlank()
                ) { Text("Share") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


@Composable
private fun ExportPdfDialog(
    defaultQuality: PdfQuality,
    onDismiss: () -> Unit,
    onShare: (PdfQuality) -> Unit,
    onSave: (PdfQuality) -> Unit
) {
    var quality by remember(defaultQuality) { mutableStateOf(defaultQuality) }
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
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onSave(quality) }) { Text("Save") }
                TextButton(onClick = { onShare(quality) }) { Text("Share") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


@Composable
private fun DeletedPagesDialog(
    pages: List<PageEntity>,
    onDismiss: () -> Unit,
    onRestore: (PageEntity) -> Unit,
    onRestoreAll: () -> Unit
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
            Row {
                TextButton(onClick = onRestoreAll, enabled = pages.isNotEmpty()) {
                    Text("Restore all")
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    )
}


@Composable
private fun TextExportDialog(
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OCR text") },
        text = {
            Text(
                "Export the current active pages as plain text in their current page order."
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSave) { Text("Save") }
                TextButton(onClick = onShare) { Text("Share") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
