package com.thiepn.scan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thiepn.scan.data.CleanupKind
import com.thiepn.scan.data.CleanupStroke
import com.thiepn.scan.data.CleanupSuggestion
import com.thiepn.scan.data.CropQuadCodec
import com.thiepn.scan.data.GeometryRotation
import com.thiepn.scan.data.NormalizedPoint
import com.thiepn.scan.data.PageCleanupRecipe
import com.thiepn.scan.data.PageCleanupRecipeCodec
import com.thiepn.scan.data.PageEntity
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun CleanupEditorDialog(
    page: PageEntity,
    suggestions: List<CleanupSuggestion>,
    detectingSuggestions: Boolean,
    onDetectSuggestions: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (PageCleanupRecipe) -> Unit
) {
    var recipe by remember(page.id, page.cleanupRecipe) {
        mutableStateOf(PageCleanupRecipeCodec.decode(page.cleanupRecipe))
    }
    var showBefore by remember { mutableStateOf(false) }
    var brushRadius by remember { mutableStateOf(0.025f) }
    val activePoints = remember { mutableStateListOf<NormalizedPoint>() }

    val displayedCleanup = if (showBefore) {
        null
    } else {
        PageCleanupRecipeCodec.encode(recipe)
    }
    val acceptedKeys = recipe.strokes.map(::strokeKey).toSet()
    val pendingSuggestions = suggestions.filter {
        strokeKey(it.stroke) !in acceptedKeys
    }
    val strongSuggestions = pendingSuggestions.filter {
        it.stroke.confidence >= 0.82f
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Smart cleanup") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 680.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Brush over fingers, objects, handwriting, marks, holes, or stains. Scan reconstructs the masked area from nearby page texture.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showBefore = !showBefore }
                    ) {
                        Text(if (showBefore) "Show cleaned" else "Show original")
                    }
                    OutlinedButton(
                        onClick = {
                            if (recipe.strokes.isNotEmpty()) {
                                recipe = recipe.copy(
                                    strokes = recipe.strokes.dropLast(1)
                                )
                            }
                        },
                        enabled = recipe.strokes.isNotEmpty()
                    ) {
                        Text("Undo")
                    }
                    OutlinedButton(
                        onClick = { recipe = PageCleanupRecipe() },
                        enabled = recipe.strokes.isNotEmpty()
                    ) {
                        Text("Clear")
                    }
                }

                CleanupBrushPreview(
                    page = page,
                    cleanupRecipe = displayedCleanup,
                    activePoints = activePoints,
                    brushRadius = brushRadius,
                    enabled = !showBefore,
                    onStrokeFinished = { points ->
                        if (points.isNotEmpty()) {
                            recipe = recipe.copy(
                                strokes = recipe.strokes + CleanupStroke(
                                    kind = CleanupKind.MANUAL,
                                    points = points,
                                    radius = brushRadius,
                                    confidence = 1f
                                )
                            ).normalized()
                        }
                    }
                )

                Text(
                    "Brush size ${(brushRadius * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = brushRadius,
                    onValueChange = { brushRadius = it },
                    valueRange = 0.008f..0.075f,
                    enabled = !showBefore
                )

                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Automatic suggestions",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(
                        onClick = onDetectSuggestions,
                        enabled = !detectingSuggestions
                    ) {
                        Text(if (suggestions.isEmpty()) "Detect" else "Rescan")
                    }
                }
                if (detectingSuggestions) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (strongSuggestions.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            recipe = recipe.copy(
                                strokes = recipe.strokes +
                                    strongSuggestions.map { it.stroke }
                            ).normalized()
                        }
                    ) {
                        Text(
                            "Accept ${strongSuggestions.size} strong suggestion" +
                                if (strongSuggestions.size == 1) "" else "s"
                        )
                    }
                }

                if (!detectingSuggestions && suggestions.isEmpty()) {
                    Text(
                        "Detection is conservative. Nothing is removed until you accept a suggestion.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                pendingSuggestions.forEach { suggestion ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                suggestion.label,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "${(suggestion.stroke.confidence * 100f).roundToInt()}% confidence",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = {
                                recipe = recipe.copy(
                                    strokes = recipe.strokes + suggestion.stroke
                                ).normalized()
                            }
                        ) {
                            Text("Accept")
                        }
                    }
                }

                if (recipe.strokes.isNotEmpty()) {
                    HorizontalDivider()
                    val manual = recipe.strokes.count {
                        it.kind == CleanupKind.MANUAL
                    }
                    val automatic = recipe.strokes.size - manual
                    Text(
                        buildString {
                            append("${recipe.strokes.size} cleanup mask")
                            if (recipe.strokes.size != 1) append('s')
                            if (manual > 0) append(" · $manual manual")
                            if (automatic > 0) append(" · $automatic suggested")
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(recipe.normalized()) }) {
                Text("Save cleanup")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CleanupBrushPreview(
    page: PageEntity,
    cleanupRecipe: String?,
    activePoints: MutableList<NormalizedPoint>,
    brushRadius: Float,
    enabled: Boolean,
    onStrokeFinished: (List<NormalizedPoint>) -> Unit
) {
    val displayAspect = remember(
        page.width,
        page.height,
        page.cropQuad,
        page.rotationDegrees
    ) {
        estimatedDisplayAspect(page)
    }
    val previewColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .pointerInput(page.id, enabled, brushRadius, displayAspect) {
                if (!enabled) return@pointerInput
                fun map(position: Offset): NormalizedPoint? {
                    val boxWidth = size.width.toFloat()
                    val boxHeight = size.height.toFloat()
                    if (boxWidth <= 1f || boxHeight <= 1f) return null

                    val boxAspect = boxWidth / boxHeight
                    val imageWidth: Float
                    val imageHeight: Float
                    val originX: Float
                    val originY: Float
                    if (boxAspect > displayAspect) {
                        imageHeight = boxHeight
                        imageWidth = imageHeight * displayAspect
                        originX = (boxWidth - imageWidth) / 2f
                        originY = 0f
                    } else {
                        imageWidth = boxWidth
                        imageHeight = imageWidth / displayAspect
                        originX = 0f
                        originY = (boxHeight - imageHeight) / 2f
                    }
                    if (
                        position.x !in originX..(originX + imageWidth) ||
                        position.y !in originY..(originY + imageHeight)
                    ) {
                        return null
                    }
                    val displayPoint = NormalizedPoint(
                        x = ((position.x - originX) / imageWidth)
                            .coerceIn(0f, 1f),
                        y = ((position.y - originY) / imageHeight)
                            .coerceIn(0f, 1f)
                    )
                    return GeometryRotation.displayToSource(
                        displayPoint,
                        page.rotationDegrees
                    ).clamped()
                }

                detectDragGestures(
                    onDragStart = { offset ->
                        activePoints.clear()
                        map(offset)?.let(activePoints::add)
                    },
                    onDrag = { change, _ ->
                        map(change.position)?.let { point ->
                            val last = activePoints.lastOrNull()
                            if (
                                last == null ||
                                hypot(
                                    (point.x - last.x).toDouble(),
                                    (point.y - last.y).toDouble()
                                ) >= 0.003
                            ) {
                                activePoints.add(point)
                            }
                        }
                    },
                    onDragCancel = { activePoints.clear() },
                    onDragEnd = {
                        val completed = activePoints.toList()
                        activePoints.clear()
                        onStrokeFinished(completed)
                    }
                )
            }
    ) {
        FileImage(
            path = page.imagePath,
            modifier = Modifier.matchParentSize(),
            maxDecodeEdge = 1200,
            rotationDegrees = page.rotationDegrees,
            cropQuad = page.cropQuad,
            visualRecipe = page.visualRecipe,
            cleanupRecipe = cleanupRecipe,
            contentDescription = "Cleanup preview"
        )

        Canvas(Modifier.matchParentSize()) {
            if (activePoints.isEmpty()) return@Canvas

            val boxAspect = size.width / size.height
            val imageWidth: Float
            val imageHeight: Float
            val originX: Float
            val originY: Float
            if (boxAspect > displayAspect) {
                imageHeight = size.height
                imageWidth = imageHeight * displayAspect
                originX = (size.width - imageWidth) / 2f
                originY = 0f
            } else {
                imageWidth = size.width
                imageHeight = imageWidth / displayAspect
                originX = 0f
                originY = (size.height - imageHeight) / 2f
            }

            val displayPoints = activePoints.map {
                GeometryRotation.sourceToDisplay(
                    it,
                    page.rotationDegrees
                )
            }.map {
                Offset(
                    x = originX + it.x * imageWidth,
                    y = originY + it.y * imageHeight
                )
            }
            val strokeWidth = brushRadius *
                min(imageWidth, imageHeight) * 2f

            if (displayPoints.size == 1) {
                drawCircle(
                    color = previewColor.copy(alpha = 0.45f),
                    radius = strokeWidth / 2f,
                    center = displayPoints.first()
                )
            } else {
                displayPoints.zipWithNext().forEach { (a, b) ->
                    drawLine(
                        color = previewColor.copy(alpha = 0.45f),
                        start = a,
                        end = b,
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}

private fun estimatedDisplayAspect(page: PageEntity): Float {
    val quad = CropQuadCodec.decode(page.cropQuad)
    val sourceWidth = page.width.toFloat().coerceAtLeast(1f)
    val sourceHeight = page.height.toFloat().coerceAtLeast(1f)

    fun distance(a: NormalizedPoint, b: NormalizedPoint): Float =
        hypot(
            ((a.x - b.x) * sourceWidth).toDouble(),
            ((a.y - b.y) * sourceHeight).toDouble()
        ).toFloat()

    val unrotatedWidth = max(
        distance(quad.topLeft, quad.topRight),
        distance(quad.bottomLeft, quad.bottomRight)
    ).coerceAtLeast(1f)
    val unrotatedHeight = max(
        distance(quad.topLeft, quad.bottomLeft),
        distance(quad.topRight, quad.bottomRight)
    ).coerceAtLeast(1f)

    val unrotatedAspect = unrotatedWidth / unrotatedHeight
    return when ((page.rotationDegrees % 360 + 360) % 360) {
        90, 270 -> (1f / unrotatedAspect).coerceAtLeast(0.05f)
        else -> unrotatedAspect.coerceAtLeast(0.05f)
    }
}

private fun strokeKey(stroke: CleanupStroke): String {
    val normalized = stroke.normalized()
    val first = normalized.points.firstOrNull() ?: return normalized.kind.name
    val last = normalized.points.lastOrNull() ?: first
    return listOf(
        normalized.kind.name,
        (first.x * 100f).roundToInt(),
        (first.y * 100f).roundToInt(),
        (last.x * 100f).roundToInt(),
        (last.y * 100f).roundToInt(),
        (normalized.radius * 100f).roundToInt()
    ).joinToString(":")
}
