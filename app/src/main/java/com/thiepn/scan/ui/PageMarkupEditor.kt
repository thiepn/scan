package com.thiepn.scan.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.thiepn.scan.data.CropQuadCodec
import com.thiepn.scan.data.NormalizedPoint
import com.thiepn.scan.data.OcrLayoutCodec
import com.thiepn.scan.data.PageEntity
import com.thiepn.scan.data.PageMarkupItem
import com.thiepn.scan.data.PageMarkupKind
import com.thiepn.scan.data.PageMarkupRecipe
import com.thiepn.scan.data.PageMarkupRecipeCodec
import com.thiepn.scan.data.RedactionVerification
import com.thiepn.scan.data.SavedSignatureEntity
import com.thiepn.scan.data.SavedSignatureKind
import com.thiepn.scan.data.SignaturePathCodec
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun PageMarkupEditorDialog(
    page: PageEntity,
    savedSignatures: List<SavedSignatureEntity>,
    onDismiss: () -> Unit,
    onSave: (PageMarkupRecipe) -> Unit,
    onVerify: suspend (PageMarkupRecipe) -> RedactionVerification,
    onSaveSignature: (
        label: String,
        kind: SavedSignatureKind,
        points: List<NormalizedPoint>
    ) -> Unit,
    onDeleteSignature: (String) -> Unit
) {
    var recipe by remember(page.id, page.markupRecipe) {
        mutableStateOf(PageMarkupRecipeCodec.decode(page.markupRecipe))
    }
    var undoStack by remember(page.id) {
        mutableStateOf<List<PageMarkupRecipe>>(emptyList())
    }
    var tool by remember(page.id) { mutableStateOf(PageMarkupKind.FREEHAND) }
    var text by remember(page.id) { mutableStateOf("APPROVED") }
    var strokeWidth by remember(page.id) { mutableStateOf(0.006f) }
    var colorArgb by remember(page.id) { mutableStateOf(AndroidColor.BLACK) }
    var selectedSavedSignatureId by remember(page.id) {
        mutableStateOf<String?>(null)
    }
    var signatureLabel by remember(page.id) { mutableStateOf("") }
    var dragPoints by remember(page.id) {
        mutableStateOf<List<NormalizedPoint>>(emptyList())
    }
    var verification by remember(page.id) {
        mutableStateOf<RedactionVerification?>(null)
    }
    var verifyError by remember(page.id) { mutableStateOf<String?>(null) }
    var verifying by remember(page.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val ocrLayout = remember(page.ocrLayout) {
        OcrLayoutCodec.decode(page.ocrLayout)
    }
    val normalizedRotation = ((page.rotationDegrees % 360) + 360) % 360
    val crop = remember(page.cropQuad) {
        CropQuadCodec.decode(page.cropQuad)
    }
    val cropPoints = crop.points.map {
        it.x * page.width to it.y * page.height
    }
    fun edgeLength(
        a: Pair<Float, Float>,
        b: Pair<Float, Float>
    ): Float = hypot(
        (a.first - b.first).toDouble(),
        (a.second - b.second).toDouble()
    ).toFloat()
    val unrotatedWidth = max(
        edgeLength(cropPoints[0], cropPoints[1]),
        edgeLength(cropPoints[3], cropPoints[2])
    ).roundToInt().coerceAtLeast(1)
    val unrotatedHeight = max(
        edgeLength(cropPoints[0], cropPoints[3]),
        edgeLength(cropPoints[1], cropPoints[2])
    ).roundToInt().coerceAtLeast(1)
    val fallbackWidth = if (normalizedRotation == 90 || normalizedRotation == 270) {
        unrotatedHeight
    } else {
        unrotatedWidth
    }
    val fallbackHeight = if (normalizedRotation == 90 || normalizedRotation == 270) {
        unrotatedWidth
    } else {
        unrotatedHeight
    }
    val sourceWidth = (ocrLayout?.sourceWidth ?: fallbackWidth).coerceAtLeast(1)
    val sourceHeight = (ocrLayout?.sourceHeight ?: fallbackHeight).coerceAtLeast(1)

    fun push(next: PageMarkupRecipe) {
        val normalized = next.normalized()
        if (normalized == recipe) return
        undoStack = undoStack + recipe
        recipe = normalized
        verification = null
        verifyError = null
    }

    fun add(item: PageMarkupItem) {
        push(recipe.copy(items = recipe.items + item))
    }

    fun remove(id: String) {
        push(recipe.copy(items = recipe.items.filterNot { it.id == id }))
    }

    val savedForTool = savedSignatures.filter {
        when (tool) {
            PageMarkupKind.SIGNATURE ->
                it.kind == SavedSignatureKind.SIGNATURE.name
            PageMarkupKind.INITIALS ->
                it.kind == SavedSignatureKind.INITIALS.name
            else -> false
        }
    }
    val selectedSaved = savedForTool.firstOrNull {
        it.id == selectedSavedSignatureId
    }

    val lastDrawnSignature = recipe.items.lastOrNull {
        it.kind == tool &&
            (tool == PageMarkupKind.SIGNATURE ||
                tool == PageMarkupKind.INITIALS) &&
            it.points.size >= 2
    }

    val previewRecipe = remember(recipe) {
        PageMarkupRecipeCodec.encode(recipe)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Annotate, fill, sign & redact") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 760.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "All edits are stored as reversible operations. Secure redaction is flattened into exported PDFs and removed from OCR/search text.",
                    style = MaterialTheme.typography.bodySmall
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PageMarkupKind.entries.forEach { option ->
                        FilterChip(
                            selected = tool == option,
                            onClick = {
                                tool = option
                                selectedSavedSignatureId = null
                                verification = null
                                verifyError = null
                            },
                            label = { Text(option.label) }
                        )
                    }
                }

                MarkupPreview(
                    page = page,
                    recipe = previewRecipe,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    tool = tool,
                    colorArgb = colorArgb,
                    strokeWidth = strokeWidth,
                    text = text,
                    savedSignature = selectedSaved,
                    dragPoints = dragPoints,
                    onDragPointsChange = { dragPoints = it },
                    onCreateItem = ::add
                )

                if (
                    tool == PageMarkupKind.TEXT ||
                    tool == PageMarkupKind.STAMP ||
                    tool == PageMarkupKind.FORM_TEXT ||
                    tool == PageMarkupKind.FORM_DATE
                ) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(4000) },
                        label = {
                            Text(
                                when (tool) {
                                    PageMarkupKind.STAMP -> "Stamp text"
                                    PageMarkupKind.FORM_DATE -> "Date value"
                                    else -> "Text"
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 5
                    )
                }

                Text(
                    "Ink",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "Black" to AndroidColor.BLACK,
                        "Blue" to AndroidColor.rgb(25, 92, 210),
                        "Red" to AndroidColor.rgb(205, 40, 40),
                        "Green" to AndroidColor.rgb(20, 130, 70),
                        "Yellow" to AndroidColor.rgb(242, 196, 36)
                    ).forEach { (label, value) ->
                        FilterChip(
                            selected = colorArgb == value,
                            onClick = { colorArgb = value },
                            label = { Text(label) }
                        )
                    }
                }

                if (
                    tool == PageMarkupKind.FREEHAND ||
                    tool == PageMarkupKind.HIGHLIGHT ||
                    tool == PageMarkupKind.RECTANGLE ||
                    tool == PageMarkupKind.ARROW ||
                    tool == PageMarkupKind.SIGNATURE ||
                    tool == PageMarkupKind.INITIALS
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                strokeWidth = (strokeWidth - 0.002f)
                                    .coerceAtLeast(0.002f)
                            }
                        ) {
                            Text("Thinner")
                        }
                        Text(
                            "${(strokeWidth * 1000).toInt()}",
                            modifier = Modifier.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.labelLarge
                        )
                        OutlinedButton(
                            onClick = {
                                strokeWidth = (strokeWidth + 0.002f)
                                    .coerceAtMost(0.04f)
                            }
                        ) {
                            Text("Thicker")
                        }
                    }
                }

                if (
                    tool == PageMarkupKind.SIGNATURE ||
                    tool == PageMarkupKind.INITIALS
                ) {
                    HorizontalDivider()
                    Text(
                        "Saved ${tool.label.lowercase()}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (savedForTool.isEmpty()) {
                        Text(
                            "Draw one on the page, then save it for reuse.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            savedForTool.forEach { saved ->
                                FilterChip(
                                    selected = selectedSavedSignatureId == saved.id,
                                    onClick = {
                                        selectedSavedSignatureId =
                                            if (selectedSavedSignatureId == saved.id) {
                                                null
                                            } else {
                                                saved.id
                                            }
                                    },
                                    label = { Text(saved.label) }
                                )
                            }
                        }
                        selectedSaved?.let { saved ->
                            TextButton(
                                onClick = {
                                    selectedSavedSignatureId = null
                                    onDeleteSignature(saved.id)
                                }
                            ) {
                                Text("Delete selected saved ${tool.label.lowercase()}")
                            }
                        }
                    }

                    if (lastDrawnSignature != null) {
                        OutlinedTextField(
                            value = signatureLabel,
                            onValueChange = { signatureLabel = it.take(60) },
                            label = { Text("Saved name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedButton(
                            onClick = {
                                onSaveSignature(
                                    signatureLabel,
                                    if (tool == PageMarkupKind.SIGNATURE) {
                                        SavedSignatureKind.SIGNATURE
                                    } else {
                                        SavedSignatureKind.INITIALS
                                    },
                                    lastDrawnSignature.points
                                )
                                signatureLabel = ""
                            }
                        ) {
                            Text("Save drawn ${tool.label.lowercase()}")
                        }
                    }
                }

                if (recipe.hasRedactions()) {
                    HorizontalDivider()
                    Text(
                        "Secure redaction",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Black regions are burned into exported PDF pixels. OCR words intersecting those regions are removed before search, text export, and the PDF text layer.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = {
                            verifying = true
                            verifyError = null
                            scope.launch {
                                runCatching { onVerify(recipe) }
                                    .onSuccess { verification = it }
                                    .onFailure {
                                        verifyError =
                                            it.message ?: "Verification failed"
                                    }
                                verifying = false
                            }
                        },
                        enabled = !verifying
                    ) {
                        Text(if (verifying) "Verifying…" else "Verify redaction")
                    }
                    verification?.let { result ->
                        Text(
                            if (result.secure) {
                                "Verified: ${result.redactionCount} region(s), ${result.removedWordCount} OCR word(s) removed, 0 exposed."
                            } else {
                                "Verification failed: ${result.exposedWordCount} OCR word(s) still intersect a redaction."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    verifyError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (recipe.items.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        "Page operations",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    recipe.items.takeLast(8).asReversed().forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                buildString {
                                    append(item.kind.label)
                                    if (item.text.isNotBlank()) {
                                        append(" · ")
                                        append(item.text.take(24))
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = { remove(item.id) }) {
                                Text("Remove")
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            undoStack.lastOrNull()?.let { previous ->
                                recipe = previous
                                undoStack = undoStack.dropLast(1)
                                verification = null
                            }
                        },
                        enabled = undoStack.isNotEmpty()
                    ) {
                        Text("Undo")
                    }
                    OutlinedButton(
                        onClick = {
                            if (!recipe.isEmpty()) {
                                push(PageMarkupRecipe())
                            }
                        },
                        enabled = !recipe.isEmpty()
                    ) {
                        Text("Clear page")
                    }
                }

                Spacer(Modifier.height(2.dp))
                Text(
                    "Crop, rotation, cleanup, and OCR-language changes are blocked while page-space markup exists because those operations would invalidate coordinates. Reset page edits clears this layer.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(recipe.normalized()) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun MarkupPreview(
    page: PageEntity,
    recipe: String?,
    sourceWidth: Int,
    sourceHeight: Int,
    tool: PageMarkupKind,
    colorArgb: Int,
    strokeWidth: Float,
    text: String,
    savedSignature: SavedSignatureEntity?,
    dragPoints: List<NormalizedPoint>,
    onDragPointsChange: (List<NormalizedPoint>) -> Unit,
    onCreateItem: (PageMarkupItem) -> Unit
) {
    val previewColor = MaterialTheme.colorScheme.primary

    fun pointFor(offset: Offset, canvas: IntSize): NormalizedPoint? {
        val sourceW = sourceWidth.toFloat().coerceAtLeast(1f)
        val sourceH = sourceHeight.toFloat().coerceAtLeast(1f)
        val scale = min(canvas.width / sourceW, canvas.height / sourceH)
        if (scale <= 0f) return null
        val renderedWidth = sourceW * scale
        val renderedHeight = sourceH * scale
        val originX = (canvas.width - renderedWidth) / 2f
        val originY = (canvas.height - renderedHeight) / 2f
        val x = (offset.x - originX) / renderedWidth
        val y = (offset.y - originY) / renderedHeight
        if (x !in 0f..1f || y !in 0f..1f) return null
        return NormalizedPoint(x, y)
    }

    fun bounds(points: List<NormalizedPoint>): FloatArray {
        if (points.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)
        return floatArrayOf(
            points.minOf { it.x },
            points.minOf { it.y },
            points.maxOf { it.x },
            points.maxOf { it.y }
        )
    }

    fun addDragItem(points: List<NormalizedPoint>) {
        if (points.isEmpty()) return
        val b = bounds(points)
        val opacity = if (tool == PageMarkupKind.HIGHLIGHT) 0.34f else 1f
        onCreateItem(
            PageMarkupItem(
                id = UUID.randomUUID().toString(),
                kind = tool,
                points = when (tool) {
                    PageMarkupKind.RECTANGLE,
                    PageMarkupKind.ARROW,
                    PageMarkupKind.REDACTION -> emptyList()
                    else -> points
                },
                left = b[0],
                top = b[1],
                right = b[2],
                bottom = b[3],
                colorArgb = when (tool) {
                    PageMarkupKind.REDACTION -> AndroidColor.BLACK
                    PageMarkupKind.HIGHLIGHT ->
                        if (colorArgb == AndroidColor.BLACK) {
                            AndroidColor.rgb(242, 196, 36)
                        } else {
                            colorArgb
                        }
                    else -> colorArgb
                },
                strokeWidth = strokeWidth,
                opacity = opacity
            )
        )
    }

    fun addTapItem(point: NormalizedPoint) {
        val saved = savedSignature
        if (
            (tool == PageMarkupKind.SIGNATURE ||
                tool == PageMarkupKind.INITIALS) &&
            saved != null
        ) {
            val width = if (tool == PageMarkupKind.SIGNATURE) 0.34f else 0.16f
            val height = if (tool == PageMarkupKind.SIGNATURE) 0.10f else 0.08f
            val left = (point.x - width / 2f).coerceIn(0f, 1f - width)
            val top = (point.y - height / 2f).coerceIn(0f, 1f - height)
            onCreateItem(
                PageMarkupItem(
                    id = UUID.randomUUID().toString(),
                    kind = tool,
                    points = SignaturePathCodec.place(
                        SignaturePathCodec.decode(saved.pathData),
                        left,
                        top,
                        left + width,
                        top + height
                    ),
                    left = left,
                    top = top,
                    right = left + width,
                    bottom = top + height,
                    colorArgb = colorArgb,
                    strokeWidth = strokeWidth
                )
            )
            return
        }

        val (width, height) = when (tool) {
            PageMarkupKind.FORM_CHECKBOX,
            PageMarkupKind.FORM_RADIO -> 0.045f to 0.045f
            PageMarkupKind.STAMP -> 0.24f to 0.07f
            PageMarkupKind.FORM_TEXT,
            PageMarkupKind.FORM_DATE -> 0.30f to 0.06f
            else -> 0.28f to 0.07f
        }
        val left = (point.x - width / 2f).coerceIn(0f, 1f - width)
        val top = (point.y - height / 2f).coerceIn(0f, 1f - height)
        onCreateItem(
            PageMarkupItem(
                id = UUID.randomUUID().toString(),
                kind = tool,
                left = left,
                top = top,
                right = left + width,
                bottom = top + height,
                text = when (tool) {
                    PageMarkupKind.STAMP -> text.ifBlank { "APPROVED" }
                    PageMarkupKind.TEXT,
                    PageMarkupKind.FORM_TEXT,
                    PageMarkupKind.FORM_DATE -> text
                    else -> ""
                },
                colorArgb = colorArgb,
                strokeWidth = strokeWidth,
                checked = tool == PageMarkupKind.FORM_CHECKBOX ||
                    tool == PageMarkupKind.FORM_RADIO,
                groupKey = if (tool == PageMarkupKind.FORM_RADIO) "default" else null
            )
        )
    }

    val useTap = when (tool) {
        PageMarkupKind.TEXT,
        PageMarkupKind.STAMP,
        PageMarkupKind.FORM_TEXT,
        PageMarkupKind.FORM_CHECKBOX,
        PageMarkupKind.FORM_RADIO,
        PageMarkupKind.FORM_DATE -> true
        PageMarkupKind.SIGNATURE,
        PageMarkupKind.INITIALS -> savedSignature != null
        else -> false
    }

    val interaction = if (useTap) {
        Modifier.pointerInput(
            tool, sourceWidth, sourceHeight, savedSignature?.id,
            text, colorArgb, strokeWidth
        ) {
            detectTapGestures { tap ->
                pointFor(tap, size)?.let(::addTapItem)
            }
        }
    } else {
        Modifier.pointerInput(tool, sourceWidth, sourceHeight, colorArgb, strokeWidth) {
            var gesturePoints = emptyList<NormalizedPoint>()
            detectDragGestures(
                onDragStart = { offset ->
                    gesturePoints = pointFor(offset, size)
                        ?.let(::listOf)
                        .orEmpty()
                    onDragPointsChange(gesturePoints)
                },
                onDragCancel = {
                    gesturePoints = emptyList()
                    onDragPointsChange(emptyList())
                },
                onDragEnd = {
                    addDragItem(gesturePoints)
                    gesturePoints = emptyList()
                    onDragPointsChange(emptyList())
                },
                onDrag = { change, _ ->
                    val next = pointFor(change.position, size)
                    if (next != null) {
                        gesturePoints = (gesturePoints + next).takeLast(600)
                        onDragPointsChange(gesturePoints)
                    }
                }
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(390.dp)
    ) {
        FileImage(
            path = page.imagePath,
            modifier = Modifier.matchParentSize(),
            rotationDegrees = page.rotationDegrees,
            cropQuad = page.cropQuad,
            visualRecipe = page.visualRecipe,
            cleanupRecipe = page.cleanupRecipe,
            textEditRecipe = page.textEditRecipe,
            markupRecipe = recipe,
            contentDescription = "Page markup preview"
        )

        Canvas(
            modifier = Modifier
                .matchParentSize()
                .then(interaction)
        ) {
            if (dragPoints.isEmpty()) return@Canvas
            val sourceW = sourceWidth.toFloat().coerceAtLeast(1f)
            val sourceH = sourceHeight.toFloat().coerceAtLeast(1f)
            val scale = min(size.width / sourceW, size.height / sourceH)
            val renderedWidth = sourceW * scale
            val renderedHeight = sourceH * scale
            val originX = (size.width - renderedWidth) / 2f
            val originY = (size.height - renderedHeight) / 2f

            fun toOffset(point: NormalizedPoint): Offset =
                Offset(
                    originX + point.x * renderedWidth,
                    originY + point.y * renderedHeight
                )

            val color = previewColor.copy(
                alpha = if (tool == PageMarkupKind.REDACTION) 0.55f else 0.78f
            )
            if (
                tool == PageMarkupKind.RECTANGLE ||
                tool == PageMarkupKind.REDACTION
            ) {
                val b = bounds(dragPoints)
                val topLeft = toOffset(NormalizedPoint(b[0], b[1]))
                val bottomRight = toOffset(NormalizedPoint(b[2], b[3]))
                drawRect(
                    color = color,
                    topLeft = topLeft,
                    size = Size(
                        max(1f, bottomRight.x - topLeft.x),
                        max(1f, bottomRight.y - topLeft.y)
                    ),
                    style = if (tool == PageMarkupKind.REDACTION) {
                        androidx.compose.ui.graphics.drawscope.Fill
                    } else {
                        Stroke(width = 2f * density)
                    }
                )
            } else {
                val path = Path()
                dragPoints.forEachIndexed { index, point ->
                    val p = toOffset(point)
                    if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = max(2f, strokeWidth * min(size.width, size.height)),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )
            }
        }
    }
}
