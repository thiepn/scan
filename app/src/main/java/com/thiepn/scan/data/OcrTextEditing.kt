package com.thiepn.scan.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.util.Base64
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class OcrTextEditTarget(val label: String) {
    WORD("Word"),
    LINE("Line"),
    BLOCK("Block")
}

enum class OcrTextAlignment(val label: String) {
    AUTO("Auto"),
    LEFT("Left"),
    CENTER("Center"),
    RIGHT("Right")
}

data class OcrEditableRegion(
    val id: String,
    val target: OcrTextEditTarget,
    val blockIndex: Int,
    val lineIndex: Int,
    val wordIndex: Int,
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val angleDegrees: Float
) {
    val area: Float
        get() = (right - left).coerceAtLeast(0f) *
            (bottom - top).coerceAtLeast(0f)
}

data class OcrTextEdit(
    val id: String,
    val target: OcrTextEditTarget,
    val blockIndex: Int,
    val lineIndex: Int,
    val wordIndex: Int,
    val originalText: String,
    val replacementText: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val angleDegrees: Float = 0f,
    val alignment: OcrTextAlignment = OcrTextAlignment.AUTO,
    val fontScale: Float = 1f
) {
    fun normalized(): OcrTextEdit {
        val l = min(left, right).coerceIn(0f, 1f)
        val r = max(left, right).coerceIn(0f, 1f)
        val t = min(top, bottom).coerceIn(0f, 1f)
        val b = max(top, bottom).coerceIn(0f, 1f)
        return copy(
            originalText = originalText.take(MAX_TEXT_LENGTH),
            replacementText = replacementText.take(MAX_TEXT_LENGTH),
            left = l,
            top = t,
            right = max(r, l + MIN_REGION_SIZE).coerceAtMost(1f),
            bottom = max(b, t + MIN_REGION_SIZE).coerceAtMost(1f),
            angleDegrees = angleDegrees.coerceIn(-45f, 45f),
            fontScale = fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        )
    }

    companion object {
        const val MAX_TEXT_LENGTH = 4000
        const val MIN_REGION_SIZE = 0.001f
        const val MIN_FONT_SCALE = 0.55f
        const val MAX_FONT_SCALE = 1.7f
    }
}

data class PageTextEditRecipe(
    val version: Int = CURRENT_VERSION,
    val edits: List<OcrTextEdit> = emptyList()
) {
    fun normalized(): PageTextEditRecipe {
        val deduped = LinkedHashMap<String, OcrTextEdit>()
        edits.map(OcrTextEdit::normalized).forEach { edit ->
            deduped[edit.id] = edit
        }
        return copy(
            version = CURRENT_VERSION,
            edits = deduped.values.toList().takeLast(MAX_EDITS)
        )
    }

    fun isEmpty(): Boolean = edits.isEmpty()

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_EDITS = 160
    }
}

object PageTextEditRecipeCodec {
    private const val FIELD = "\t"
    private const val RECORD = "\n"

    fun encode(recipe: PageTextEditRecipe?): String? {
        val normalized = recipe?.normalized() ?: return null
        if (normalized.isEmpty()) return null
        return buildList {
            add(
                listOf("H", PageTextEditRecipe.CURRENT_VERSION)
                    .joinToString(FIELD)
            )
            normalized.edits.forEach { edit ->
                add(
                    listOf(
                        "E",
                        edit.id,
                        edit.target.name,
                        edit.blockIndex,
                        edit.lineIndex,
                        edit.wordIndex,
                        edit.left,
                        edit.top,
                        edit.right,
                        edit.bottom,
                        edit.angleDegrees,
                        edit.alignment.name,
                        edit.fontScale,
                        encodeText(edit.originalText),
                        encodeText(edit.replacementText)
                    ).joinToString(FIELD)
                )
            }
        }.joinToString(RECORD)
    }

