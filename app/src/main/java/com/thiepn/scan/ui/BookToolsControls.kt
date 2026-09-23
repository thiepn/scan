package com.thiepn.scan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thiepn.scan.data.BookSpreadAnalysis
import kotlin.math.roundToInt

@Composable
fun BookToolsBar(
    reviewCount: Int,
    splitCount: Int,
    enabled: Boolean,
    onAutoProcess: () -> Unit,
    onReviewNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "Book tools",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            when {
                reviewCount > 0 ->
                    "$reviewCount possible spread${if (reviewCount == 1) "" else "s"} need manual review."
                splitCount > 0 ->
                    "$splitCount original spread${if (splitCount == 1) "" else "s"} preserved for restoration."
                else ->
                    "Wide pages are checked for a center gutter before OCR."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onAutoProcess,
                enabled = enabled
            ) {
                Text("Analyze unsplit pages")
            }
            if (reviewCount > 0) {
                OutlinedButton(
                    onClick = onReviewNext,
                    enabled = enabled
                ) {
                    Text("Review next spread")
                }
            }
        }
    }
}

@Composable
fun BookSpreadReviewDialog(
    pageLabel: String,
    imagePath: String,
    rotationDegrees: Int,
    cropQuad: String?,
    visualRecipe: String?,
    analysis: BookSpreadAnalysis,
    onDismiss: () -> Unit,
    onSplit: (gutterX: Float, dewarp: Boolean) -> Unit
) {
    var gutter by remember(analysis.gutterX) {
        mutableStateOf(analysis.gutterX.coerceIn(0.32f, 0.68f))
    }
    var dewarp by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review $pageLabel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    analysis.reason,
                    style = MaterialTheme.typography.bodyMedium
                )
                val gutterColor = MaterialTheme.colorScheme.primary
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                ) {
                    FileImage(
                        path = imagePath,
                        modifier = Modifier.matchParentSize(),
                        maxDecodeEdge = 900,
                        rotationDegrees = rotationDegrees,
                        cropQuad = cropQuad,
                        visualRecipe = visualRecipe,
                        contentDescription = pageLabel
                    )
                    Canvas(Modifier.matchParentSize()) {
                        val x = size.width * gutter
                        drawLine(
                            color = gutterColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 3f * density
                        )
                    }
                }
                Text(
                    "Spread confidence ${(analysis.confidence * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge
                )
                LinearProgressIndicator(
                    progress = { analysis.confidence.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Gutter ${(gutter * 100f).roundToInt()}% from the left edge",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = gutter,
                    onValueChange = { gutter = it },
                    valueRange = 0.32f..0.68f
                )
                Text(
                    "Move the gutter only when the detected center line is wrong.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = dewarp,
                        onCheckedChange = { dewarp = it }
                    )
                    Column(Modifier.padding(start = 4.dp)) {
                        Text("Flatten page curvature")
                        Text(
                            "Uses a bounded cylindrical mesh warp near the gutter.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSplit(gutter, dewarp) }) {
                Text("Split spread")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Keep single page")
            }
        }
    )
}
