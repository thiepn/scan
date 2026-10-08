package com.thiepn.scan.capture

import android.content.Intent
import android.net.Uri
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Anti-corruption seam around Google's external scanner result.
 *
 * The UI consumes a stable local model instead of depending directly on
 * ML Kit result types. Empty or cancelled results are deliberately represented
 * as empty payloads, never as saved documents.
 */
internal data class ScanCapturePayload(
    val pageUris: List<Uri>,
    val pdfUri: Uri?
) {
    val hasContent: Boolean get() = pageUris.isNotEmpty() || pdfUri != null

    companion object {
        fun fromScannerIntent(data: Intent?): ScanCapturePayload {
            val result = GmsDocumentScanningResult.fromActivityResultIntent(data)
            return ScanCapturePayload(
                pageUris = result?.pages?.map { it.imageUri }.orEmpty(),
                pdfUri = result?.pdf?.uri
            )
        }
    }
}