    fun decode(encoded: String?): PageTextEditRecipe {
        if (encoded.isNullOrBlank()) return PageTextEditRecipe()
        val records = encoded.split(RECORD)
        val header = records.firstOrNull()?.split(FIELD).orEmpty()
        if (
            header.size != 2 ||
            header[0] != "H" ||
            header[1].toIntOrNull() != PageTextEditRecipe.CURRENT_VERSION
        ) {
            return PageTextEditRecipe()
        }

        val edits = records.drop(1).mapNotNull { record ->
            val fields = record.split(FIELD)
            if (fields.size != 15 || fields[0] != "E") return@mapNotNull null
            runCatching {
                OcrTextEdit(
                    id = fields[1],
                    target = OcrTextEditTarget.valueOf(fields[2]),
                    blockIndex = fields[3].toInt(),
                    lineIndex = fields[4].toInt(),
                    wordIndex = fields[5].toInt(),
                    left = fields[6].toFloat(),
                    top = fields[7].toFloat(),
                    right = fields[8].toFloat(),
                    bottom = fields[9].toFloat(),
                    angleDegrees = fields[10].toFloat(),
                    alignment = OcrTextAlignment.valueOf(fields[11]),
                    fontScale = fields[12].toFloat(),
                    originalText = decodeText(fields[13]),
                    replacementText = decodeText(fields[14])
                ).normalized()
            }.getOrNull()
        }
        return PageTextEditRecipe(edits = edits).normalized()
    }

    private fun encodeText(value: String): String =
        if (value.isEmpty()) {
            "~"
        } else {
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toByteArray(Charsets.UTF_8))
        }

    private fun decodeText(value: String): String =
        if (value == "~") {
            ""
        } else {
            Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)
        }
}

object OcrTextEditEngine {
    fun regions(
        result: OcrPageResult?,
        target: OcrTextEditTarget
    ): List<OcrEditableRegion> {
        result ?: return emptyList()
        if (result.sourceWidth <= 0 || result.sourceHeight <= 0) return emptyList()
        val width = result.sourceWidth.toFloat()
        val height = result.sourceHeight.toFloat()

        return when (target) {
            OcrTextEditTarget.WORD -> result.words.map { word ->
                OcrEditableRegion(
                    id = regionId(target, word.blockIndex, word.lineIndex, word.wordIndex),
                    target = target,
                    blockIndex = word.blockIndex,
                    lineIndex = word.lineIndex,
                    wordIndex = word.wordIndex,
                    text = word.text,
                    left = word.left / width,
                    top = word.top / height,
                    right = word.right / width,
                    bottom = word.bottom / height,
                    angleDegrees = word.angleDegrees
                )
            }

            OcrTextEditTarget.LINE -> result.lines.map { line ->
                OcrEditableRegion(
                    id = regionId(target, line.blockIndex, line.lineIndex, -1),
                    target = target,
                    blockIndex = line.blockIndex,
                    lineIndex = line.lineIndex,
                    wordIndex = -1,
                    text = line.text,
                    left = line.left / width,
                    top = line.top / height,
                    right = line.right / width,
                    bottom = line.bottom / height,
                    angleDegrees = line.angleDegrees
                )
            }

            OcrTextEditTarget.BLOCK -> result.blocks.map { block ->
                val angles = result.lines
                    .filter { it.blockIndex == block.blockIndex }
                    .map { it.angleDegrees }
                OcrEditableRegion(
                    id = regionId(target, block.blockIndex, -1, -1),
                    target = target,
                    blockIndex = block.blockIndex,
                    lineIndex = -1,
                    wordIndex = -1,
                    text = block.text,
                    left = block.left / width,
                    top = block.top / height,
                    right = block.right / width,
                    bottom = block.bottom / height,
                    angleDegrees = if (angles.isEmpty()) 0f else angles.average().toFloat()
                )
            }
        }.filter {
            it.text.isNotBlank() &&
                it.right > it.left &&
                it.bottom > it.top
        }.sortedWith(
            compareBy<OcrEditableRegion> { it.top }
                .thenBy { it.left }
        )
    }

