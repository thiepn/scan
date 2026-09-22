package com.thiepn.scan.data

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File

class OcrEngine(private val context: Context) {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognize(file: File): String {
        val image = InputImage.fromFilePath(context, Uri.fromFile(file))
        return recognizer.process(image).await().text.trim()
    }
}
