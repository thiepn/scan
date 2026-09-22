package com.thiepn.scan.ui

import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.thiepn.scan.data.CropQuadCodec
import com.thiepn.scan.data.ImageEnhancementRenderer
import com.thiepn.scan.data.PageGeometryRenderer
import com.thiepn.scan.data.PageVisualRecipeCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun FileImage(
    path: String,
    modifier: Modifier = Modifier,
    maxDecodeEdge: Int = 1600,
    rotationDegrees: Int = 0,
    cropQuad: String? = null,
    visualRecipe: String? = null,
    contentDescription: String? = null
) {
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        path,
        maxDecodeEdge,
        rotationDegrees,
        cropQuad,
        visualRecipe
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val geometry = PageGeometryRenderer.renderFile(
                    file = File(path),
                    cropQuad = CropQuadCodec.decode(cropQuad),
                    rotationDegrees = rotationDegrees,
                    maxLongEdge = maxDecodeEdge
                )
                val enhanced = ImageEnhancementRenderer.apply(
                    geometry,
                    PageVisualRecipeCodec.decode(visualRecipe)
                )
                if (enhanced !== geometry) geometry.recycle()
                enhanced
            }.getOrNull()
        }
    }

    DisposableEffect(bitmap) {
        onDispose { bitmap?.recycle() }
    }

    val image = remember(bitmap) { bitmap?.asImageBitmap() }

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
