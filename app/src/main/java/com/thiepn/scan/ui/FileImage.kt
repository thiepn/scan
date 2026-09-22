package com.thiepn.scan.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

@Composable
fun FileImage(
    path: String,
    modifier: Modifier = Modifier,
    maxDecodeEdge: Int = 1600,
    rotationDegrees: Int = 0,
    contentDescription: String? = null
) {
    val image by produceState<ImageBitmap?>(
        initialValue = null,
        path,
        maxDecodeEdge,
        rotationDegrees
    ) {
        value = withContext(Dispatchers.IO) {
            decode(path, maxDecodeEdge, rotationDegrees)?.asImageBitmap()
        }
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

private fun decode(
    path: String,
    maxEdge: Int,
    rotationDegrees: Int
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxEdge * 2) {
        sample *= 2
    }

    val decoded = BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply { inSampleSize = sample }
    ) ?: return null

    val normalized = ((rotationDegrees % 360) + 360) % 360
    if (normalized == 0) return decoded

    val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
    val rotated = Bitmap.createBitmap(
        decoded,
        0,
        0,
        decoded.width,
        decoded.height,
        matrix,
        true
    )
    if (rotated !== decoded) decoded.recycle()
    return rotated
}