    fun createEdit(
        region: OcrEditableRegion,
        replacementText: String,
        alignment: OcrTextAlignment = OcrTextAlignment.AUTO,
        fontScale: Float = 1f
    ): OcrTextEdit = OcrTextEdit(
        id = region.id,
        target = region.target,
        blockIndex = region.blockIndex,
        lineIndex = region.lineIndex,
        wordIndex = region.wordIndex,
        originalText = region.text,
        replacementText = replacementText,
        left = region.left,
        top = region.top,
        right = region.right,
        bottom = region.bottom,
        angleDegrees = region.angleDegrees,
        alignment = alignment,
        fontScale = fontScale
    ).normalized()

    fun withEdit(
        recipe: PageTextEditRecipe,
        edit: OcrTextEdit
    ): PageTextEditRecipe {
        val normalized = edit.normalized()
        val retained = recipe.normalized().edits.filterNot {
            conflicts(it, normalized)
        }
        if (
            normalized.replacementText == normalized.originalText &&
            normalized.alignment == OcrTextAlignment.AUTO &&
            abs(normalized.fontScale - 1f) < 0.001f
        ) {
            return PageTextEditRecipe(edits = retained).normalized()
        }
        return PageTextEditRecipe(edits = retained + normalized).normalized()
    }

    fun removeEdit(
        recipe: PageTextEditRecipe,
        id: String
    ): PageTextEditRecipe = PageTextEditRecipe(
        edits = recipe.normalized().edits.filterNot { it.id == id }
    ).normalized()

