package com.thiepn.scan

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.thiepn.scan.data.DocumentSecuritySettingsCodec
import com.thiepn.scan.data.ScanMode
import com.thiepn.scan.data.ScanModeProfiles
import com.thiepn.scan.data.ScanRepository
import com.thiepn.scan.ui.DocumentScreen
import com.thiepn.scan.ui.LibraryScreen
import com.thiepn.scan.ui.ScanTheme
import com.thiepn.scan.ui.VaultLockedScreen
import com.thiepn.scan.util.displayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import java.util.UUID

internal sealed interface PendingScanAction {
    data class NewDocument(
        val mode: ScanMode,
        val rapid: Boolean = false
    ) : PendingScanAction
    data class RapidExistingStart(
        val documentId: String,
        val mode: ScanMode
    ) : PendingScanAction
    data class RapidContinue(
        val documentId: String,
        val mode: ScanMode,
        val sessionId: String
    ) : PendingScanAction
    data class IdBack(
        val documentId: String
    ) : PendingScanAction
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

internal object PendingScanActionCodec {
    private const val VERSION = "1"

    fun encode(action: PendingScanAction): String {
        val fields = when (action) {
            is PendingScanAction.NewDocument ->
                listOf(
                    "NEW",
                    action.mode.name,
                    action.rapid.toString()
                )
            is PendingScanAction.RapidExistingStart ->
                listOf(
                    "RAPID_START",
                    action.documentId,
                    action.mode.name
                )
            is PendingScanAction.RapidContinue ->
                listOf(
                    "RAPID_CONTINUE",
                    action.documentId,
                    action.mode.name,
                    action.sessionId
                )
            is PendingScanAction.IdBack ->
                listOf(
                    "ID_BACK",
                    action.documentId
                )
            is PendingScanAction.Append ->
                listOf(
                    "APPEND",
                    action.documentId,
                    action.mode.name
                )
            is PendingScanAction.Insert ->
                listOf(
                    "INSERT",
                    action.documentId,
                    action.index.toString(),
                    action.mode.name
                )
            is PendingScanAction.Retake ->
                listOf(
                    "RETAKE",
                    action.documentId,
                    action.pageId,
                    action.mode.name
                )
        }
        return (
            listOf(VERSION) + fields
            ).joinToString(".") {
                Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                        it.toByteArray(Charsets.UTF_8)
                    )
            }
    }

    fun decode(encoded: String): PendingScanAction? =
        runCatching {
            val fields = encoded
                .split('.')
                .map {
                    Base64.getUrlDecoder()
                        .decode(it)
                        .toString(Charsets.UTF_8)
                }
            if (
                fields.isEmpty() ||
                fields[0] != VERSION
            ) {
                return@runCatching null
            }

            when (fields.getOrNull(1)) {
                "NEW" ->
                    PendingScanAction.NewDocument(
                        mode = scanMode(fields[2]),
                        rapid = fields[3].toBooleanStrict()
                    )
                "RAPID_START" ->
                    PendingScanAction.RapidExistingStart(
                        documentId = fields[2],
                        mode = scanMode(fields[3])
                    )
                "RAPID_CONTINUE" ->
                    PendingScanAction.RapidContinue(
                        documentId = fields[2],
                        mode = scanMode(fields[3]),
                        sessionId = fields[4]
                    )
                "ID_BACK" ->
                    PendingScanAction.IdBack(
                        documentId = fields[2]
                    )
                "APPEND" ->
                    PendingScanAction.Append(
                        documentId = fields[2],
                        mode = scanMode(fields[3])
                    )
                "INSERT" ->
                    PendingScanAction.Insert(
                        documentId = fields[2],
                        index = fields[3].toInt(),
                        mode = scanMode(fields[4])
                    )
                "RETAKE" ->
                    PendingScanAction.Retake(
                        documentId = fields[2],
                        pageId = fields[3],
                        mode = scanMode(fields[4])
                    )
                else -> null
            }
        }.getOrNull()

