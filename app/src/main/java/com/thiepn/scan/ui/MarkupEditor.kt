package com.thiepn.scan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.thiepn.scan.data.MarkupPoint
import com.thiepn.scan.data.OcrLayoutCodec
import com.thiepn.scan.data.PageEntity
import com.thiepn.scan.data.PageMarkupElement
import com.thiepn.scan.data.PageMarkupEngine
import com.thiepn.scan.data.PageMarkupKind
import com.thiepn.scan.data.PageMarkupRecipe
import com.thiepn.scan.data.PageMarkupRecipeCodec
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

@Composable
fun MarkupEditorDialog(
    page: PageEntity,
    onDismiss: () -> Unit,
    onSave: (PageMarkupRecipe) -> Unit
) {
    var recipe by remember(page.id, page.markupRecipe) {
        mutableStateOf(PageMarkupRecipeCodec.decode(page.markupRecipe))
    }
    var undo by remember(page.id) { mutableStateOf<List<PageMarkupRecipe>>(emptyList()) }
    var redo by remember(page.id) { mutableStateOf<List<PageMarkupRecipe>>(emptyList()) }
    var tool by remember(page.id) { mutableStateOf(PageMarkupKind.REDACTION) }
    var colorArgb by remember(page.id) { mutableStateOf(0xFFE53935.toInt()) }
    var opacity by remember(page.id) { mutableStateOf(0.9f) }
    var strokeWidth by remember(page.id) { mutableStateOf(0.006f) }
    var textDraft by remember(page.id) { mutableStateOf("Note") }
    var stampDraft by remember(page.id) { mutableStateOf("APPROVED") }

    fun commit(next: PageMarkupRecipe) {
        undo = undo + recipe
        recipe = next.normalized()
        redo = emptyList()
    }

    val preview = remember(recipe) { PageMarkupRecipeCodec.encode(recipe) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Markup & redaction") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 760.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Redactions are flattened into exported PDFs and removed from searchable OCR. Other markup remains non-destructive until export.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        PageMarkupKind.REDACTION, PageMarkupKind.HIGHLIGHT,
                        PageMarkupKind.FREEHAND, PageMarkupKind.RECTANGLE,
                        PageMarkupKind.ELLIPSE, PageMarkupKind.ARROW,
                        PageMarkupKind.SIGNATURE
                    ).forEach { option ->
                        FilterChip(
                            selected = tool == option,
                            onClick = { tool = option },
                            label = { Text(option.label) }
                        )
                    }
                }

                MarkupSurface(
                    page, preview, tool, colorArgb, opacity, strokeWidth
                ) { commit(PageMarkupEngine.withElement(recipe, it)) }

                if (tool != PageMarkupKind.REDACTION) {
                    Text("Color", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "Black" to 0xFF111111.toInt(),
                            "Red" to 0xFFE53935.toInt(),
                            "Blue" to 0xFF1565C0.toInt(),
                            "Green" to 0xFF2E7D32.toInt(),
                            "Yellow" to 0xFFFFC107.toInt()
                        ).forEach { choice ->
                            FilterChip(
                                selected = colorArgb == choice.second,
                                onClick = { colorArgb = choice.second },
                                label = { Text(choice.first) }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = {
                        strokeWidth = (strokeWidth - 0.002f).coerceAtLeast(0.002f)
                    }) { Text("Thinner") }
                    OutlinedButton(onClick = {
                        strokeWidth = (strokeWidth + 0.002f).coerceAtMost(0.03f)
                    }) { Text("Thicker") }
                    OutlinedButton(onClick = {
                        opacity = if (opacity > 0.55f) 0.35f else 0.9f
                    }) { Text(if (opacity > 0.55f) "Light" else "Strong") }
                }

                Text("Text annotation", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = textDraft,
                    onValueChange = { textDraft = it.take(1200) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 4
                )
                Button(onClick = {
                    if (textDraft.isNotBlank()) {
                        commit(PageMarkupEngine.withElement(
                            recipe,
                            PageMarkupElement(
                                id=UUID.randomUUID().toString(),
                                kind=PageMarkupKind.TEXT,
                                left=.18f,top=.18f,right=.82f,bottom=.30f,
                                text=textDraft,colorArgb=colorArgb,opacity=opacity
                            )
                        ))
                    }
                }) { Text("Add text") }

                Text("Stamp", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("APPROVED","RECEIVED","DRAFT","CONFIDENTIAL","SIGNED").forEach { preset ->
                        FilterChip(
                            selected=stampDraft==preset,
                            onClick={stampDraft=preset},
                            label={Text(preset)}
                        )
                    }
                }
                OutlinedTextField(
                    value=stampDraft,
                    onValueChange={stampDraft=it.take(80)},
                    modifier=Modifier.fillMaxWidth(),
                    singleLine=true
                )
                OutlinedButton(onClick = {
                    if (stampDraft.isNotBlank()) {
                        commit(PageMarkupEngine.withElement(
                            recipe,
                            PageMarkupElement(
                                id=UUID.randomUUID().toString(),
                                kind=PageMarkupKind.STAMP,
                                left=.28f,top=.35f,right=.72f,bottom=.46f,
                                text=stampDraft,colorArgb=colorArgb,
                                strokeWidth=strokeWidth,opacity=opacity
                            )
                        ))
                    }
                }) { Text("Add stamp") }

                if (recipe.elements.isNotEmpty()) {
                    Text("Layers · " + recipe.elements.size, style = MaterialTheme.typography.labelLarge)
                    recipe.elements.sortedByDescending { it.zIndex }.forEach { element ->
                        Row(
                            modifier=Modifier.fillMaxWidth(),
                            horizontalArrangement=Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                element.kind.label +
                                    element.text.takeIf { it.isNotBlank() }?.let { ": " + it.take(22) }.orEmpty(),
                                modifier=Modifier.fillMaxWidth(0.46f),
                                style=MaterialTheme.typography.bodySmall
                            )
                            TextButton(
                                onClick={commit(PageMarkupEngine.move(recipe,element.id,1))},
                                enabled=element.zIndex<recipe.elements.lastIndex
                            ){Text("Up")}
                            TextButton(
                                onClick={commit(PageMarkupEngine.move(recipe,element.id,-1))},
                                enabled=element.zIndex>0
                            ){Text("Down")}
                            TextButton(
                                onClick={commit(PageMarkupEngine.remove(recipe,element.id))}
                            ){Text("Delete")}
                        }
                    }
                }

                Row(
                    modifier=Modifier.fillMaxWidth(),
                    horizontalArrangement=Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick={
                        undo.lastOrNull()?.let {
                            redo=redo+recipe;recipe=it;undo=undo.dropLast(1)
                        }
                    },enabled=undo.isNotEmpty()){Text("Undo")}
                    OutlinedButton(onClick={
                        redo.lastOrNull()?.let {
                            undo=undo+recipe;recipe=it;redo=redo.dropLast(1)
                        }
                    },enabled=redo.isNotEmpty()){Text("Redo")}
                    OutlinedButton(onClick={
                        if(!recipe.isEmpty())commit(PageMarkupRecipe())
                    },enabled=!recipe.isEmpty()){Text("Revert all")}
                }
            }
        },
        confirmButton={TextButton(onClick={onSave(recipe.normalized())}){Text("Save")}},
        dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}}
    )
}

