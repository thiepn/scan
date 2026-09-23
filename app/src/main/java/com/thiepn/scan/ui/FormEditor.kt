package com.thiepn.scan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import com.thiepn.scan.data.FormField
import com.thiepn.scan.data.FormFieldDetector
import com.thiepn.scan.data.FormFieldType
import com.thiepn.scan.data.FormPoint
import com.thiepn.scan.data.FormTemplateEntity
import com.thiepn.scan.data.FormValidator
import com.thiepn.scan.data.OcrLayoutCodec
import com.thiepn.scan.data.PageEntity
import com.thiepn.scan.data.PageFormRecipe
import com.thiepn.scan.data.PageFormRecipeCodec
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

@Composable
fun FormDocumentTools(
    fieldCount: Int,
    filledCount: Int,
    issueCount: Int,
    templateCount: Int,
    enabled: Boolean,
    onFill: () -> Unit,
    onDetectAll: () -> Unit,
    onSaveTemplate: () -> Unit,
    onTemplates: () -> Unit
) {
    androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Form filling", style = MaterialTheme.typography.titleSmall)
            Text(
                filledCount.toString() + " / " + fieldCount + " fields filled" +
                    if (issueCount > 0) {
                        " · " + issueCount + " validation issue" + if (issueCount == 1) "" else "s"
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onFill, enabled = enabled) { Text("Fill") }
                OutlinedButton(onClick = onDetectAll, enabled = enabled) { Text("Detect fields") }
                OutlinedButton(
                    onClick = onSaveTemplate,
                    enabled = enabled && fieldCount > 0
                ) { Text("Save profile") }
                OutlinedButton(
                    onClick = onTemplates,
                    enabled = enabled && templateCount > 0
                ) { Text("Profiles (" + templateCount + ")") }
            }
        }
    }
}