    private fun scanMode(value: String): ScanMode =
        ScanMode.entries.first {
            it.name == value
        }
}

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
    var selectedDocumentId by rememberSaveable { mutableStateOf<String?>(null) }
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
                    selectedDocumentId = documentId
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
                    selectedDocumentId = action.documentId
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
                        selectedDocumentId = action.documentId
                    }
                }

                else -> Unit
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
                            selectedDocumentId = documentId
                            pendingScanAction =
                                PendingScanAction.IdBack(
                                    documentId = documentId
                                )
                            snackbar.showSnackbar(
                                "Front saved. Now scan the back."
                            )
                            startModeScanner(
                                activity = activity,
                                mode = ScanMode.ID_CARD,
                                forceSinglePage = true,
                                launcher = scannerLauncher,
                                onFailure = { error ->
                                    pendingScanAction = null
                                    selectedDocumentId = documentId
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
                                        .onSuccess { selectedDocumentId = it }
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
                            selectedDocumentId = action.documentId
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
                                    selectedDocumentId = action.documentId
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
                            selectedDocumentId =
                                action.documentId
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
                                    selectedDocumentId =
                                        action.documentId
                                    snackbar.showSnackbar(
                                        "ID card front and back saved"
                                    )
                                }
                                .onFailure { error ->
                                    selectedDocumentId =
                                        action.documentId
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
                    pdfLauncher.launch(arrayOf("application/pdf"))
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
                VaultLockedScreen(
                    contentPadding = padding,
                    busy = vaultUnlockBusy,
                    integrityWarning =
                        id in
                            vaultState.integrityFailedDocumentIds,
                    onUnlock = {
                        if (!vaultUnlockBusy) {
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
                        selectedDocumentId = null
                    }
                )
            } else {
            DocumentScreen(
                documentId = id,
                repository = repository,
                contentPadding = padding,
                onBack = { selectedDocumentId = null },
                onDeleted = { selectedDocumentId = null },
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


private fun requestVaultAuthentication(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    val biometric = BiometricManager.from(activity)
    val keyguard = activity.getSystemService(
        Context.KEYGUARD_SERVICE
    ) as KeyguardManager
    val executor = ContextCompat.getMainExecutor(activity)

    val canUseBiometric = biometric.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_WEAK
    ) == BiometricManager.BIOMETRIC_SUCCESS
    val canUseCredential = keyguard.isDeviceSecure
    if (!canUseBiometric && !canUseCredential) {
        onError(
            "Set up biometrics or a device screen lock before using the secure vault."
        )
        return
    }

    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult
            ) {
                onSuccess()
            }

            override fun onAuthenticationError(
                errorCode: Int,
                errString: CharSequence
            ) {
                onError(errString.toString())
            }
        }
    )

    val builder = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock secure document")
        .setSubtitle(
            "Authenticate to decrypt this document."
        )

    if (Build.VERSION.SDK_INT >= 30) {
        builder.setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
    } else {
        @Suppress("DEPRECATION")
        builder.setDeviceCredentialAllowed(true)
    }

    prompt.authenticate(builder.build())
}

private fun startModeScanner(
    activity: Activity,
    mode: ScanMode,
    forceSinglePage: Boolean,
    rapidCapture: Boolean = false,
    launcher: ActivityResultLauncher<IntentSenderRequest>,
    onFailure: (Throwable) -> Unit
) {
    val profile = ScanModeProfiles.forMode(mode)
    val builder = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setScannerMode(
            if (mode == ScanMode.PHOTO) {
                GmsDocumentScannerOptions.SCANNER_MODE_BASE_WITH_FILTER
            } else {
                GmsDocumentScannerOptions.SCANNER_MODE_FULL
            }
        )

    if (rapidCapture) {
        builder.setResultFormats(
            GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
        )
    } else {
        builder.setResultFormats(
            GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
            GmsDocumentScannerOptions.RESULT_FORMAT_PDF
        )
    }

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
