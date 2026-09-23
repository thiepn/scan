package com.thiepn.scan

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.thiepn.scan.data.ScanMode
import com.thiepn.scan.data.ScanModeProfiles
import com.thiepn.scan.data.ScanRepository
import com.thiepn.scan.ui.DocumentScreen
import com.thiepn.scan.ui.LibraryScreen
import com.thiepn.scan.ui.ScanTheme
import com.thiepn.scan.util.displayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private sealed interface PendingScanAction {
    data class NewDocument(val mode: ScanMode) : PendingScanAction
    data class IdBack(val stagedFrontPath: String) : PendingScanAction
    data class Append(
        val documentId: String,
        val mode: ScanMode
    ) : PendingScanAction
    data class Insert(
        val documentId: String,
        val index: Int,
        val mode: ScanMode
    ) : PendingScanAction
    data class Retake(
        val documentId: String,
        val pageId: String,
        val mode: ScanMode
    ) : PendingScanAction
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as ScanApplication).graph.repository
        setContent {
            ScanTheme {
                Surface(Modifier.fillMaxSize()) {
                    ScanApp(repository)
                }
            }
        }
    }
}

@Composable
private fun ScanApp(repository: ScanRepository) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var selectedDocumentId by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingScanAction by remember { mutableStateOf<PendingScanAction?>(null) }

    lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>

    fun persistStagedIdFront(path: String, message: String) {
        scope.launch {
            busy = true
            val file = File(path)
            try {
                runCatching {
                    repository.ingestScan(
                        pageUris = listOf(Uri.fromFile(file)),
                        pdfUri = null,
                        scanMode = ScanMode.ID_CARD
                    )
                }
                    .onSuccess { id ->
                        selectedDocumentId = id
                        snackbar.showSnackbar(message)
                    }
                    .onFailure {
                        snackbar.showSnackbar(it.message ?: "Could not save ID card")
                    }
            } finally {
                file.delete()
                busy = false
            }
        }
    }

    scannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val action = pendingScanAction
        pendingScanAction = null

        if (action == null) {
            return@rememberLauncherForActivityResult
        }

        if (result.resultCode != Activity.RESULT_OK) {
            if (action is PendingScanAction.IdBack) {
                persistStagedIdFront(
                    action.stagedFrontPath,
                    "Front saved. The back can be added later."
                )
            }
            return@rememberLauncherForActivityResult
        }

        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val pages = scan?.pages?.map { it.imageUri }.orEmpty()
        val pdf = scan?.pdf?.uri

        when (action) {
            is PendingScanAction.NewDocument -> {
                if (action.mode == ScanMode.ID_CARD) {
                    val front = pages.firstOrNull()
                    if (front == null) {
                        scope.launch {
                            snackbar.showSnackbar("No ID-card image was returned")
                        }
                    } else {
                        scope.launch {
                            busy = true
                            val staged = runCatching {
                                stageCapture(context, front)
                            }.getOrElse {
                                busy = false
                                snackbar.showSnackbar(
                                    it.message ?: "Could not stage ID-card front"
                                )
                                return@launch
                            }
                            busy = false
                            pendingScanAction = PendingScanAction.IdBack(
                                stagedFrontPath = staged.absolutePath
                            )
                            launch {
                                snackbar.showSnackbar("Front captured. Now scan the back.")
                            }
                            startModeScanner(
                                activity = activity,
                                mode = ScanMode.ID_CARD,
                                forceSinglePage = true,
                                launcher = scannerLauncher,
                                onFailure = { error ->
                                    pendingScanAction = null
                                    persistStagedIdFront(
                                        staged.absolutePath,
                                        error.message
                                            ?: "Back scanner unavailable; front was saved."
                                    )
                                }
                            )
                        }
                    }
                } else {
                    scope.launch {
                        busy = true
                        try {
                            if (pages.isNotEmpty() || pdf != null) {
                                runCatching {
                                    repository.ingestScan(
                                        pageUris = pages,
                                        pdfUri = pdf,
                                        scanMode = action.mode
                                    )
                                }
                                    .onSuccess { selectedDocumentId = it }
                                    .onFailure {
                                        snackbar.showSnackbar(
                                            it.message ?: "Could not save scan"
                                        )
                                    }
                            }
                        } finally {
                            busy = false
                        }
                    }
                }
            }

            is PendingScanAction.IdBack -> {
                val stagedFront = File(action.stagedFrontPath)
                val back = pages.firstOrNull()
                scope.launch {
                    busy = true
                    try {
                        val pageUris = buildList {
                            add(Uri.fromFile(stagedFront))
                            if (back != null) add(back)
                        }
                        runCatching {
                            repository.ingestScan(
                                pageUris = pageUris,
                                pdfUri = null,
                                scanMode = ScanMode.ID_CARD
                            )
                        }
                            .onSuccess { id ->
                                selectedDocumentId = id
                                if (back == null) {
                                    snackbar.showSnackbar(
                                        "Front saved. The back can be added later."
                                    )
                                }
                            }
                            .onFailure {
                                snackbar.showSnackbar(
                                    it.message ?: "Could not save ID card"
                                )
                            }
                    } finally {
                        stagedFront.delete()
                        busy = false
                    }
                }
            }

            is PendingScanAction.Append -> {
                scope.launch {
                    busy = true
                    try {
                        if (pages.isEmpty()) {
                            snackbar.showSnackbar(
                                "No page images were returned by the scanner"
                            )
                        } else {
                            runCatching {
                                repository.appendScan(action.documentId, pages)
                            }
                                .onSuccess { count ->
                                    selectedDocumentId = action.documentId
                                    snackbar.showSnackbar(
                                        "$count page${if (count == 1) "" else "s"} added"
                                    )
                                }
                                .onFailure {
                                    snackbar.showSnackbar(
                                        it.message ?: "Could not add pages"
                                    )
                                }
                        }
                    } finally {
                        busy = false
                    }
                }
            }

            is PendingScanAction.Insert -> {
                scope.launch {
                    busy = true
                    try {
                        if (pages.isEmpty()) {
                            snackbar.showSnackbar(
                                "No page images were returned by the scanner"
                            )
                        } else {
                            runCatching {
                                repository.insertScan(
                                    action.documentId,
                                    pages,
                                    action.index
                                )
                            }
                                .onSuccess { count ->
                                    selectedDocumentId = action.documentId
                                    snackbar.showSnackbar(
                                        "$count page${if (count == 1) "" else "s"} inserted"
                                    )
                                }
                                .onFailure {
                                    snackbar.showSnackbar(
                                        it.message ?: "Could not insert pages"
                                    )
                                }
                        }
                    } finally {
                        busy = false
                    }
                }
            }

            is PendingScanAction.Retake -> {
                val page = pages.firstOrNull()
                if (page == null) {
                    scope.launch {
                        snackbar.showSnackbar("No page image was returned by the scanner")
                    }
                } else {
                    scope.launch {
                        busy = true
                        try {
                            runCatching {
                                repository.replacePageFromUri(
                                    action.documentId,
                                    action.pageId,
                                    page
                                )
                            }
                                .onSuccess {
                                    selectedDocumentId = action.documentId
                                    snackbar.showSnackbar("Page retaken")
                                }
                                .onFailure {
                                    snackbar.showSnackbar(
                                        it.message ?: "Could not retake page"
                                    )
                                }
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                try {
                    runCatching {
                        repository.importPdf(uri, displayName(context, uri))
                    }
                        .onSuccess { selectedDocumentId = it }
                        .onFailure {
                            snackbar.showSnackbar(it.message ?: "Could not import PDF")
                        }
                } finally {
                    busy = false
                }
            }
        }
    }

    androidx.compose.material3.Scaffold(
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        val id = selectedDocumentId
        if (id == null) {
            LibraryScreen(
                repository = repository,
                contentPadding = padding,
                busy = busy,
                onOpenDocument = { selectedDocumentId = it },
                onScan = { mode ->
                    pendingScanAction = PendingScanAction.NewDocument(mode)
                    startModeScanner(
                        activity = activity,
                        mode = mode,
                        forceSinglePage = mode == ScanMode.ID_CARD,
                        launcher = scannerLauncher,
                        onFailure = { error ->
                            pendingScanAction = null
                            scope.launch {
                                snackbar.showSnackbar(
                                    error.message ?: "Scanner unavailable"
                                )
                            }
                        }
                    )
                },
                onImportPdf = {
                    pdfLauncher.launch(arrayOf("application/pdf"))
                },
                onMessage = { message ->
                    scope.launch { snackbar.showSnackbar(message) }
                }
            )
        } else {
            DocumentScreen(
                documentId = id,
                repository = repository,
                contentPadding = padding,
                onBack = { selectedDocumentId = null },
                onDeleted = { selectedDocumentId = null },
                onAddPages = { mode ->
                    pendingScanAction = PendingScanAction.Append(id, mode)
                    startModeScanner(
                        activity = activity,
                        mode = mode,
                        forceSinglePage = mode == ScanMode.ID_CARD,
                        launcher = scannerLauncher,
                        onFailure = { error ->
                            pendingScanAction = null
                            scope.launch {
                                snackbar.showSnackbar(
                                    error.message ?: "Scanner unavailable"
                                )
                            }
                        }
                    )
                },
                onInsertPages = { index, mode ->
                    pendingScanAction = PendingScanAction.Insert(id, index, mode)
                    startModeScanner(
                        activity = activity,
                        mode = mode,
                        forceSinglePage = mode == ScanMode.ID_CARD,
                        launcher = scannerLauncher,
                        onFailure = { error ->
                            pendingScanAction = null
                            scope.launch {
                                snackbar.showSnackbar(
                                    error.message ?: "Scanner unavailable"
                                )
                            }
                        }
                    )
                },
                onRetakePage = { pageId, mode ->
                    pendingScanAction = PendingScanAction.Retake(id, pageId, mode)
                    startModeScanner(
                        activity = activity,
                        mode = mode,
                        forceSinglePage = true,
                        launcher = scannerLauncher,
                        onFailure = { error ->
                            pendingScanAction = null
                            scope.launch {
                                snackbar.showSnackbar(
                                    error.message ?: "Scanner unavailable"
                                )
                            }
                        }
                    )
                },
                onMessage = { message ->
                    scope.launch { snackbar.showSnackbar(message) }
                }
            )
        }
    }
}

private fun startModeScanner(
    activity: Activity,
    mode: ScanMode,
    forceSinglePage: Boolean,
    launcher: ActivityResultLauncher<IntentSenderRequest>,
    onFailure: (Throwable) -> Unit
) {
    val profile = ScanModeProfiles.forMode(mode)
    val builder = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setResultFormats(
            GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
            GmsDocumentScannerOptions.RESULT_FORMAT_PDF
        )
        .setScannerMode(
            if (mode == ScanMode.PHOTO) {
                GmsDocumentScannerOptions.SCANNER_MODE_BASE_WITH_FILTER
            } else {
                GmsDocumentScannerOptions.SCANNER_MODE_FULL
            }
        )

    val pageLimit = if (forceSinglePage) 1 else profile.pageLimit
    if (pageLimit != null) {
        builder.setPageLimit(pageLimit)
    }

    GmsDocumentScanning.getClient(builder.build())
        .getStartScanIntent(activity)
        .addOnSuccessListener { sender ->
            launcher.launch(IntentSenderRequest.Builder(sender).build())
        }
        .addOnFailureListener(onFailure)
}

private suspend fun stageCapture(context: Context, uri: Uri): File =
    withContext(Dispatchers.IO) {
        val destination = File(
            context.cacheDir,
            "scan-id-front-${UUID.randomUUID()}.jpg"
        )
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to read ID-card front" }
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        destination
    }
