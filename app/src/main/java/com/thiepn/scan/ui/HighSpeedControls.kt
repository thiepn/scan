package com.thiepn.scan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thiepn.scan.data.CaptureSessionEntity
import com.thiepn.scan.data.CaptureSessionStatus
import com.thiepn.scan.data.progressFraction

@Composable
fun HighSpeedSessionCard(
    session: CaptureSessionEntity,
    onRetryFailures: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = runCatching {
        CaptureSessionStatus.valueOf(session.status)
    }.getOrDefault(CaptureSessionStatus.PROCESSING)

    Card(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Rapid capture",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    statusLabel(status),
                    style = MaterialTheme.typography.labelMedium,
                    color = when (status) {
                        CaptureSessionStatus.FAILED ->
                            MaterialTheme.colorScheme.error
                        CaptureSessionStatus.PAUSED ->
                            MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            }

            if (session.capturedCount > 0) {
                LinearProgressIndicator(
                    progress = { session.progressFraction },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${session.processedCount}/${session.capturedCount} captured frames processed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val details = buildList {
                if (session.duplicateCount > 0) {
                    add(
                        "${session.duplicateCount} duplicate" +
                            if (session.duplicateCount == 1) "" else "s" +
                            " suppressed"
                    )
                }
                if (session.lowQualityCount > 0) {
                    add(
                        "${session.lowQualityCount} low-quality page" +
                            if (session.lowQualityCount == 1) "" else "s" +
                            " flagged"
                    )
                }
                if (session.failedCount > 0) {
                    add(
                        "${session.failedCount} failed page" +
                            if (session.failedCount == 1) "" else "s"
                    )
                }
            }
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            session.pausedReason?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            if (
                status == CaptureSessionStatus.FAILED &&
                session.failedCount > 0
            ) {
                TextButton(onClick = onRetryFailures) {
                    Text("Retry failed pages")
                }
            }
        }
    }
}

private fun statusLabel(status: CaptureSessionStatus): String =
    when (status) {
        CaptureSessionStatus.CAPTURING -> "Capturing"
        CaptureSessionStatus.PROCESSING -> "Processing"
        CaptureSessionStatus.PAUSED -> "Paused"
        CaptureSessionStatus.COMPLETE -> "Complete"
        CaptureSessionStatus.INTERRUPTED -> "Recovering"
        CaptureSessionStatus.FAILED -> "Needs attention"
    }
