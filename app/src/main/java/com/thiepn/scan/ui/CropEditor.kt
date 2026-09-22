package com.thiepn.scan.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.thiepn.scan.data.CropQuad
import com.thiepn.scan.data.CropQuadCodec
import com.thiepn.scan.data.GeometryRotation
import com.thiepn.scan.data.NormalizedPoint
import com.thiepn.scan.data.PageEntity
import com.thiepn.scan.data.PageGeometryRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

@Composable
fun CropEditorDialog(
    page: PageEntity,
    onDismiss: () -> Unit,
    onAutoDetect: suspend () -> CropQuad?,
    onSave: (CropQuad?) -> Unit
) {
    val scope = rememberCoroutineScope()
    var draft by remember(page.id, page.cropQuad) {
        mutableStateOf(CropQuadCodec.decode(page.cropQuad))
    }
    var detecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        page.id,
        page.imagePath,
        page.rotationDegrees
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                PageGeometryRenderer.renderFile(
                    file = File(page.imagePath),
                    cropQuad = CropQuad.FULL,
                    rotationDegrees = page.rotationDegrees,
                    maxLongEdge = 1800
                )
            }.getOrNull()
        }
    }

    DisposableEffect(bitmap) {
        onDispose { bitmap?.recycle() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }

                    OutlinedButton(
                        onClick = {
                            detecting = true
                            status = null
                            scope.launch {
                                val result = runCatching { onAutoDetect() }.getOrNull()
                                if (result != null) {
                                    draft = result
                                    status = "Boundary detected"
                                } else {
                                    status = "No stronger boundary found"
                                }
                                detecting = false
                            }
                        },
                        enabled = !detecting
                    ) {
                        if (detecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 6.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                        }
                        Text(" Auto")
                    }

                    OutlinedButton(
                        onClick = {
                            draft = CropQuad.FULL
                            status = "Crop reset"
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text(" Reset")
                    }

                    Box(Modifier.weight(1f))

                    Button(
                        onClick = {
                            onSave(draft.takeUnless { it.isFullFrame() })
                        }
                    ) {
                        Text("Save")
                    }
                }

                status?.let {
                    Text(
                        it,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                val sourceBitmap = bitmap
                if (sourceBitmap == null) {
                    Box(Modifier.fillMaxSize()) {
                        CircularProgressIndicator(Modifier.padding(24.dp))
                    }
                } else {
                    GeometryCanvas(
                        bitmap = sourceBitmap,
                        sourceQuad = draft,
                        rotationDegrees = page.rotationDegrees,
                        onQuadChanged = { draft = it },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun GeometryCanvas(
    bitmap: Bitmap,
    sourceQuad: CropQuad,
    rotationDegrees: Int,
    onQuadChanged: (CropQuad) -> Unit,
    modifier: Modifier = Modifier
) {
    var activeCorner by remember { mutableStateOf<Int?>(null) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    Canvas(
        modifier = modifier.pointerInput(bitmap, rotationDegrees) {
            val hitRadius = 58.dp.toPx()
            androidx.compose.foundation.gestures.detectDragGestures(
                onDragStart = { position ->
                    val rect = fitRect(
                        Size(size.width.toFloat(), size.height.toFloat()),
                        bitmap.width,
                        bitmap.height
                    )
                    val displayPoints = sourceQuad.points.map {
                        GeometryRotation.sourceToDisplay(it, rotationDegrees)
                    }
                    activeCorner = displayPoints
                        .mapIndexed { index, point ->
                            index to distance(position, pointToOffset(point, rect))
                        }
                        .filter { it.second <= hitRadius }
                        .minByOrNull { it.second }
                        ?.first
                },
                onDragEnd = { activeCorner = null },
                onDragCancel = { activeCorner = null },
                onDrag = { change, _ ->
                    val index = activeCorner ?: return@detectDragGestures
                    val rect = fitRect(
                        Size(size.width.toFloat(), size.height.toFloat()),
                        bitmap.width,
                        bitmap.height
                    )
                    if (rect.width <= 0f || rect.height <= 0f) return@detectDragGestures

                    val displayPoint = NormalizedPoint(
                        x = ((change.position.x - rect.left) / rect.width).coerceIn(0f, 1f),
                        y = ((change.position.y - rect.top) / rect.height).coerceIn(0f, 1f)
                    )
                    val sourcePoint = GeometryRotation.displayToSource(
                        displayPoint,
                        rotationDegrees
                    ).clamped()

                    sourceQuad.withCorner(index, sourcePoint)?.let(onQuadChanged)
                    change.consume()
                }
            )
        }
    ) {
        val rect = fitRect(size, bitmap.width, bitmap.height)
        drawImage(
            image = image,
            dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
            dstSize = IntSize(
                max(1, rect.width.toInt()),
                max(1, rect.height.toInt())
            ),
            filterQuality = FilterQuality.High
        )

        val displayPoints = sourceQuad.points.map {
            GeometryRotation.sourceToDisplay(it, rotationDegrees)
        }
        val offsets = displayPoints.map { pointToOffset(it, rect) }
        val cropPath = Path().apply {
            moveTo(offsets[0].x, offsets[0].y)
            lineTo(offsets[1].x, offsets[1].y)
            lineTo(offsets[2].x, offsets[2].y)
            lineTo(offsets[3].x, offsets[3].y)
            close()
        }

        clipPath(cropPath, ClipOp.Difference) {
            drawRect(Color.Black.copy(alpha = 0.56f))
        }

        drawPath(
            cropPath,
            color = Color.White,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 2.5.dp.toPx()
            )
        )

        offsets.forEachIndexed { index, point ->
            drawCircle(
                color = if (activeCorner == index) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.White
                },
                radius = if (activeCorner == index) 14.dp.toPx() else 11.dp.toPx(),
                center = point
            )
            drawCircle(
                color = Color.Black,
                radius = if (activeCorner == index) 7.dp.toPx() else 5.dp.toPx(),
                center = point
            )
        }

        val active = activeCorner
        if (active != null) {
            drawLoupe(
                image = image,
                imageRect = rect,
                point = offsets[active],
                canvasSize = size
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLoupe(
    image: androidx.compose.ui.graphics.ImageBitmap,
    imageRect: Rect,
    point: Offset,
    canvasSize: Size
) {
    val diameter = 132.dp.toPx()
    val radius = diameter / 2f
    val margin = 18.dp.toPx()
    val center = Offset(
        x = canvasSize.width - radius - margin,
        y = radius + margin
    )

    val nx = ((point.x - imageRect.left) / imageRect.width).coerceIn(0f, 1f)
    val ny = ((point.y - imageRect.top) / imageRect.height).coerceIn(0f, 1f)
    val srcHalf = max(18, min(image.width, image.height) / 12)
    val srcX = (nx * image.width).toInt()
        .coerceIn(srcHalf, max(srcHalf, image.width - srcHalf))
    val srcY = (ny * image.height).toInt()
        .coerceIn(srcHalf, max(srcHalf, image.height - srcHalf))

    val circle = Path().apply {
        addOval(
            Rect(
                center.x - radius,
                center.y - radius,
                center.x + radius,
                center.y + radius
            )
        )
    }

    clipPath(circle) {
        drawImage(
            image = image,
            srcOffset = IntOffset(srcX - srcHalf, srcY - srcHalf),
            srcSize = IntSize(srcHalf * 2, srcHalf * 2),
            dstOffset = IntOffset(
                (center.x - radius).toInt(),
                (center.y - radius).toInt()
            ),
            dstSize = IntSize(diameter.toInt(), diameter.toInt()),
            filterQuality = FilterQuality.None
        )
    }
    drawCircle(
        color = Color.White,
        radius = radius,
        center = center,
        style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())
    )
    drawLine(
        Color.White,
        Offset(center.x - 12.dp.toPx(), center.y),
        Offset(center.x + 12.dp.toPx(), center.y),
        strokeWidth = 1.dp.toPx()
    )
    drawLine(
        Color.White,
        Offset(center.x, center.y - 12.dp.toPx()),
        Offset(center.x, center.y + 12.dp.toPx()),
        strokeWidth = 1.dp.toPx()
    )
}

private fun fitRect(
    canvasSize: Size,
    imageWidth: Int,
    imageHeight: Int
): Rect {
    if (imageWidth <= 0 || imageHeight <= 0) return Rect.Zero
    val scale = min(
        canvasSize.width / imageWidth.toFloat(),
        canvasSize.height / imageHeight.toFloat()
    )
    val width = imageWidth * scale
    val height = imageHeight * scale
    val left = (canvasSize.width - width) / 2f
    val top = (canvasSize.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

private fun pointToOffset(point: NormalizedPoint, rect: Rect): Offset =
    Offset(
        x = rect.left + point.x * rect.width,
        y = rect.top + point.y * rect.height
    )

private fun distance(a: Offset, b: Offset): Float =
    hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
