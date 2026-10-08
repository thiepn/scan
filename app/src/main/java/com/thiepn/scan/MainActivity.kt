package com.thiepn.scan

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thiepn.scan.capture.ScanCapturePayload
import com.thiepn.scan.data.DocumentSecuritySettingsCodec
import com.thiepn.scan.data.ScanMode
import com.thiepn.scan.data.ScanRepository
import com.thiepn.scan.capture.PendingScanAction
import com.thiepn.scan.capture.PendingScanActionCodec
import com.thiepn.scan.capture.ScanImportViewModel
import com.thiepn.scan.capture.ScanImportResult
import com.thiepn.scan.capture.startModeScanner
import com.thiepn.scan.navigation.rememberScanNavigationState
import com.thiepn.scan.security.requestVaultAuthentication
import com.thiepn.scan.ui.DocumentScreen
import com.thiepn.scan.ui.LibraryScreen
import com.thiepn.scan.ui.ScanTheme
import com.thiepn.scan.ui.VaultLockedScreen
import com.thiepn.scan.util.displayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


private val PendingScanActionSaver =
    Saver<PendingScanAction?, String>(
        save = { action ->
            action?.let(PendingScanActionCodec::encode)
        },
        restore = PendingScanActionCodec::decode
    )

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as ScanApplication).graph.repository
        setContent {
            ScanTheme {
                Surface(Modifier.fillMaxSize()) {
                    ScanApp(
                        repository = repository,
                        authenticate = { onSuccess, onError ->
                            requestVaultAuthentication(
                                activity = this@MainActivity,
                                onSuccess = onSuccess,
                                onError = onError
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        (application as ScanApplication)
            .graph
            .vault
            .lockOnBackgroundAsync()
        super.onUserLeaveHint()
    }
}

@Composable
private fun ScanApp(
    repository: ScanRepository,
    authenticate: (
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) -> Unit
) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val navigation = rememberScanNavigationState()
    val importModel: ScanImportViewModel = viewModel(
        factory = remember(repository) { ScanImportViewModel.factory(repository) }
    )
    LaunchedEffect(importModel) {
        importModel.results.collect { result ->
            when (result) {
                is ScanImportResult.Success ->
                    navigation.openDocument(result.documentId)
                is ScanImportResult.Failure ->
                    snackbar.showSnackbar(result.message)
            }
        }
    }
    var busy by remember { mutableStateOf(false) }
    var vaultUnlockBusy by remember { mutableStateOf(false) }
    val vaultState by repository.observeVaultState()
        .collectAsStateWithLifecycle()
    var pendingScanAction by rememberSaveable(
        stateSaver = PendingScanActionSaver
    ) {
        mutableStateOf<PendingScanAction?>(null)
    }

    lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>

    fun continueRapidCapture(
        documentId: String,
        sessionId: String,
        mode: ScanMode
    ) {
        pendingScanAction = PendingScanAction.RapidContinue(
            documentId = documentId,
            mode = mode,
            sessionId = sessionId
        )
        startModeScanner(
            activity = activity,
            mode = mode,
            forceSinglePage = false,
            rapidCapture = true,
            launcher = scannerLauncher,
            onFailure = { error ->
                pendingScanAction = null
                scope.launch {
                    runCatching {
                        repository.finishHighSpeedCaptureSession(sessionId)
                    }
                    navigation.openDocument(documentId)
                    snackbar.showSnackbar(
                        error.message
                            ?: "Continuous scanner stopped; captured pages were preserved."
                    )
                }
            }
        )
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
            when (action) {
                is PendingScanAction.IdBack -> {
                    navigation.openDocument(action.documentId)
                    scope.launch {
                        snackbar.showSnackbar(
                            "Front saved. The back can be added later."
                        )
                    }
                }

                is PendingScanAction.RapidContinue -> {
                    scope.launch {
                        runCatching {
                            repository.finishHighSpeedCaptureSession(action.sessionId)
                        }
                            .onFailure {
                                snackbar.showSnackbar(
                                    it.message ?: "Could not finish rapid capture"
                                )
                            }
                        navigation.openDocument(action.documentId)
                    }
                }

                else -> Unit
            }
            return@rememberLauncherForActivityResult
        }

        val capture = ScanCapturePayload.fromScannerIntent(result.data)
        val pages = capture.pageUris
        val pdf = capture.pdfUri

        when (action) {
            is PendingScanAction.NewDocument -> {
                if (action.mode == ScanMode.ID_CARD) {
                    val front = pages.firstOrNull()
                    if (front == null) {
                        scope.launch {
                            snackbar.showSnackbar(
                                "No ID-card image was returned"
                            )
                        }
                    } else {
                        scope.launch {
                            busy = true
                            val documentId = runCatching {
                                repository.ingestScan(
                                    pageUris = listOf(front),
                                    pdfUri = null,
                                    scanMode = ScanMode.ID_CARD,
                                    awaitProcessing = true
                                )
                            }.getOrElse { error ->
                                busy = false
                                snackbar.showSnackbar(
                                    error.message
                                        ?: "Could not save ID-card front"
                                )
                                return@launch
                            }
                            busy = false
                            navigation.openDocument(documentId)
                            pendingScanAction =
                                PendingScanAction.IdBack(
                                    documentId = documentId
                                )
                            launch {
                                snackbar.showSnackbar(
                                    "Front saved. Now scan the back."
                                )
                            }
                            startModeScanner(
                                activity = activity,
                                mode = ScanMode.ID_CARD,
                                forceSinglePage = true,
                                launcher = scannerLauncher,
                                onFailure = { error ->
                                    pendingScanAction = null
                                    navigation.openDocument(documentId)
                                    scope.launch {
                                        snackbar.showSnackbar(
                                            error.message
                                                ?: "Back scanner unavailable; front was saved."
                                        )
                                    }
                                }
                            )
                        }
                    }
                } else {
                    scope.launch {
                        busy = true
                        try {
                            if (pages.isNotEmpty() || pdf != null) {
                                if (action.rapid) {
                                    if (pages.isEmpty()) {
                                        snackbar.showSnackbar(
                                            "Rapid capture requires page images"
                                        )
                                    } else {
                                        runCatching {
                                            repository.ingestHighSpeedCapture(
                                                pageUris = pages,
                                                scanMode = action.mode
                                            )
                                        }
                                            .onSuccess { capture ->
                                                continueRapidCapture(
                                                    documentId = capture.documentId,
                                                    sessionId = capture.sessionId,
                                                    mode = action.mode
                                                )
                                            }
                                            .onFailure {
                                                snackbar.showSnackbar(
                                                    it.message
                                                        ?: "Could not start rapid capture"
                                                )
                                            }
                                    }
                                } else {
                                    runCatching {
                                        repository.ingestScan(
                                            pageUris = pages,
                                            pdfUri = pdf,
                                            scanMode = action.mode
                                        )
                                    }
                                        .onSuccess { navigation.openDocument(it) }
                                        .onFailure {
                                            snackbar.showSnackbar(
                                                it.message ?: "Could not save scan"
                                            )
                                        }
                                }
                            }
                        } finally {
                            busy = false
                        }
                    }
                }
            }

            is PendingScanAction.RapidExistingStart -> {
                scope.launch {
                    busy = true
                    try {
                        if (pages.isEmpty()) {
                            snackbar.showSnackbar(
                                "No page images were returned by the scanner"
                            )
                        } else {
                            runCatching {
                                repository.startHighSpeedCaptureForDocument(
                                    documentId = action.documentId,
                                    pageUris = pages
                                )
                            }
                                .onSuccess { capture ->
                                    continueRapidCapture(
                                        documentId = capture.documentId,
                                        sessionId = capture.sessionId,
                                        mode = action.mode
                                    )
                                }
                                .onFailure {
                                    snackbar.showSnackbar(
                                        it.message ?: "Could not start rapid capture"
                                    )
                                }
                        }
                    } finally {
                        busy = false
                    }
                }
            }

            is PendingScanAction.RapidContinue -> {
                scope.launch {
                    busy = true
                    try {
                        if (pages.isEmpty()) {
                            repository.finishHighSpeedCaptureSession(
                                action.sessionId
                            )
                            navigation.openDocument(action.documentId)
                        } else {
                            runCatching {
                                repository.appendHighSpeedCapture(
                                    sessionId = action.sessionId,
                                    pageUris = pages
                                )
                            }
                                .onSuccess {
                                    continueRapidCapture(
                                        documentId = action.documentId,
                                        sessionId = action.sessionId,
                                        mode = action.mode
                                    )
                                }
                                .onFailure { error ->
                                    runCatching {
                                        repository.finishHighSpeedCaptureSession(
                                            action.sessionId
                                        )
                                    }
                                    navigation.openDocument(action.documentId)
                                    snackbar.showSnackbar(
                                        error.message
                                            ?: "Rapid capture stopped; saved pages are processing."
                                    )
                                }
                        }
                    } finally {
                        busy = false
                    }
                }
            }

            is PendingScanAction.IdBack -> {
                val back = pages.firstOrNull()
                scope.launch {
                    busy = true
                    try {
                        if (back == null) {
                            navigation.openDocument(action.documentId)
                            snackbar.showSnackbar(
                                "Front saved. The back can be added later."
                            )
                        } else {
                            runCatching {
                                repository.appendScan(
                                    action.documentId,
                                    listOf(back)
                                )
                            }
                                .onSuccess {
                                    navigation.openDocument(action.documentId)
                                    snackbar.showSnackbar(
                                        "ID card front and back saved"
                                    )
                                }
                                .onFailure { error ->
                                    navigation.openDocument(action.documentId)
                                    snackbar.showSnackbar(
                                        error.message
                                            ?: "Front is saved, but the back could not be added."
                                    )
                                }
                        }
                    } finally {
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
                                    navigation.openDocument(action.documentId)
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
                                    navigation.openDocument(action.documentId)
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
                                    navigation.openDocument(action.documentId)
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
            importModel.importPdf(uri, displayName(context, uri))
        }
    }

    androidx.activity.compose.BackHandler(
        enabled = navigation.documentId != null
    ) {
        navigation.navigateBack()
    }

    androidx.compose.material3.Scaffold(
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        val id = navigation.documentId
        if (id == null) {
            LibraryScreen(
                repository = repository,
                contentPadding = padding,
                busy = busy || importModel.isImporting,
                onOpenDocument = { navigation.openDocument(it) },
                onScan = { mode, rapid ->
                    pendingScanAction = PendingScanAction.NewDocument(
                        mode = mode,
                        rapid = rapid
                    )
                    startModeScanner(
                        activity = activity,
                        mode = mode,
                        forceSinglePage = mode == ScanMode.ID_CARD,
                        rapidCapture = rapid,
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
                    if (!busy && !importModel.isImporting) {
                        pdfLauncher.launch(arrayOf("application/pdf"))
                    }
                },
                onMessage = { message ->
                    scope.launch { snackbar.showSnackbar(message) }
                }
            )
        } else {
            val selectedDocumentFlow = remember(id) {
                repository.observeDocument(id)
            }
            val selectedDocument by selectedDocumentFlow
                .collectAsStateWithLifecycle(initialValue = null)
            val security = DocumentSecuritySettingsCodec.decode(
                selectedDocument?.securityRecipe
            )
            val protectedDocument =
                id in vaultState.protectedDocumentIds
            val lockedDocument =
                id in vaultState.lockedDocumentIds

            DisposableEffect(
                id,
                protectedDocument,
                security.blockScreenshots
            ) {
                if (
                    protectedDocument &&
                    security.blockScreenshots
                ) {
                    activity.window.addFlags(
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    activity.window.clearFlags(
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                }
                onDispose {
                    activity.window.clearFlags(
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                }
            }

            if (protectedDocument && lockedDocument) {
                val vaultPreparing =
                    id in vaultState.busyDocumentIds
                VaultLockedScreen(
                    contentPadding = padding,
                    busy =
                        vaultUnlockBusy || vaultPreparing,
                    integrityWarning =
                        id in
                            vaultState.integrityFailedDocumentIds,
                    onUnlock = {
                        if (
                            !vaultUnlockBusy &&
                            !vaultPreparing
                        ) {
                            vaultUnlockBusy = true
                            authenticate(
                                {
                                    scope.launch {
                                        runCatching {
                                            repository
                                                .unlockVaultDocument(
                                                    id
                                                )
                                        }
                                            .onSuccess { valid ->
                                                if (!valid) {
                                                    snackbar
                                                        .showSnackbar(
                                                            "Unlocked, but the document integrity manifest does not match."
                                                        )
                                                }
                                            }
                                            .onFailure {
                                                snackbar
                                                    .showSnackbar(
                                                        it.message
                                                            ?: "Could not unlock secure document"
                                                    )
                                            }
                                        vaultUnlockBusy = false
                                    }
                                },
                                { message ->
                                    vaultUnlockBusy = false
                                    scope.launch {
                                        snackbar.showSnackbar(
                                            message
                                        )
                                    }
                                }
                            )
                        }
                    },
                    onBack = {
                        navigation.showLibrary()
                    }
                )
            } else {
            DocumentScreen(
                documentId = id,
                repository = repository,
                contentPadding = padding,
                onBack = { navigation.showLibrary() },
                onDeleted = { navigation.showLibrary() },
                onRapidScan = { mode ->
                    pendingScanAction = PendingScanAction.RapidExistingStart(
                        documentId = id,
                        mode = mode
                    )
                    startModeScanner(
                        activity = activity,
                        mode = mode,
                        forceSinglePage = false,
                        rapidCapture = true,
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
}
