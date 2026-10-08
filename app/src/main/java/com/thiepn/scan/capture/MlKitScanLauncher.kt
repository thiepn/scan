package com.thiepn.scan.capture

import android.app.Activity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.thiepn.scan.data.ScanMode
import com.thiepn.scan.data.ScanModeProfiles

internal fun startModeScanner(
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