@Composable
fun FormEditorDialog(
    page: PageEntity,
    onDismiss: () -> Unit,
    onSave: (PageFormRecipe) -> Unit
) {
    val baseLayout = remember(page.id, page.ocrBaseLayout, page.ocrLayout) {
        OcrLayoutCodec.decode(page.ocrLayout ?: page.ocrBaseLayout)
    }
    var recipe by remember(page.id, page.formFillRecipe) {
        mutableStateOf(PageFormRecipeCodec.decode(page.formFillRecipe))
    }
    var undo by remember(page.id) { mutableStateOf<List<PageFormRecipe>>(emptyList()) }
    var redo by remember(page.id) { mutableStateOf<List<PageFormRecipe>>(emptyList()) }
    var selectedId by remember(page.id) { mutableStateOf(recipe.fields.firstOrNull()?.id) }
    var addType by remember(page.id) { mutableStateOf<FormFieldType?>(null) }

    fun commit(next: PageFormRecipe) {
        undo = undo + recipe
        recipe = next.normalized()
        redo = emptyList()
    }

    val selected = recipe.fields.firstOrNull { it.id == selectedId }
    var labelDraft by remember(selected?.id, selected?.label) {
        mutableStateOf(selected?.label.orEmpty())
    }
    var typeDraft by remember(selected?.id, selected?.type) {
        mutableStateOf(selected?.type ?: FormFieldType.TEXT)
    }
    var valueDraft by remember(selected?.id, selected?.value) {
        mutableStateOf(selected?.value.orEmpty())
    }
    var checkedDraft by remember(selected?.id, selected?.checked) {
        mutableStateOf(selected?.checked ?: false)
    }
    var requiredDraft by remember(selected?.id, selected?.required) {
        mutableStateOf(selected?.required ?: false)
    }
    var signatureDraft by remember(selected?.id, selected?.signature) {
        mutableStateOf(selected?.signature.orEmpty())
    }

    fun appliedRecipe(): PageFormRecipe {
        val field = selected ?: return recipe
        return PageFormRecipe(
            fields = recipe.fields.map {
                if (it.id == field.id) {
                    it.copy(
                        label = labelDraft,
                        type = typeDraft,
                        value = if (typeDraft == FormFieldType.TEXT || typeDraft == FormFieldType.DATE) valueDraft else "",
                        checked = if (typeDraft == FormFieldType.CHECKBOX) checkedDraft else false,
                        required = requiredDraft,
                        signature = if (typeDraft == FormFieldType.SIGNATURE) signatureDraft else emptyList()
                    )
                } else {
                    it
                }
            }
        ).normalized()
    }

    val issues = FormValidator.validate(appliedRecipe())
    val currentIndex = recipe.fields.indexOfFirst { it.id == selectedId }
    val preview = PageFormRecipeCodec.encode(appliedRecipe())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fill form") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 780.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Auto-detect labels or drag a new field onto the page. Filled values are flattened into exported PDFs.",
                    style = MaterialTheme.typography.bodySmall
                )

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val detected = FormFieldDetector.detect(baseLayout)
                            val merged = FormFieldDetector.merge(recipe, detected)
                            if (merged != recipe) commit(merged)
                            if (selectedId == null) selectedId = merged.fields.firstOrNull()?.id
                        },
                        enabled = baseLayout != null
                    ) { Text("Auto-detect") }

                    FormFieldType.entries.forEach { type ->
                        FilterChip(
                            selected = addType == type,
                            onClick = { addType = if (addType == type) null else type },
                            label = { Text("Add " + type.label) }
                        )
                    }
                }

                FormFieldSurface(
                    page = page,
                    recipe = preview,
                    fields = appliedRecipe().fields,
                    selectedId = selectedId,
                    addType = addType,
                    onSelect = { selectedId = it },
                    onAdd = { field ->
                        commit(PageFormRecipe(fields = recipe.fields + field))
                        selectedId = field.id
                        addType = null
                    }
                )

                if (recipe.fields.isEmpty()) {
                    Text(
                        "No fields yet. Use Auto-detect or choose a field type and drag its region on the page.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (selected != null) {
                    Text(
                        "Field " + (currentIndex + 1) + " of " + recipe.fields.size,
                        style = MaterialTheme.typography.labelLarge
                    )
                    OutlinedTextField(
                        value = labelDraft,
                        onValueChange = { labelDraft = it.take(180) },
                        label = { Text("Label") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FormFieldType.entries.forEach { type ->
                            FilterChip(
                                selected = typeDraft == type,
                                onClick = { typeDraft = type },
                                label = { Text(type.label) }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(
                            checked = requiredDraft,
                            onCheckedChange = { requiredDraft = it }
                        )
                        Text("Required", modifier = Modifier.padding(top = 12.dp))
                    }

                    when (typeDraft) {
                        FormFieldType.TEXT, FormFieldType.DATE -> {
                            OutlinedTextField(
                                value = valueDraft,
                                onValueChange = { valueDraft = it.take(2000) },
                                label = { Text(if (typeDraft == FormFieldType.DATE) "Date" else "Value") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 1,
                                maxLines = 5
                            )
                        }
                        FormFieldType.CHECKBOX -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Checkbox(checked = checkedDraft, onCheckedChange = { checkedDraft = it })
                                Text(
                                    if (checkedDraft) "Checked" else "Unchecked",
                                    modifier = Modifier.padding(top = 12.dp)
                                )
                            }
                        }
                        FormFieldType.SIGNATURE -> {
                            SignaturePad(points = signatureDraft, onChange = { signatureDraft = it })
                            OutlinedButton(
                                onClick = { signatureDraft = emptyList() },
                                enabled = signatureDraft.isNotEmpty()
                            ) { Text("Clear signature") }
                        }
                    }

                    issues.firstOrNull { it.fieldId == selected.id }?.let {
                        Text(
                            it.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = { commit(appliedRecipe()) }) { Text("Apply") }
                        OutlinedButton(
                            onClick = {
                                val updated = appliedRecipe()
                                if (updated != recipe) commit(updated)
                                selectedId = updated.fields.getOrNull(currentIndex - 1)?.id
                            },
                            enabled = currentIndex > 0
                        ) { Text("Previous") }
                        OutlinedButton(
                            onClick = {
                                val updated = appliedRecipe()
                                if (updated != recipe) commit(updated)
                                selectedId = updated.fields.getOrNull(currentIndex + 1)?.id
                            },
                            enabled = currentIndex >= 0 && currentIndex < recipe.fields.lastIndex
                        ) { Text("Next") }
                        OutlinedButton(
                            onClick = {
                                val remaining = PageFormRecipe(
                                    fields = recipe.fields.filterNot { it.id == selected.id }
                                ).normalized()
                                commit(remaining)
                                selectedId = remaining.fields.getOrNull(
                                    (currentIndex - 1).coerceAtLeast(0)
                                )?.id
                            }
                        ) { Text("Delete") }
                    }
                }

                if (issues.isNotEmpty()) {
                    Text(
                        issues.size.toString() + " validation issue" +
                            if (issues.size == 1) "" else "s" +
                            " · partial saves are allowed",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            undo.lastOrNull()?.let { previous ->
                                redo = redo + recipe
                                recipe = previous
                                undo = undo.dropLast(1)
                                selectedId = previous.fields.firstOrNull()?.id
                            }
                        },
                        enabled = undo.isNotEmpty()
                    ) { Text("Undo") }
                    OutlinedButton(
                        onClick = {
                            redo.lastOrNull()?.let { next ->
                                undo = undo + recipe
                                recipe = next
                                redo = redo.dropLast(1)
                                selectedId = next.fields.firstOrNull()?.id
                            }
                        },
                        enabled = redo.isNotEmpty()
                    ) { Text("Redo") }
                    OutlinedButton(
                        onClick = {
                            if (!recipe.isEmpty()) {
                                commit(PageFormRecipe())
                                selectedId = null
                            }
                        },
                        enabled = !recipe.isEmpty()
                    ) { Text("Clear fields") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(appliedRecipe()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun FormFieldSurface(
    page: PageEntity,
    recipe: String?,
    fields: List<FormField>,
    selectedId: String?,
    addType: FormFieldType?,
    onSelect: (String) -> Unit,
    onAdd: (FormField) -> Unit
) {
    val layout = remember(page.ocrBaseLayout, page.ocrLayout) {
        OcrLayoutCodec.decode(page.ocrLayout ?: page.ocrBaseLayout)
    }
    val rotated = page.rotationDegrees == 90 || page.rotationDegrees == 270
    val sourceWidth = (layout?.sourceWidth ?: if (rotated) page.height else page.width).coerceAtLeast(1)
    val sourceHeight = (layout?.sourceHeight ?: if (rotated) page.width else page.height).coerceAtLeast(1)
    val normalColor = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.tertiary

    fun normalized(position: Offset, width: Float, height: Float): FormPoint? {
        val scale = min(width / sourceWidth, height / sourceHeight)
        val rw = sourceWidth * scale
        val rh = sourceHeight * scale
        val ox = (width - rw) / 2f
        val oy = (height - rh) / 2f
        val x = (position.x - ox) / rw
        val y = (position.y - oy) / rh
        return if (x in 0f..1f && y in 0f..1f) FormPoint(x, y) else null
    }

    val tap = if (addType == null) {
        Modifier.pointerInput(fields, sourceWidth, sourceHeight) {
            detectTapGestures { position ->
                normalized(position, size.width.toFloat(), size.height.toFloat())?.let { p ->
                    fields.filter {
                        p.x >= it.left && p.x <= it.right && p.y >= it.top && p.y <= it.bottom
                    }.minByOrNull { (it.right - it.left) * (it.bottom - it.top) }
                        ?.let { onSelect(it.id) }
                }
            }
        }
    } else Modifier

    val drag = if (addType != null) {
        Modifier.pointerInput(addType, sourceWidth, sourceHeight) {
            var start: FormPoint? = null
            var end: FormPoint? = null
            detectDragGestures(
                onDragStart = { pos ->
                    start = normalized(pos, size.width.toFloat(), size.height.toFloat())
                    end = start
                },
                onDrag = { change, _ ->
                    end = normalized(change.position, size.width.toFloat(), size.height.toFloat()) ?: end
                },
                onDragEnd = {
                    val a = start
                    val b = end
                    if (a != null && b != null) {
                        val l = min(a.x, b.x)
                        val t = min(a.y, b.y)
                        val r = max(a.x, b.x)
                        val bot = max(a.y, b.y)
                        if (r - l >= 0.012f && bot - t >= 0.012f) {
                            onAdd(
                                FormField(
                                    id = UUID.randomUUID().toString(),
                                    type = addType,
                                    label = addType.label,
                                    left = l,
                                    top = t,
                                    right = r,
                                    bottom = bot,
                                    source = "MANUAL",
                                    fieldOrder = fields.size
                                )
                            )
                        }
                    }
                    start = null
                    end = null
                },
                onDragCancel = {
                    start = null
                    end = null
                }
            )
        }
    } else Modifier

    Box(Modifier.fillMaxWidth().height(390.dp)) {
        FileImage(
            path = page.imagePath,
            modifier = Modifier.fillMaxSize(),
            rotationDegrees = page.rotationDegrees,
            cropQuad = page.cropQuad,
            visualRecipe = page.visualRecipe,
            cleanupRecipe = page.cleanupRecipe,
            textEditRecipe = page.textEditRecipe,
            markupRecipe = page.markupRecipe,
            formFillRecipe = recipe,
            contentDescription = "Form filling preview"
        )
        Canvas(Modifier.fillMaxSize().then(tap).then(drag)) {
            val scale = min(size.width / sourceWidth, size.height / sourceHeight)
            val rw = sourceWidth * scale
            val rh = sourceHeight * scale
            val ox = (size.width - rw) / 2f
            val oy = (size.height - rh) / 2f
            fields.forEach { field ->
                val isSelected = field.id == selectedId
                val color = if (isSelected) selectedColor else normalColor
                val left = ox + field.left * rw
                val top = oy + field.top * rh
                val width = (field.right - field.left) * rw
                val height = (field.bottom - field.top) * rh
                drawRect(
                    color = color.copy(alpha = if (isSelected) 0.15f else 0.04f),
                    topLeft = Offset(left, top),
                    size = Size(width, height)
                )
                drawRect(
                    color = color.copy(alpha = if (isSelected) 0.95f else 0.48f),
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    style = Stroke(width = if (isSelected) 3f * density else 1f * density)
                )
            }
        }
    }
}

@Composable
private fun SignaturePad(
    points: List<FormPoint>,
    onChange: (List<FormPoint>) -> Unit
) {
    val lineColor = MaterialTheme.colorScheme.onSurface
    var activeStroke by remember { mutableStateOf<List<FormPoint>>(emptyList()) }
    val renderedPoints = points + activeStroke

    Canvas(
        Modifier.fillMaxWidth().height(150.dp)
            .pointerInput(points.size) {
                detectDragGestures(
                    onDragStart = { pos ->
                        activeStroke = listOf(
                            FormPoint(
                                pos.x / size.width.toFloat(),
                                pos.y / size.height.toFloat(),
                                strokeStart = true
                            ).clamped()
                        )
                    },
                    onDrag = { change, _ ->
                        activeStroke = activeStroke + FormPoint(
                            change.position.x / size.width.toFloat(),
                            change.position.y / size.height.toFloat()
                        ).clamped()
                    },
                    onDragEnd = {
                        if (activeStroke.size >= 2) {
                            onChange(points + activeStroke)
                        }
                        activeStroke = emptyList()
                    },
                    onDragCancel = {
                        activeStroke = emptyList()
                    }
                )
            }
    ) {
        drawRect(
            color = lineColor.copy(alpha = 0.08f),
            style = Stroke(width = 1f * density)
        )
        var previous: FormPoint? = null
        renderedPoints.forEach { point ->
            if (point.strokeStart) {
                previous = point
            } else {
                previous?.let {
                    drawLine(
                        lineColor,
                        Offset(it.x * size.width, it.y * size.height),
                        Offset(point.x * size.width, point.y * size.height),
                        strokeWidth = 2f * density
                    )
                }
                previous = point
            }
        }
    }
}

@Composable
fun FormTemplateNameDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save form profile") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text("Profile name") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun FormTemplatePickerDialog(
    templates: List<FormTemplateEntity>,
    currentPageCount: Int,
    onDismiss: () -> Unit,
    onApply: (FormTemplateEntity) -> Unit,
    onDelete: (FormTemplateEntity) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Form profiles") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                templates.forEach { template ->
                    androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(template.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                template.pageCount.toString() + " page" +
                                    if (template.pageCount == 1) "" else "s",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onApply(template) },
                                    enabled = template.pageCount == currentPageCount
                                ) { Text("Apply") }
                                TextButton(onClick = { onDelete(template) }) { Text("Delete") }
                            }
                            if (template.pageCount != currentPageCount) {
                                Text(
                                    "Page count does not match this document.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