@Composable
private fun MarkupSurface(
    page:PageEntity,
    recipe:String?,
    tool:PageMarkupKind,
    colorArgb:Int,
    opacity:Float,
    strokeWidth:Float,
    onElement:(PageMarkupElement)->Unit
) {
    val ocr=remember(page.ocrBaseLayout,page.ocrLayout){
        OcrLayoutCodec.decode(page.ocrBaseLayout?:page.ocrLayout)
    }
    val rotated=page.rotationDegrees==90||page.rotationDegrees==270
    val sw=(ocr?.sourceWidth?:if(rotated)page.height else page.width).coerceAtLeast(1)
    val sh=(ocr?.sourceHeight?:if(rotated)page.width else page.height).coerceAtLeast(1)

    Box(Modifier.fillMaxWidth().height(390.dp)) {
        FileImage(
            path=page.imagePath,
            modifier=Modifier.fillMaxSize(),
            rotationDegrees=page.rotationDegrees,
            cropQuad=page.cropQuad,
            visualRecipe=page.visualRecipe,
            cleanupRecipe=page.cleanupRecipe,
            textEditRecipe=page.textEditRecipe,
            markupRecipe=recipe,
            formFillRecipe=page.formFillRecipe,
            contentDescription="Markup preview"
        )
        Canvas(
            Modifier.fillMaxSize().pointerInput(tool,colorArgb,opacity,strokeWidth,sw,sh) {
                var start=MarkupPoint(0f,0f)
                val points=mutableListOf<MarkupPoint>()
                fun norm(pos:Offset):MarkupPoint? {
                    val scale=min(size.width/sw.toFloat(),size.height/sh.toFloat())
                    val rw=sw*scale;val rh=sh*scale
                    val ox=(size.width-rw)/2f;val oy=(size.height-rh)/2f
                    val x=(pos.x-ox)/rw;val y=(pos.y-oy)/rh
                    return if(x in 0f..1f&&y in 0f..1f)MarkupPoint(x,y) else null
                }
                detectDragGestures(
                    onDragStart={pos->points.clear();norm(pos)?.let{start=it;points+=it}},
                    onDrag={change,_->
                        norm(change.position)?.let{p->
                            if(tool==PageMarkupKind.FREEHAND||tool==PageMarkupKind.SIGNATURE)points+=p
                            else if(points.size==1)points+=p else points[points.lastIndex]=p
                        }
                    },
                    onDragEnd={
                        val end=points.lastOrNull()?:start
                        val l=min(start.x,end.x);val t=min(start.y,end.y)
                        val r=max(start.x,end.x);val b=max(start.y,end.y)
                        if((r-l)>.006f||(b-t)>.006f||points.size>=2){
                            val c=when(tool){
                                PageMarkupKind.REDACTION->0xFF000000.toInt()
                                PageMarkupKind.HIGHLIGHT->0xFFFFC107.toInt()
                                PageMarkupKind.SIGNATURE->0xFF111111.toInt()
                                else->colorArgb
                            }
                            onElement(PageMarkupElement(
                                id=UUID.randomUUID().toString(),kind=tool,
                                left=l,top=t,right=r,bottom=b,
                                points=if(tool==PageMarkupKind.FREEHAND||tool==PageMarkupKind.SIGNATURE||tool==PageMarkupKind.ARROW)points.toList() else emptyList(),
                                colorArgb=c,
                                strokeWidth=if(tool==PageMarkupKind.SIGNATURE)max(.004f,strokeWidth) else strokeWidth,
                                opacity=when(tool){
                                    PageMarkupKind.REDACTION->1f
                                    PageMarkupKind.HIGHLIGHT->min(.42f,opacity)
                                    else->opacity
                                },
                                filled=tool==PageMarkupKind.REDACTION||tool==PageMarkupKind.HIGHLIGHT
                            ))
                        }
                        points.clear()
                    },
                    onDragCancel={points.clear()}
                )
            }
        ){}
    }
}
