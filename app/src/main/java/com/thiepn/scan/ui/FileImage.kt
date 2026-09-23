package com.thiepn.scan.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import com.thiepn.scan.data.CropQuadCodec
import com.thiepn.scan.data.ImageEnhancementRenderer
import com.thiepn.scan.data.OcrWordBox
import com.thiepn.scan.data.OcrTextEditRenderer
import com.thiepn.scan.data.PageMarkupRecipeCodec
import com.thiepn.scan.data.PageMarkupRenderer
import com.thiepn.scan.data.PageCleanupRecipeCodec
import com.thiepn.scan.data.PageTextEditRecipeCodec
import com.thiepn.scan.data.PageCleanupRenderer
import com.thiepn.scan.data.PageGeometryRenderer
import com.thiepn.scan.data.PageVisualRecipeCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

@Composable
fun FileImage(
    path: String,
    modifier: Modifier = Modifier,
    maxDecodeEdge: Int = 1600,
    rotationDegrees: Int = 0,
    cropQuad: String? = null,
    visualRecipe: String? = null,
    cleanupRecipe: String? = null,
    textEditRecipe: String? = null,
    markupRecipe: String? = null,
    highlightWords: List<OcrWordBox> = emptyList(),
    highlightSourceWidth: Int = 0,
    highlightSourceHeight: Int = 0,
    contentDescription: String? = null
) {
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        path,
        maxDecodeEdge,
        rotationDegrees,
        cropQuad,
        visualRecipe,
        cleanupRecipe,
        textEditRecipe,
        markupRecipe
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val geometry = PageGeometryRenderer.renderUnrotatedForPdf(
                    file = File(path),
                    cropQuad = CropQuadCodec.decode(cropQuad),
                    maxLongEdge = maxDecodeEdge
                )
                val cleaned = PageCleanupRenderer.apply(
                    geometry,
                    PageCleanupRecipeCodec.decode(cleanupRecipe)
                )
                if (cleaned !== geometry) geometry.recycle()
                val textEdits = PageTextEditRecipeCodec.decode(textEditRecipe)
                val rendered = if (textEdits.isEmpty()) {
                    val enhanced = ImageEnhancementRenderer.apply(
                        cleaned,
                        PageVisualRecipeCodec.decode(visualRecipe)
                    )
                    if (enhanced !== cleaned) cleaned.recycle()
                    val rotated = PageGeometryRenderer.rotateBitmap(
                        enhanced,
                        rotationDegrees
                    )
                    if (rotated !== enhanced) enhanced.recycle()
                    rotated
                } else {
                    val rotated = PageGeometryRenderer.rotateBitmap(
                        cleaned,
                        rotationDegrees
                    )
                    if (rotated !== cleaned) cleaned.recycle()
                    val edited = OcrTextEditRenderer.apply(
                        rotated,
                        textEdits
                    )
                    if (edited !== rotated) rotated.recycle()
                    val enhanced = ImageEnhancementRenderer.apply(
                        edited,
                        PageVisualRecipeCodec.decode(visualRecipe)
                    )
                    if (enhanced !== edited) edited.recycle()
                    enhanced
                }
                val marked = PageMarkupRenderer.apply(
                    rendered,
                    PageMarkupRecipeCodec.decode(markupRecipe)
                )
                if (marked !== rendered) rendered.recycle()
                marked
            }.getOrNull()
        }
    }

    DisposableEffect(bitmap) {
        onDispose { bitmap?.recycle() }
    }

    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val highlightColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Fit
            )

            if (
                highlightWords.isNotEmpty() &&
                highlightSourceWidth > 0 &&
                highlightSourceHeight > 0
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val sourceWidth = highlightSourceWidth.toFloat()
                    val sourceHeight = highlightSourceHeight.toFloat()
                    val scale = min(
                        size.width / sourceWidth,
                        size.height / sourceHeight
                    )
                    val renderedWidth = sourceWidth * scale
                    val renderedHeight = sourceHeight * scale
                    val originX = (size.width - renderedWidth) / 2f
                    val originY = (size.height - renderedHeight) / 2f
                    val stroke = (2f * density).coerceAtLeast(1f)

                    highlightWords.forEach { word ->
                        val left = originX + word.left * scale
                        val top = originY + word.top * scale
                        val width = (word.right - word.left).coerceAtLeast(1) * scale
                        val height = (word.bottom - word.top).coerceAtLeast(1) * scale

                        drawRect(
                            color = highlightColor.copy(alpha = 0.20f),
                            topLeft = Offset(left, top),
                            size = Size(width, height)
                        )
                        drawRect(
                            color = highlightColor.copy(alpha = 0.92f),
                            topLeft = Offset(left, top),
                            size = Size(width, height),
                            style = Stroke(width = stroke)
                        )
                    }
                }
            }
        }
    }
}
