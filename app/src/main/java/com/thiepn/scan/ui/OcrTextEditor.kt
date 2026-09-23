package com.thiepn.scan.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thiepn.scan.data.OcrEditableRegion
import com.thiepn.scan.data.OcrLayoutCodec
import com.thiepn.scan.data.OcrTextAlignment
import com.thiepn.scan.data.OcrTextEditEngine
import com.thiepn.scan.data.OcrTextEditTarget
import com.thiepn.scan.data.PageEntity
import com.thiepn.scan.data.PageTextEditRecipe
import com.thiepn.scan.data.PageTextEditRecipeCodec
import kotlin.math.min

@Composable
fun OcrTextEditorDialog(
    page: PageEntity,
    onDismiss: () -> Unit,
    onSave: (PageTextEditRecipe) -> Unit
) {
    val baseLayout = remember(page.id, page.ocrBaseLayout, page.ocrLayout) {
        OcrLayoutCodec.decode(page.ocrBaseLayout ?: page.ocrLayout)
    }
    var target by remember(page.id) {
        mutableStateOf(OcrTextEditTarget.LINE)
    }
    var recipe by remember(page.id, page.textEditRecipe) {
        mutableStateOf(PageTextEditRecipeCodec.decode(page.textEditRecipe))
    }
    var undoStack by remember(page.id) {
        mutableStateOf<List<PageTextEditRecipe>>(emptyList())
    }
    var selectedId by remember(page.id) {
        mutableStateOf<String?>(null)
    }

    val regions = remember(baseLayout, target) {
        OcrTextEditEngine.regions(baseLayout, target)
    }
    val selected = remember(regions, selectedId) {
        regions.firstOrNull { it.id == selectedId }
    }
    val existing = remember(recipe, selected?.id) {
        selected?.let { region ->
            recipe.edits.firstOrNull { it.id == region.id }
        }
    }

    var draft by remember(selected?.id, existing?.replacementText) {
        mutableStateOf(
            existing?.replacementText ?: selected?.text.orEmpty()
        )
    }
    var alignment by remember(selected?.id, existing?.alignment) {
        mutableStateOf(existing?.alignment ?: OcrTextAlignment.AUTO)
    }
    var fontScale by remember(selected?.id, existing?.fontScale) {
        mutableStateOf(existing?.fontScale ?: 1f)
    }

    val previewRecipe = remember(recipe) {
        PageTextEditRecipeCodec.encode(recipe)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit recognized text") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 720.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Select a recognized word, line, or text block. The scan is rebuilt locally; the original image remains unchanged.",
                    style = MaterialTheme.typography.bodySmall
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OcrTextEditTarget.entries.forEach { option ->
                        FilterChip(
                            selected = target == option,
                            onClick = {
                                target = option
                                selectedId = null
                            },
                            label = { Text(option.label) }
                        )
                    }
                }

                if (baseLayout == null || regions.isEmpty()) {
                    Text(
                        if (baseLayout == null) {
                            "Spatial OCR data is unavailable for this page."
                        } else {
                            "No recognized ${target.label.lowercase()} regions are available."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        "Tap text in the preview · ${regions.size} ${target.label.lowercase()} region${if (regions.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    OcrRegionPreview(
                        page = page,
                        regions = regions,
                        selectedId = selectedId,
                        textEditRecipe = previewRecipe,
                        sourceWidth = baseLayout.sourceWidth,
                        sourceHeight = baseLayout.sourceHeight,
                        onSelect = { selectedId = it.id }
                    )
                }

                if (selected != null) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Original",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            selected.text.ifBlank { "[blank]" },
                            style = MaterialTheme.typography.bodySmall
                        )

                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            label = { Text("Replacement text") },
                            supportingText = {
                                Text(
                                    if (target == OcrTextEditTarget.WORD) {
                                        "Word mode is single-line; line breaks are rendered as spaces."
                                    } else {
                                        "Line breaks are preserved."
                                    }
                                )
                            },
                            minLines = if (target == OcrTextEditTarget.WORD) 1 else 2,
                            maxLines = if (target == OcrTextEditTarget.WORD) 2 else 7,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            "Alignment",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OcrTextAlignment.entries.forEach { option ->
                                FilterChip(
                                    selected = alignment == option,
                                    onClick = { alignment = option },
                                    label = { Text(option.label) }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    fontScale = (fontScale - 0.1f)
                                        .coerceAtLeast(0.55f)
                                }
                            ) {
                                Text("Text −")
                            }
                            Text(
                                "${(fontScale * 100).toInt()}%",
                                modifier = Modifier.padding(vertical = 12.dp),
                                style = MaterialTheme.typography.labelLarge
                            )
                            OutlinedButton(
                                onClick = {
                                    fontScale = (fontScale + 0.1f)
                                        .coerceAtMost(1.7f)
                                }
                            ) {
                                Text("Text +")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    undoStack = undoStack + recipe
                                    recipe = OcrTextEditEngine.withEdit(
                                        recipe,
                                        OcrTextEditEngine.createEdit(
                                            region = selected,
                                            replacementText = if (
                                                target == OcrTextEditTarget.WORD
                                            ) {
                                                draft.replace('\n', ' ')
                                            } else {
                                                draft
                                            },
                                            alignment = alignment,
                                            fontScale = fontScale
                                        )
                                    )
                                }
                            ) {
                                Text("Apply")
                            }

                            OutlinedButton(
                                onClick = {
                                    undoStack = undoStack + recipe
                                    recipe = OcrTextEditEngine.withEdit(
                                        recipe,
                                        OcrTextEditEngine.createEdit(
                                            region = selected,
                                            replacementText = selected.text
                                        )
                                    )
                                    draft = selected.text
                                    alignment = OcrTextAlignment.AUTO
                                    fontScale = 1f
                                }
                            ) {
                                Text("Revert region")
                            }
                        }
                    }
                }

                if (!recipe.isEmpty()) {
                    Text(
                        "${recipe.edits.size} replacement${if (recipe.edits.size == 1) "" else "s"} pending",
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val previous = undoStack.lastOrNull()
                            if (previous != null) {
                                recipe = previous
                                undoStack = undoStack.dropLast(1)
                            }
                        },
                        enabled = undoStack.isNotEmpty()
                    ) {
                        Text("Undo")
                    }
                    OutlinedButton(
                        onClick = {
                            if (!recipe.isEmpty()) {
                                undoStack = undoStack + recipe
                                recipe = PageTextEditRecipe()
                                selectedId = null
                            }
                        },
                        enabled = !recipe.isEmpty()
                    ) {
                        Text("Revert all")
                    }
                }

                Spacer(Modifier.height(2.dp))
                Text(
                    "Saving updates both the visible page replacement recipe and searchable OCR text. Reset page edits restores the original recognized text.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(recipe.normalized()) },
                enabled = baseLayout != null
            ) {
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
private fun OcrRegionPreview(
    page: PageEntity,
    regions: List<OcrEditableRegion>,
    selectedId: String?,
    textEditRecipe: String?,
    sourceWidth: Int,
    sourceHeight: Int,
    onSelect: (OcrEditableRegion) -> Unit
) {
    val regionColor = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.tertiary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
    ) {
        FileImage(
            path = page.imagePath,
            modifier = Modifier.matchParentSize(),
            rotationDegrees = page.rotationDegrees,
            cropQuad = page.cropQuad,
            visualRecipe = page.visualRecipe,
            cleanupRecipe = page.cleanupRecipe,
            textEditRecipe = textEditRecipe,
            contentDescription = "OCR text editing preview"
        )

        Canvas(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(
                    regions,
                    sourceWidth,
                    sourceHeight
                ) {
                    detectTapGestures { tap ->
                        if (sourceWidth <= 0 || sourceHeight <= 0) {
                            return@detectTapGestures
                        }
                        val sourceW = sourceWidth.toFloat()
                        val sourceH = sourceHeight.toFloat()
                        val scale = min(
                            size.width / sourceW,
                            size.height / sourceH
                        )
                        val renderedWidth = sourceW * scale
                        val renderedHeight = sourceH * scale
                        val originX = (size.width - renderedWidth) / 2f
                        val originY = (size.height - renderedHeight) / 2f
                        val x = (tap.x - originX) / renderedWidth
                        val y = (tap.y - originY) / renderedHeight
                        if (x !in 0f..1f || y !in 0f..1f) {
                            return@detectTapGestures
                        }
                        regions
                            .filter {
                                x >= it.left &&
                                    x <= it.right &&
                                    y >= it.top &&
                                    y <= it.bottom
                            }
                            .minByOrNull { it.area }
                            ?.let(onSelect)
                    }
                }
        ) {
            if (sourceWidth <= 0 || sourceHeight <= 0) return@Canvas
            val sourceW = sourceWidth.toFloat()
            val sourceH = sourceHeight.toFloat()
            val scale = min(
                size.width / sourceW,
                size.height / sourceH
            )
            val renderedWidth = sourceW * scale
            val renderedHeight = sourceH * scale
            val originX = (size.width - renderedWidth) / 2f
            val originY = (size.height - renderedHeight) / 2f

            regions.forEach { region ->
                val selected = region.id == selectedId
                val left = originX + region.left * renderedWidth
                val top = originY + region.top * renderedHeight
                val width = (region.right - region.left) * renderedWidth
                val height = (region.bottom - region.top) * renderedHeight
                val color = if (selected) selectedColor else regionColor
                drawRect(
                    color = color.copy(alpha = if (selected) 0.17f else 0.05f),
                    topLeft = Offset(left, top),
                    size = Size(width, height)
                )
                drawRect(
                    color = color.copy(alpha = if (selected) 0.95f else 0.42f),
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    style = Stroke(
                        width = if (selected) 3f * density else 1f * density
                    )
                )
            }
        }
    }
}
