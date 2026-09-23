package com.thiepn.scan.data

data class HighSpeedCaptureResult(
    val documentId: String,
    val sessionId: String,
    val capturedInBatch: Int
)

val CaptureSessionEntity.progressFraction: Float
    get() = if (capturedCount <= 0) {
        0f
    } else {
        (processedCount.toFloat() / capturedCount.toFloat()).coerceIn(0f, 1f)
    }

val CaptureSessionEntity.isTerminal: Boolean
    get() = status == CaptureSessionStatus.COMPLETE.name ||
        status == CaptureSessionStatus.FAILED.name
