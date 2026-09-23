package com.thiepn.scan.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.thiepn.scan.data.CropQuadCodec
import com.thiepn.scan.data.ImageEnhancementRenderer
import com.thiepn.scan.data.PageCleanupRecipeCodec
import com.thiepn.scan.data.PageCleanupRenderer
import com.thiepn.scan.data.PageEntity
import com.thiepn.scan.data.PageGeometryRenderer
import com.thiepn.scan.data.PageVisualRecipe
import com.thiepn.scan.data.PageVisualRecipeCodec
import com.thiepn.scan.data.ScanPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@Composable
fun EnhancementEditorDialog(
    page: PageEntity,
    onDismiss: () -> Unit,
    onSave: (PageVisualRecipe) -> Unit
) {
    var recipe by remember(page.id, page.visualRecipe) {
        mutableStateOf(PageVisualRecipeCodec.decode(page.visualRecipe))
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
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    OutlinedButton(
                        onClick = { recipe = PageVisualRecipe.forPreset(ScanPreset.ORIGINAL) }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text(" Reset")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onSave(recipe.normalized()) }) {
                        Text("Save")
                    }
                }

                EnhancementPreview(
                    page = page,
                    recipe = recipe,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.48f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.52f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 28.dp)
                ) {
                    Text(
                        "Presets",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presetLabels.forEach { (preset, label) ->
                            FilterChip(
                                selected = recipe.preset == preset,
                                onClick = {
                                    recipe = PageVisualRecipe.forPreset(preset)
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    Text(
                        "Adjustments",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                    )

                    SignedSlider("Brightness", recipe.brightness) {
                        recipe = recipe.copy(brightness = it)
                    }
                    SignedSlider("Contrast", recipe.contrast) {
                        recipe = recipe.copy(contrast = it)
                    }
                    SignedSlider("Highlights", recipe.highlights) {
                        recipe = recipe.copy(highlights = it)
                    }
                    SignedSlider("Shadows", recipe.shadows) {
                        recipe = recipe.copy(shadows = it)
                    }
                    PositiveSlider("Black point", recipe.blackPoint) {
                        recipe = recipe.copy(blackPoint = it)
                    }
                    PositiveSlider("White point", recipe.whitePoint) {
                        recipe = recipe.copy(whitePoint = it)
                    }
                    SignedSlider("Warmth", recipe.warmth) {
                        recipe = recipe.copy(warmth = it)
                    }
                    SignedSlider("Saturation", recipe.saturation) {
                        recipe = recipe.copy(saturation = it)
                    }
                    PositiveSlider("Sharpness", recipe.sharpness) {
                        recipe = recipe.copy(sharpness = it)
                    }
                    PositiveSlider("Background whitening", recipe.backgroundWhitening) {
                        recipe = recipe.copy(backgroundWhitening = it)
                    }
                    PositiveSlider("Shadow normalization", recipe.shadowNormalization) {
                        recipe = recipe.copy(shadowNormalization = it)
                    }
                }
            }
        }
    }
}

@Composable
private fun EnhancementPreview(
    page: PageEntity,
    recipe: PageVisualRecipe,
    modifier: Modifier = Modifier
) {
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        page.id,
        page.imagePath,
        page.cropQuad,
        page.rotationDegrees,
        page.cleanupRecipe,
        recipe
    ) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val geometry = PageGeometryRenderer.renderUnrotatedForPdf(
                    file = File(page.imagePath),
                    cropQuad = CropQuadCodec.decode(page.cropQuad),
                    maxLongEdge = 1400
                )
                val cleaned = PageCleanupRenderer.apply(
                    geometry,
                    PageCleanupRecipeCodec.decode(page.cleanupRecipe)
                )
                if (cleaned !== geometry) geometry.recycle()
                val enhanced = ImageEnhancementRenderer.apply(cleaned, recipe)
                if (enhanced !== cleaned) cleaned.recycle()
                val rotated = PageGeometryRenderer.rotateBitmap(
                    enhanced,
                    page.rotationDegrees
                )
                if (rotated !== enhanced) enhanced.recycle()
                rotated
            }.getOrNull()
        }
    }

    DisposableEffect(bitmap) {
        onDispose { bitmap?.recycle() }
    }

    val image = remember(bitmap) { bitmap?.asImageBitmap() }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = "Enhancement preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun SignedSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit
) {
    ValueSlider(
        label = label,
        value = value,
        range = -1f..1f,
        valueText = signedPercent(value),
        onChange = onChange
    )
}

@Composable
private fun PositiveSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit
) {
    ValueSlider(
        label = label,
        value = value,
        range = 0f..1f,
        valueText = "${(value * 100f).roundToInt()}%",
        onChange = onChange
    )
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range
        )
    }
}

private fun signedPercent(value: Float): String {
    val percent = (value * 100f).roundToInt()
    return if (percent > 0) "+$percent%" else "$percent%"
}

private val presetLabels = listOf(
    ScanPreset.ORIGINAL to "Original",
    ScanPreset.AUTO to "Auto",
    ScanPreset.CLEAN to "Clean",
    ScanPreset.COLOR to "Color",
    ScanPreset.GRAYSCALE to "Grayscale",
    ScanPreset.BLACK_WHITE to "B&W",
    ScanPreset.NOTES to "Notes",
    ScanPreset.RECEIPT to "Receipt",
    ScanPreset.WHITEBOARD to "Whiteboard"
)
