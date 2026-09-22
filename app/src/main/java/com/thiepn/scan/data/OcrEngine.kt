package com.thiepn.scan.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File

class OcrEngine(private val context: Context) {
    private val recognizers = mutableMapOf<OcrScript, TextRecognizer>()

    suspend fun recognize(
        file: File,
        script: OcrScript = OcrScript.LATIN
    ): String = recognizeDetailed(file, script).text

    suspend fun recognize(
        bitmap: Bitmap,
        script: OcrScript = OcrScript.LATIN
    ): String = recognizeDetailed(bitmap, script).text

    suspend fun recognizeDetailed(
        file: File,
        script: OcrScript = OcrScript.LATIN
    ): OcrPageResult {
        val image = InputImage.fromFilePath(context, Uri.fromFile(file))
        return recognizeInput(image, script)
    }

    suspend fun recognizeDetailed(
        bitmap: Bitmap,
        script: OcrScript = OcrScript.LATIN
    ): OcrPageResult = recognizeInput(InputImage.fromBitmap(bitmap, 0), script)

    private suspend fun recognizeInput(
        image: InputImage,
        script: OcrScript
    ): OcrPageResult {
        val result = recognizerFor(script).process(image).await()
        val blocks = mutableListOf<OcrTextBlock>()
        val lines = mutableListOf<OcrTextLine>()
        val words = mutableListOf<OcrWordBox>()
        var blockOrder = 0
        var lineOrder = 0
        var wordOrder = 0

        result.textBlocks.forEachIndexed { blockIndex, block ->
            val blockBox = block.boundingBox
            if (blockBox != null && block.text.isNotBlank()) {
                blocks += OcrTextBlock(
                    blockIndex = blockIndex,
                    readingOrder = blockOrder++,
                    text = block.text.trim(),
                    left = blockBox.left,
                    top = blockBox.top,
                    right = blockBox.right,
                    bottom = blockBox.bottom,
                    cornerPoints = block.cornerPoints.toOcrPoints(),
                    languageTag = block.recognizedLanguage
                )
            }

            block.lines.forEachIndexed { lineIndex, line ->
                val lineBox = line.boundingBox
                if (lineBox != null && line.text.isNotBlank()) {
                    lines += OcrTextLine(
                        blockIndex = blockIndex,
                        lineIndex = lineIndex,
                        readingOrder = lineOrder++,
                        text = line.text.trim(),
                        left = lineBox.left,
                        top = lineBox.top,
                        right = lineBox.right,
                        bottom = lineBox.bottom,
                        cornerPoints = line.cornerPoints.toOcrPoints(),
                        languageTag = line.recognizedLanguage,
                        confidence = line.confidence,
                        angleDegrees = line.angle
                    )
                }

                line.elements.forEachIndexed { wordIndex, element ->
                    val box = element.boundingBox ?: return@forEachIndexed
                    val text = element.text.trim()
                    if (text.isNotEmpty()) {
                        words += OcrWordBox(
                            blockIndex = blockIndex,
                            lineIndex = lineIndex,
                            wordIndex = wordIndex,
                            readingOrder = wordOrder++,
                            text = text,
                            left = box.left,
                            top = box.top,
                            right = box.right,
                            bottom = box.bottom,
                            cornerPoints = element.cornerPoints.toOcrPoints(),
                            languageTag = element.recognizedLanguage,
                            confidence = element.confidence,
                            angleDegrees = element.angle
                        )
                    }
                }
            }
        }

        return OcrPageResult(
            text = result.text.trim(),
            blocks = blocks,
            lines = lines,
            words = words,
            sourceWidth = image.width,
            sourceHeight = image.height,
            script = script
        )
    }

    @Synchronized
    private fun recognizerFor(script: OcrScript): TextRecognizer =
        recognizers.getOrPut(script) {
            when (script) {
                OcrScript.LATIN ->
                    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

                OcrScript.CHINESE ->
                    TextRecognition.getClient(
                        ChineseTextRecognizerOptions.Builder().build()
                    )

                OcrScript.DEVANAGARI ->
                    TextRecognition.getClient(
                        DevanagariTextRecognizerOptions.Builder().build()
                    )

                OcrScript.JAPANESE ->
                    TextRecognition.getClient(
                        JapaneseTextRecognizerOptions.Builder().build()
                    )

                OcrScript.KOREAN ->
                    TextRecognition.getClient(
                        KoreanTextRecognizerOptions.Builder().build()
                    )
            }
        }
}

private fun Array<Point>?.toOcrPoints(): List<OcrPoint> =
    this?.map { OcrPoint(it.x, it.y) }.orEmpty()
