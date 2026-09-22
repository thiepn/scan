package com.thiepn.scan.data

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File

data class OcrWordBox(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

data class OcrPageResult(
    val text: String,
    val words: List<OcrWordBox>
)

class OcrEngine(private val context: Context) {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognize(file: File): String = recognizeDetailed(file).text

    suspend fun recognize(bitmap: Bitmap): String = recognizeDetailed(bitmap).text

    suspend fun recognizeDetailed(file: File): OcrPageResult {
        val image = InputImage.fromFilePath(context, Uri.fromFile(file))
        return recognizeInput(image)
    }

    suspend fun recognizeDetailed(bitmap: Bitmap): OcrPageResult =
        recognizeInput(InputImage.fromBitmap(bitmap, 0))

    private suspend fun recognizeInput(image: InputImage): OcrPageResult {
        val result = recognizer.process(image).await()
        val words = buildList {
            result.textBlocks.forEach { block ->
                block.lines.forEach { line ->
                    line.elements.forEach { element ->
                        val box = element.boundingBox ?: return@forEach
                        val text = element.text.trim()
                        if (text.isNotEmpty()) {
                            add(
                                OcrWordBox(
                                    text = text,
                                    left = box.left,
                                    top = box.top,
                                    right = box.right,
                                    bottom = box.bottom
                                )
                            )
                        }
                    }
                }
            }
        }
        return OcrPageResult(result.text.trim(), words)
    }
}