    fun apply(
        base: OcrPageResult,
        recipe: PageTextEditRecipe
    ): OcrPageResult {
        val normalized = recipe.normalized()
        if (normalized.isEmpty()) return base

        val blocks = base.blocks.toMutableList()
        val lines = base.lines.toMutableList()
        val words = base.words.toMutableList()
        val affectedBlocks = mutableSetOf<Int>()

        normalized.edits.forEach { edit ->
            affectedBlocks += edit.blockIndex
            when (edit.target) {
                OcrTextEditTarget.WORD -> {
                    val originalWord = words.firstOrNull {
                        it.blockIndex == edit.blockIndex &&
                            it.lineIndex == edit.lineIndex &&
                            it.wordIndex == edit.wordIndex
                    }
                    words.removeAll {
                        it.blockIndex == edit.blockIndex &&
                            it.lineIndex == edit.lineIndex &&
                            it.wordIndex == edit.wordIndex
                    }
                    if (edit.replacementText.isNotBlank()) {
                        words += synthesizeWords(
                            edit = edit,
                            text = edit.replacementText.replace('\n', ' '),
                            sourceWidth = base.sourceWidth,
                            sourceHeight = base.sourceHeight,
                            lineIndex = edit.lineIndex,
                            readingOrder = originalWord?.readingOrder ?: words.size
                        )
                    }
                    val linePosition = lines.indexOfFirst {
                        it.blockIndex == edit.blockIndex &&
                            it.lineIndex == edit.lineIndex
                    }
                    if (linePosition >= 0) {
                        val line = lines[linePosition]
                        val lineWords = words
                            .filter {
                                it.blockIndex == edit.blockIndex &&
                                    it.lineIndex == edit.lineIndex &&
                                    it.text.isNotBlank()
                            }
                            .sortedBy { it.left }
                        lines[linePosition] = line.copy(
                            text = lineWords.joinToString(" ") { it.text }
                        )
                    }
                }

                OcrTextEditTarget.LINE -> {
                    val oldLine = lines.firstOrNull {
                        it.blockIndex == edit.blockIndex &&
                            it.lineIndex == edit.lineIndex
                    }
                    lines.removeAll {
                        it.blockIndex == edit.blockIndex &&
                            it.lineIndex == edit.lineIndex
                    }
                    words.removeAll {
                        it.blockIndex == edit.blockIndex &&
                            it.lineIndex == edit.lineIndex
                    }
                    val synthetic = synthesizeLines(
                        edit = edit,
                        sourceWidth = base.sourceWidth,
                        sourceHeight = base.sourceHeight,
                        firstLineIndex = edit.lineIndex,
                        readingOrder = oldLine?.readingOrder ?: lines.size
                    )
                    lines += synthetic.lines
                    words += synthetic.words
                }

                OcrTextEditTarget.BLOCK -> {
                    val oldLines = lines.filter { it.blockIndex == edit.blockIndex }
                    lines.removeAll { it.blockIndex == edit.blockIndex }
                    words.removeAll { it.blockIndex == edit.blockIndex }
                    val synthetic = synthesizeLines(
                        edit = edit,
                        sourceWidth = base.sourceWidth,
                        sourceHeight = base.sourceHeight,
                        firstLineIndex = 10_000 + edit.blockIndex * 100,
                        readingOrder = oldLines.minOfOrNull { it.readingOrder } ?: lines.size
                    )
                    lines += synthetic.lines
                    words += synthetic.words

                    val blockPosition = blocks.indexOfFirst {
                        it.blockIndex == edit.blockIndex
                    }
                    if (blockPosition >= 0) {
                        blocks[blockPosition] = blocks[blockPosition].copy(
                            text = edit.replacementText.trim()
                        )
                    }
                }
            }
        }

        val orderedLines = lines
            .filter { it.text.isNotBlank() }
            .sortedWith(
                compareBy<OcrTextLine> { it.readingOrder }
                    .thenBy { it.top }
                    .thenBy { it.left }
            )
            .mapIndexed { index, line -> line.copy(readingOrder = index) }

        val orderedWords = words
            .filter { it.text.isNotBlank() }
            .sortedWith(
                compareBy<OcrWordBox> { word ->
                    orderedLines.indexOfFirst {
                        it.blockIndex == word.blockIndex &&
                            it.lineIndex == word.lineIndex
                    }.let { if (it < 0) Int.MAX_VALUE else it }
                }.thenBy { it.left }
            )
            .mapIndexed { index, word -> word.copy(readingOrder = index) }

        val updatedBlocks = blocks.map { block ->
            val blockLines = orderedLines
                .filter { it.blockIndex == block.blockIndex }
                .sortedBy { it.readingOrder }
            when {
                blockLines.isNotEmpty() -> block.copy(
                    text = blockLines.joinToString("\n") { it.text }
                )
                block.blockIndex in affectedBlocks -> block.copy(text = "")
                else -> block
            }
        }

        val fullText = if (updatedBlocks.isNotEmpty()) {
            updatedBlocks
                .sortedBy { it.readingOrder }
                .map { it.text.trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
        } else {
            orderedLines
                .map { it.text.trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }

        return base.copy(
            text = fullText,
            blocks = updatedBlocks,
            lines = orderedLines,
            words = orderedWords
        )
    }

    private data class SyntheticLayout(
        val lines: List<OcrTextLine>,
        val words: List<OcrWordBox>
    )

    private fun synthesizeLines(
        edit: OcrTextEdit,
        sourceWidth: Int,
        sourceHeight: Int,
        firstLineIndex: Int,
        readingOrder: Int
    ): SyntheticLayout {
        val replacementLines = edit.replacementText
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (replacementLines.isEmpty()) return SyntheticLayout(emptyList(), emptyList())

        val left = (edit.left * sourceWidth).roundToInt()
        val right = (edit.right * sourceWidth).roundToInt().coerceAtLeast(left + 1)
        val top = (edit.top * sourceHeight).roundToInt()
        val bottom = (edit.bottom * sourceHeight).roundToInt().coerceAtLeast(top + 1)
        val slotHeight = (bottom - top).toFloat() / replacementLines.size
        val outputLines = mutableListOf<OcrTextLine>()
        val outputWords = mutableListOf<OcrWordBox>()

        replacementLines.forEachIndexed { index, text ->
            val lineTop = (top + slotHeight * index).roundToInt()
            val lineBottom = (top + slotHeight * (index + 1)).roundToInt()
                .coerceAtLeast(lineTop + 1)
            val lineIndex = if (index == 0) {
                firstLineIndex
            } else {
                firstLineIndex + 10_000 + index
            }
            outputLines += OcrTextLine(
                blockIndex = edit.blockIndex,
                lineIndex = lineIndex,
                readingOrder = readingOrder + index,
                text = text,
                left = left,
                top = lineTop,
                right = right,
                bottom = lineBottom,
                cornerPoints = emptyList(),
                languageTag = "",
                confidence = 1f,
                angleDegrees = edit.angleDegrees
            )
            outputWords += synthesizeWords(
                edit = edit.copy(
                    top = lineTop.toFloat() / sourceHeight,
                    bottom = lineBottom.toFloat() / sourceHeight
                ),
                text = text,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                lineIndex = lineIndex,
                readingOrder = readingOrder + outputWords.size
            )
        }
        return SyntheticLayout(outputLines, outputWords)
    }

    private fun synthesizeWords(
        edit: OcrTextEdit,
        text: String,
        sourceWidth: Int,
        sourceHeight: Int,
        lineIndex: Int,
        readingOrder: Int
    ): List<OcrWordBox> {
        val tokens = Regex("\\S+").findAll(text).map { it.value }.toList()
        if (tokens.isEmpty()) return emptyList()

        val left = (edit.left * sourceWidth).roundToInt()
        val right = (edit.right * sourceWidth).roundToInt().coerceAtLeast(left + 1)
        val top = (edit.top * sourceHeight).roundToInt()
        val bottom = (edit.bottom * sourceHeight).roundToInt().coerceAtLeast(top + 1)
        val totalUnits = tokens.sumOf { it.length }.coerceAtLeast(1) + (tokens.size - 1)
        var consumed = 0

        return tokens.mapIndexed { index, token ->
            val wordLeft = left + ((right - left) * consumed.toFloat() / totalUnits)
                .roundToInt()
            consumed += token.length
            val wordRight = if (index == tokens.lastIndex) {
                right
            } else {
                left + ((right - left) * consumed.toFloat() / totalUnits)
                    .roundToInt()
            }
            consumed += 1

            OcrWordBox(
                blockIndex = edit.blockIndex,
                lineIndex = lineIndex,
                wordIndex = if (tokens.size == 1) {
                    edit.wordIndex
                } else {
                    1_000_000 + edit.wordIndex.coerceAtLeast(0) * 100 + index
                },
                readingOrder = readingOrder + index,
                text = token,
                left = wordLeft,
                top = top,
                right = wordRight.coerceAtLeast(wordLeft + 1),
                bottom = bottom,
                cornerPoints = emptyList(),
                languageTag = "",
                confidence = 1f,
                angleDegrees = edit.angleDegrees
            )
        }
    }

    private fun conflicts(a: OcrTextEdit, b: OcrTextEdit): Boolean {
        if (a.blockIndex != b.blockIndex) return false
        if (a.target == OcrTextEditTarget.BLOCK || b.target == OcrTextEditTarget.BLOCK) {
            return true
        }
        if (a.lineIndex != b.lineIndex) return false
        if (a.target == OcrTextEditTarget.LINE || b.target == OcrTextEditTarget.LINE) {
            return true
        }
        return a.wordIndex == b.wordIndex
    }

    private fun regionId(
        target: OcrTextEditTarget,
        blockIndex: Int,
        lineIndex: Int,
        wordIndex: Int
    ): String = when (target) {
        OcrTextEditTarget.BLOCK -> "B:$blockIndex"
        OcrTextEditTarget.LINE -> "L:$blockIndex:$lineIndex"
        OcrTextEditTarget.WORD -> "W:$blockIndex:$lineIndex:$wordIndex"
    }
}

object OcrTextEditRenderer {
    fun apply(
        source: Bitmap,
        recipe: PageTextEditRecipe
    ): Bitmap {
        val normalized = recipe.normalized()
        if (normalized.isEmpty() || source.width < 2 || source.height < 2) {
            return source
        }

        val output = source.copy(Bitmap.Config.ARGB_8888, true) ?: return source
        normalized.edits.forEach { edit ->
            renderEdit(output, edit)
        }
        return output
    }

    private fun renderEdit(
        bitmap: Bitmap,
        edit: OcrTextEdit
    ) {
        val target = Rect(
            (edit.left * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1),
            (edit.top * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1),
            (edit.right * bitmap.width).roundToInt().coerceIn(1, bitmap.width),
            (edit.bottom * bitmap.height).roundToInt().coerceIn(1, bitmap.height)
        )
        if (target.width() <= 0 || target.height() <= 0) return

        val ink = sampleInk(bitmap, target)
        rebuildBackground(bitmap, target)
        if (edit.replacementText.isBlank()) return

        val lines = edit.replacementText
            .split('\n')
            .ifEmpty { listOf(edit.replacementText) }
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = ink.color
            typeface = Typeface.create(
                Typeface.DEFAULT,
                if (ink.bold) Typeface.BOLD else Typeface.NORMAL
            )
        }

        canvas.save()
        if (abs(edit.angleDegrees) >= 0.35f) {
            canvas.rotate(
                edit.angleDegrees,
                target.exactCenterX(),
                target.exactCenterY()
            )
        }

        val slotHeight = target.height().toFloat() / lines.size.coerceAtLeast(1)
        lines.forEachIndexed { index, line ->
            if (line.isEmpty()) return@forEachIndexed
            val maxSize = slotHeight * 0.82f * edit.fontScale
            paint.textSize = fitTextSize(
                paint = paint,
                text = line,
                maxWidth = target.width().toFloat(),
                maxSize = maxSize
            )
            val metrics = paint.fontMetrics
            val lineTop = target.top + slotHeight * index
            val baseline = lineTop +
                (slotHeight - (metrics.bottom - metrics.top)) / 2f -
                metrics.top
            val resolvedAlignment = resolveAlignment(edit)
            val x = when (resolvedAlignment) {
                OcrTextAlignment.CENTER -> target.centerX().toFloat()
                OcrTextAlignment.RIGHT -> target.right.toFloat()
                OcrTextAlignment.AUTO,
                OcrTextAlignment.LEFT -> target.left.toFloat()
            }
            paint.textAlign = when (resolvedAlignment) {
                OcrTextAlignment.CENTER -> Paint.Align.CENTER
                OcrTextAlignment.RIGHT -> Paint.Align.RIGHT
                OcrTextAlignment.AUTO,
                OcrTextAlignment.LEFT -> Paint.Align.LEFT
            }
            canvas.drawText(line, x, baseline, paint)
        }
        canvas.restore()
    }

    private fun resolveAlignment(edit: OcrTextEdit): OcrTextAlignment {
        if (edit.alignment != OcrTextAlignment.AUTO) return edit.alignment
        if (edit.target == OcrTextEditTarget.WORD) return OcrTextAlignment.LEFT

        val width = edit.right - edit.left
        val center = (edit.left + edit.right) / 2f
        return when {
            edit.right >= 0.88f && edit.left >= 0.45f ->
                OcrTextAlignment.RIGHT
            width <= 0.70f && abs(center - 0.5f) <= 0.08f ->
                OcrTextAlignment.CENTER
            else ->
                OcrTextAlignment.LEFT
        }
    }

    private data class InkSample(
        val color: Int,
        val bold: Boolean
    )

    private fun sampleInk(
        bitmap: Bitmap,
        rect: Rect
    ): InkSample {
        val background = sampleRingColor(bitmap, rect)
        val backgroundLuma = luma(background)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        val step = max(1, min(rect.width(), rect.height()) / 48)

        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                val color = bitmap.getPixel(x, y)
                if (luma(color) < backgroundLuma - 24) {
                    red += Color.red(color)
                    green += Color.green(color)
                    blue += Color.blue(color)
                    count++
                }
                x += step
            }
            y += step
        }

