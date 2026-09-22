package com.thiepn.scan

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import com.thiepn.scan.data.ScanRepository
import com.thiepn.scan.ui.DocumentScreen
import com.thiepn.scan.ui.LibraryScreen
import com.thiepn.scan.ui.ScanTheme
import com.thiepn.scan.util.displayName
import kotlinx.coroutines.launch

private sealed interface PendingScanAction {
    data object NewDocument : PendingScanAction
    data class Append(val documentId: String) : PendingScanAction
    data class Insert(val documentId: String, val index: Int) : PendingScanAction
    data class Retake(val documentId: String, val pageId: String) : PendingScanAction
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

    val scannerOptions = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }
    val scanner = remember(scannerOptions) { GmsDocumentScanning.getClient(scannerOptions) }
    val singlePageScannerOptions = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(1)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }
    val singlePageScanner = remember(singlePageScannerOptions) {
        GmsDocumentScanning.getClient(singlePageScannerOptions)
    }

    val scannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val action = pendingScanAction
        pendingScanAction = null

        if (result.resultCode == Activity.RESULT_OK && action != null) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pages = scan?.pages?.map { it.imageUri }.orEmpty()
            val pdf = scan?.pdf?.uri

            scope.launch {
                busy = true
                try {
                    when (action) {
                        PendingScanAction.NewDocument -> {
                            if (pages.isNotEmpty() || pdf != null) {
                                runCatching { repository.ingestScan(pages, pdf) }
                                    .onSuccess { selectedDocumentId = it }
                                    .onFailure {
                                        snackbar.showSnackbar(it.message ?: "Could not save scan")
                                    }
                            }
                        }

                        is PendingScanAction.Append -> {
                            if (pages.isEmpty()) {
                                snackbar.showSnackbar("No page images were returned by the scanner")
                            } else {
                                runCatching { repository.appendScan(action.documentId, pages) }
                                    .onSuccess { count ->
                                        selectedDocumentId = action.documentId
                                        snackbar.showSnackbar(
                                            "$count page${if (count == 1) "" else "s"} added"
                                        )
                                    }
                                    .onFailure {
                                        snackbar.showSnackbar(it.message ?: "Could not add pages")
                                    }
                            }
                        }

                        is PendingScanAction.Insert -> {
                            if (pages.isEmpty()) {
                                snackbar.showSnackbar("No page images were returned by the scanner")
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
                                        snackbar.showSnackbar(it.message ?: "Could not insert pages")
                                    }
                            }
                        }

                        is PendingScanAction.Retake -> {
                            val page = pages.firstOrNull()
                            if (page == null) {
                                snackbar.showSnackbar("No page image was returned by the scanner")
                            } else {
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
                                        snackbar.showSnackbar(it.message ?: "Could not retake page")
                                    }
                            }
                        }
                    }
                } finally {
                    busy = false
                }
            }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                runCatching { repository.importPdf(uri, displayName(context, uri)) }
                    .onSuccess { selectedDocumentId = it }
                    .onFailure { snackbar.showSnackbar(it.message ?: "Could not import PDF") }
                busy = false
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
                onScan = {
                    pendingScanAction = PendingScanAction.NewDocument
                    scanner.getStartScanIntent(activity)
                        .addOnSuccessListener { sender ->
                            scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
                        }
                        .addOnFailureListener { error ->
                            scope.launch { snackbar.showSnackbar(error.message ?: "Scanner unavailable") }
                        }
                },
                onImportPdf = { pdfLauncher.launch(arrayOf("application/pdf")) },
                onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } }
            )
        } else {
            DocumentScreen(
                documentId = id,
                repository = repository,
                contentPadding = padding,
                onBack = { selectedDocumentId = null },
                onDeleted = { selectedDocumentId = null },
                onAddPages = {
                    pendingScanAction = PendingScanAction.Append(id)
                    scanner.getStartScanIntent(activity)
                        .addOnSuccessListener { sender ->
                            scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
                        }
                        .addOnFailureListener { error ->
                            pendingScanAction = null
                            scope.launch {
                                snackbar.showSnackbar(error.message ?: "Scanner unavailable")
                            }
                        }
                },
                onInsertPages = { index ->
                    pendingScanAction = PendingScanAction.Insert(id, index)
                    scanner.getStartScanIntent(activity)
                        .addOnSuccessListener { sender ->
                            scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
                        }
                        .addOnFailureListener { error ->
                            pendingScanAction = null
                            scope.launch {
                                snackbar.showSnackbar(error.message ?: "Scanner unavailable")
                            }
                        }
                },
                onRetakePage = { pageId ->
                    pendingScanAction = PendingScanAction.Retake(id, pageId)
                    singlePageScanner.getStartScanIntent(activity)
                        .addOnSuccessListener { sender ->
                            scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
                        }
                        .addOnFailureListener { error ->
                            pendingScanAction = null
                            scope.launch {
                                snackbar.showSnackbar(error.message ?: "Scanner unavailable")
                            }
                        }
                },
                onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } }
            )
        }
    }
}
