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
    var selectedDocumentId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

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

    val scannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pages = scan?.pages?.map { it.imageUri }.orEmpty()
            val pdf = scan?.pdf?.uri
            if (pages.isNotEmpty() || pdf != null) {
                scope.launch {
                    busy = true
                    runCatching { repository.ingestScan(pages, pdf) }
                        .onSuccess { selectedDocumentId = it }
                        .onFailure { snackbar.showSnackbar(it.message ?: "Could not save scan") }
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
                onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } }
            )
        }
    }
}