        if (count == 0L) {
            return InkSample(Color.rgb(32, 32, 32), false)
        }
        val sampledArea = (
            max(1, rect.width() / step) *
                max(1, rect.height() / step)
            ).coerceAtLeast(1)
        val density = count.toFloat() / sampledArea
        return InkSample(
            color = Color.rgb(
                (red / count).toInt().coerceIn(0, 255),
                (green / count).toInt().coerceIn(0, 255),
                (blue / count).toInt().coerceIn(0, 255)
            ),
            bold = density >= 0.27f
        )
    }

    private fun rebuildBackground(
        bitmap: Bitmap,
        rect: Rect
    ) {
        val pad = max(2, (rect.height() * 0.16f).roundToInt())
        val sampleRadius = max(1, pad / 2)
        val topLeft = sampleAverage(
            bitmap,
            rect.left - pad - sampleRadius,
            rect.top - pad - sampleRadius,
            rect.left - pad + sampleRadius,
            rect.top - pad + sampleRadius
        )
        val topRight = sampleAverage(
            bitmap,
            rect.right + pad - sampleRadius,
            rect.top - pad - sampleRadius,
            rect.right + pad + sampleRadius,
            rect.top - pad + sampleRadius
        )
        val bottomLeft = sampleAverage(
            bitmap,
            rect.left - pad - sampleRadius,
            rect.bottom + pad - sampleRadius,
            rect.left - pad + sampleRadius,
            rect.bottom + pad + sampleRadius
        )
        val bottomRight = sampleAverage(
            bitmap,
            rect.right + pad - sampleRadius,
            rect.bottom + pad - sampleRadius,
            rect.right + pad + sampleRadius,
            rect.bottom + pad + sampleRadius
        )

        val width = rect.width().coerceAtLeast(1)
        val height = rect.height().coerceAtLeast(1)
        for (y in rect.top until rect.bottom) {
            val ty = (y - rect.top).toFloat() / height
            for (x in rect.left until rect.right) {
                val tx = (x - rect.left).toFloat() / width
                val topColor = lerpColor(topLeft, topRight, tx)
                val bottomColor = lerpColor(bottomLeft, bottomRight, tx)
                bitmap.setPixel(x, y, lerpColor(topColor, bottomColor, ty))
            }
        }
    }

    private fun sampleRingColor(
        bitmap: Bitmap,
        rect: Rect
    ): Int {
        val pad = max(2, (rect.height() * 0.14f).roundToInt())
        return sampleAverage(
            bitmap,
            rect.left - pad,
            rect.top - pad,
            rect.right + pad,
            rect.bottom + pad,
            excluded = rect
        )
    }

    private fun sampleAverage(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        excluded: Rect? = null
    ): Int {
        val l = left.coerceIn(0, bitmap.width - 1)
        val t = top.coerceIn(0, bitmap.height - 1)
        val r = right.coerceIn(l + 1, bitmap.width)
        val b = bottom.coerceIn(t + 1, bitmap.height)
        val step = max(1, min(r - l, b - t) / 18)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L

        var y = t
        while (y < b) {
            var x = l
            while (x < r) {
                if (excluded == null || !excluded.contains(x, y)) {
                    val color = bitmap.getPixel(x, y)
                    red += Color.red(color)
                    green += Color.green(color)
                    blue += Color.blue(color)
                    count++
                }
                x += step
            }
            y += step
        }

        if (count == 0L) return Color.rgb(245, 245, 245)
        return Color.rgb(
            (red / count).toInt().coerceIn(0, 255),
            (green / count).toInt().coerceIn(0, 255),
            (blue / count).toInt().coerceIn(0, 255)
        )
    }

    private fun fitTextSize(
        paint: Paint,
        text: String,
        maxWidth: Float,
        maxSize: Float
    ): Float {
        var low = 2f
        var high = max(2f, maxSize)
        repeat(10) {
            val mid = (low + high) / 2f
            paint.textSize = mid
            if (paint.measureText(text) <= maxWidth) {
                low = mid
            } else {
                high = mid
            }
        }
        return low
    }

    private fun lerpColor(
        a: Int,
        b: Int,
        t: Float
    ): Int {
        val clamped = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * clamped)
                .roundToInt()
                .coerceIn(0, 255),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * clamped)
                .roundToInt()
                .coerceIn(0, 255),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * clamped)
                .roundToInt()
                .coerceIn(0, 255)
        )
    }

    private fun luma(color: Int): Int =
        (
            Color.red(color) * 0.2126f +
                Color.green(color) * 0.7152f +
                Color.blue(color) * 0.0722f
            ).roundToInt()
}
