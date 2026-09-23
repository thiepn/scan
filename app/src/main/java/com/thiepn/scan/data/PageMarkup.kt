package com.thiepn.scan.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Base64
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class PageMarkupKind(val label: String) {
    FREEHAND("Pen"),
    HIGHLIGHT("Highlight"),
    TEXT("Text"),
    RECTANGLE("Rectangle"),
    ARROW("Arrow"),
    STAMP("Stamp"),
    SIGNATURE("Signature"),
    INITIALS("Initials"),
    FORM_TEXT("Form text"),
    FORM_CHECKBOX("Checkbox"),
    FORM_RADIO("Radio"),
    FORM_DATE("Date"),
    REDACTION("Redact")
}

enum class SavedSignatureKind(val label: String) {
    SIGNATURE("Signature"),
    INITIALS("Initials")
}

data class PageMarkupItem(
    val id: String,
    val kind: PageMarkupKind,
    val points: List<NormalizedPoint> = emptyList(),
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
    val text: String = "",
    val colorArgb: Int = Color.BLACK,
    val fillArgb: Int? = null,
    val strokeWidth: Float = 0.006f,
    val opacity: Float = 1f,
    val checked: Boolean = false,
    val groupKey: String? = null
) {
    fun normalized(): PageMarkupItem {
        val normalizedPoints = points
            .map(NormalizedPoint::clamped)
            .take(MAX_POINTS)
        val pointLeft = normalizedPoints.minOfOrNull { it.x }
        val pointTop = normalizedPoints.minOfOrNull { it.y }
        val pointRight = normalizedPoints.maxOfOrNull { it.x }
        val pointBottom = normalizedPoints.maxOfOrNull { it.y }

        val rawLeft = min(left, right).coerceIn(0f, 1f)
        val rawRight = max(left, right).coerceIn(0f, 1f)
        val rawTop = min(top, bottom).coerceIn(0f, 1f)
        val rawBottom = max(top, bottom).coerceIn(0f, 1f)

        val l = if (rawRight > rawLeft) rawLeft else pointLeft ?: rawLeft
        val t = if (rawBottom > rawTop) rawTop else pointTop ?: rawTop
        val r = if (rawRight > rawLeft) rawRight else pointRight ?: rawRight
        val b = if (rawBottom > rawTop) rawBottom else pointBottom ?: rawBottom

        return copy(
            points = normalizedPoints,
            left = l.coerceIn(0f, 1f),
            top = t.coerceIn(0f, 1f),
            right = max(r, l + MIN_REGION_SIZE).coerceIn(0f, 1f),
            bottom = max(b, t + MIN_REGION_SIZE).coerceIn(0f, 1f),
            text = text.take(MAX_TEXT_LENGTH),
            strokeWidth = strokeWidth.coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH),
            opacity = opacity.coerceIn(0.05f, 1f),
            groupKey = groupKey?.take(80)
        )
    }

    companion object {
        const val MAX_POINTS = 600
        const val MAX_TEXT_LENGTH = 4000
        const val MIN_REGION_SIZE = 0.001f
        const val MIN_STROKE_WIDTH = 0.001f
        const val MAX_STROKE_WIDTH = 0.08f
    }
}

data class PageMarkupRecipe(
    val version: Int = CURRENT_VERSION,
    val items: List<PageMarkupItem> = emptyList()
) {
    fun normalized(): PageMarkupRecipe {
        val deduped = LinkedHashMap<String, PageMarkupItem>()
        items.map(PageMarkupItem::normalized).forEach { item ->
            deduped[item.id] = item
        }
        return copy(
            version = CURRENT_VERSION,
            items = deduped.values.toList().takeLast(MAX_ITEMS)
        )
    }

    fun isEmpty(): Boolean = items.isEmpty()

    fun hasRedactions(): Boolean =
        items.any { it.kind == PageMarkupKind.REDACTION }

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_ITEMS = 300
    }
}

object PageMarkupRecipeCodec {
    private const val FIELD = "\t"
    private const val RECORD = "\n"

    fun encode(recipe: PageMarkupRecipe?): String? {
        val normalized = recipe?.normalized() ?: return null
        if (normalized.isEmpty()) return null
        return buildList {
            add(listOf("H", PageMarkupRecipe.CURRENT_VERSION).joinToString(FIELD))
            normalized.items.forEach { item ->
                add(
                    listOf(
                        "I",
                        item.id,
                        item.kind.name,
                        item.left,
                        item.top,
                        item.right,
                        item.bottom,
                        item.colorArgb,
                        item.fillArgb?.toString() ?: "~",
                        item.strokeWidth,
                        item.opacity,
                        if (item.checked) 1 else 0,
                        encodeText(item.groupKey.orEmpty()),
                        encodeText(item.text),
                        encodePoints(item.points)
                    ).joinToString(FIELD)
                )
            }
        }.joinToString(RECORD)
    }

    fun decode(encoded: String?): PageMarkupRecipe {
        if (encoded.isNullOrBlank()) return PageMarkupRecipe()
        val records = encoded.split(RECORD)
        val header = records.firstOrNull()?.split(FIELD).orEmpty()
        if (
            header.size != 2 ||
            header[0] != "H" ||
            header[1].toIntOrNull() != PageMarkupRecipe.CURRENT_VERSION
        ) {
            return PageMarkupRecipe()
        }

        val items = records.drop(1).mapNotNull { record ->
            val fields = record.split(FIELD)
            if (fields.size != 15 || fields[0] != "I") return@mapNotNull null
            runCatching {
                PageMarkupItem(
                    id = fields[1],
                    kind = PageMarkupKind.valueOf(fields[2]),
                    left = fields[3].toFloat(),
                    top = fields[4].toFloat(),
                    right = fields[5].toFloat(),
                    bottom = fields[6].toFloat(),
                    colorArgb = fields[7].toInt(),
                    fillArgb = fields[8].takeUnless { it == "~" }?.toInt(),
                    strokeWidth = fields[9].toFloat(),
                    opacity = fields[10].toFloat(),
                    checked = fields[11] == "1",
                    groupKey = decodeText(fields[12]).ifBlank { null },
                    text = decodeText(fields[13]),
                    points = decodePoints(fields[14])
                ).normalized()
            }.getOrNull()
        }
        return PageMarkupRecipe(items = items).normalized()
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

    private fun encodePoints(points: List<NormalizedPoint>): String =
        points.joinToString(";") { "${it.x},${it.y}" }

    private fun decodePoints(encoded: String): List<NormalizedPoint> =
        encoded.split(';').mapNotNull { token ->
            if (token.isBlank()) return@mapNotNull null
            val parts = token.split(',')
            if (parts.size != 2) return@mapNotNull null
            val x = parts[0].toFloatOrNull() ?: return@mapNotNull null
            val y = parts[1].toFloatOrNull() ?: return@mapNotNull null
            NormalizedPoint(x, y).clamped()
        }
}

object SignaturePathCodec {
    fun encode(points: List<NormalizedPoint>): String =
        normalize(points).joinToString(";") { "${it.x},${it.y}" }

    fun decode(encoded: String): List<NormalizedPoint> =
        encoded.split(';').mapNotNull { token ->
            if (token.isBlank()) return@mapNotNull null
            val parts = token.split(',')
            if (parts.size != 2) return@mapNotNull null
            val x = parts[0].toFloatOrNull() ?: return@mapNotNull null
            val y = parts[1].toFloatOrNull() ?: return@mapNotNull null
            NormalizedPoint(x, y).clamped()
        }

    fun normalize(points: List<NormalizedPoint>): List<NormalizedPoint> {
        val clean = points.map(NormalizedPoint::clamped)
        if (clean.isEmpty()) return emptyList()
        val left = clean.minOf { it.x }
        val top = clean.minOf { it.y }
        val right = clean.maxOf { it.x }
        val bottom = clean.maxOf { it.y }
        val width = (right - left).coerceAtLeast(0.001f)
        val height = (bottom - top).coerceAtLeast(0.001f)
        return clean.map {
            NormalizedPoint(
                x = ((it.x - left) / width).coerceIn(0f, 1f),
                y = ((it.y - top) / height).coerceIn(0f, 1f)
            )
        }
    }

    fun place(
        saved: List<NormalizedPoint>,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): List<NormalizedPoint> {
        val l = min(left, right)
        val r = max(left, right)
        val t = min(top, bottom)
        val b = max(top, bottom)
        return saved.map {
            NormalizedPoint(
                x = (l + it.x * (r - l)).coerceIn(0f, 1f),
                y = (t + it.y * (b - t)).coerceIn(0f, 1f)
            )
        }
    }
}

object PageMarkupRenderer {
    fun apply(
        source: Bitmap,
        recipe: PageMarkupRecipe
    ): Bitmap {
        val normalized = recipe.normalized()
        if (normalized.isEmpty() || source.width < 2 || source.height < 2) {
            return source
        }

        val output = source.copy(Bitmap.Config.ARGB_8888, true) ?: return source
        val canvas = Canvas(output)
        normalized.items
            .filter { it.kind != PageMarkupKind.REDACTION }
            .forEach { drawItem(canvas, output, it) }
        normalized.items
            .filter { it.kind == PageMarkupKind.REDACTION }
            .forEach { drawRedaction(canvas, output, it) }
        return output
    }

    private fun drawItem(
        canvas: Canvas,
        bitmap: Bitmap,
        item: PageMarkupItem
    ) {
        val rect = item.rect(bitmap)
        val stroke = max(
            1.5f,
            item.strokeWidth * min(bitmap.width, bitmap.height)
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = item.colorArgb
            alpha = (item.opacity * 255f).roundToInt().coerceIn(0, 255)
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        when (item.kind) {
            PageMarkupKind.FREEHAND,
            PageMarkupKind.SIGNATURE,
            PageMarkupKind.INITIALS -> {
                paint.style = Paint.Style.STROKE
                drawPath(canvas, bitmap, item.points, paint)
            }

            PageMarkupKind.HIGHLIGHT -> {
                paint.style = Paint.Style.STROKE
                paint.alpha = (item.opacity.coerceAtMost(0.45f) * 255f)
                    .roundToInt()
                    .coerceIn(0, 255)
                paint.strokeWidth = max(stroke, min(bitmap.width, bitmap.height) * 0.018f)
                drawPath(canvas, bitmap, item.points, paint)
            }

            PageMarkupKind.TEXT -> {
                drawTextBox(canvas, rect, item.text, paint, false)
            }

            PageMarkupKind.RECTANGLE -> {
                item.fillArgb?.let { fill ->
                    paint.style = Paint.Style.FILL
                    paint.color = fill
                    paint.alpha = (item.opacity * 255f).roundToInt().coerceIn(0, 255)
                    canvas.drawRect(rect, paint)
                }
                paint.style = Paint.Style.STROKE
                paint.color = item.colorArgb
                canvas.drawRect(rect, paint)
            }

            PageMarkupKind.ARROW -> {
                paint.style = Paint.Style.STROKE
                drawArrow(canvas, rect, paint)
            }

            PageMarkupKind.STAMP -> {
                paint.style = Paint.Style.STROKE
                canvas.drawRoundRect(rect, rect.height() * 0.12f, rect.height() * 0.12f, paint)
                paint.style = Paint.Style.FILL
                drawTextBox(
                    canvas,
                    rect,
                    item.text.ifBlank { "APPROVED" }.uppercase(),
                    paint,
                    true
                )
            }

            PageMarkupKind.FORM_TEXT,
            PageMarkupKind.FORM_DATE -> {
                paint.style = Paint.Style.STROKE
                paint.alpha = (item.opacity * 150f).roundToInt().coerceIn(0, 255)
                canvas.drawRect(rect, paint)
                paint.style = Paint.Style.FILL
                paint.alpha = (item.opacity * 255f).roundToInt().coerceIn(0, 255)
                drawTextBox(canvas, rect, item.text, paint, false)
            }

            PageMarkupKind.FORM_CHECKBOX -> {
                paint.style = Paint.Style.STROKE
                canvas.drawRect(rect, paint)
                if (item.checked) {
                    canvas.drawLine(rect.left, rect.top, rect.right, rect.bottom, paint)
                    canvas.drawLine(rect.right, rect.top, rect.left, rect.bottom, paint)
                }
            }

            PageMarkupKind.FORM_RADIO -> {
                paint.style = Paint.Style.STROKE
                val radius = min(rect.width(), rect.height()) / 2f
                canvas.drawCircle(rect.centerX(), rect.centerY(), radius, paint)
                if (item.checked) {
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(rect.centerX(), rect.centerY(), radius * 0.48f, paint)
                }
            }

            PageMarkupKind.REDACTION -> Unit
        }
    }

    private fun drawRedaction(
        canvas: Canvas,
        bitmap: Bitmap,
        item: PageMarkupItem
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            alpha = 255
        }
        canvas.drawRect(item.rect(bitmap), paint)
    }

    private fun PageMarkupItem.rect(bitmap: Bitmap): RectF =
        RectF(
            left * bitmap.width,
            top * bitmap.height,
            right * bitmap.width,
            bottom * bitmap.height
        )

    private fun drawPath(
        canvas: Canvas,
        bitmap: Bitmap,
        points: List<NormalizedPoint>,
        paint: Paint
    ) {
        if (points.isEmpty()) return
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = point.x * bitmap.width
            val y = point.y * bitmap.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (points.size == 1) {
            canvas.drawCircle(
                points.first().x * bitmap.width,
                points.first().y * bitmap.height,
                paint.strokeWidth / 2f,
                paint
            )
        } else {
            canvas.drawPath(path, paint)
        }
    }

    private fun drawTextBox(
        canvas: Canvas,
        rect: RectF,
        text: String,
        paint: Paint,
        centered: Boolean
    ) {
        if (text.isBlank() || rect.width() <= 1f || rect.height() <= 1f) return
        val lines = text.split('\n').ifEmpty { listOf(text) }
        val slotHeight = rect.height() / lines.size.coerceAtLeast(1)
        lines.forEachIndexed { index, line ->
            if (line.isBlank()) return@forEachIndexed
            paint.textAlign = if (centered) Paint.Align.CENTER else Paint.Align.LEFT
            paint.textSize = fitTextSize(
                paint,
                line,
                rect.width() * 0.94f,
                slotHeight * 0.78f
            )
            val metrics = paint.fontMetrics
            val lineTop = rect.top + slotHeight * index
            val baseline = lineTop +
                (slotHeight - (metrics.bottom - metrics.top)) / 2f -
                metrics.top
            canvas.drawText(
                line,
                if (centered) rect.centerX() else rect.left + rect.width() * 0.03f,
                baseline,
                paint
            )
        }
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
            if (paint.measureText(text) <= maxWidth) low = mid else high = mid
        }
        return low
    }

    private fun drawArrow(
        canvas: Canvas,
        rect: RectF,
        paint: Paint
    ) {
        val x1 = rect.left
        val y1 = rect.top
        val x2 = rect.right
        val y2 = rect.bottom
        canvas.drawLine(x1, y1, x2, y2, paint)
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val head = max(10f, min(rect.width(), rect.height()).coerceAtLeast(20f) * 0.35f)
        val a1 = angle + Math.PI * 0.82
        val a2 = angle - Math.PI * 0.82
        canvas.drawLine(
            x2,
            y2,
            x2 + (cos(a1) * head).toFloat(),
            y2 + (sin(a1) * head).toFloat(),
            paint
        )
        canvas.drawLine(
            x2,
            y2,
            x2 + (cos(a2) * head).toFloat(),
            y2 + (sin(a2) * head).toFloat(),
            paint
        )
    }
}

data class RedactionVerification(
    val redactionCount: Int,
    val removedWordCount: Int,
    val exposedWordCount: Int
) {
    val secure: Boolean
        get() = exposedWordCount == 0
}

object OcrRedactionEngine {
    fun apply(
        base: OcrPageResult,
        recipe: PageMarkupRecipe
    ): OcrPageResult {
        val redactions = recipe.normalized().items.filter {
            it.kind == PageMarkupKind.REDACTION
        }
        if (redactions.isEmpty()) return base

        val remainingWords = base.words.filterNot { word ->
            redactions.any { intersects(word, base, it) }
        }

        val remainingLines = base.lines.mapNotNull { line ->
            val words = remainingWords
                .filter {
                    it.blockIndex == line.blockIndex &&
                        it.lineIndex == line.lineIndex
                }
                .sortedBy { it.left }
            if (words.isEmpty()) {
                null
            } else {
                line.copy(text = words.joinToString(" ") { it.text })
            }
        }.mapIndexed { index, line ->
            line.copy(readingOrder = index)
        }

        val remainingBlocks = base.blocks.mapNotNull { block ->
            val lines = remainingLines
                .filter { it.blockIndex == block.blockIndex }
                .sortedBy { it.readingOrder }
            if (lines.isEmpty()) {
                null
            } else {
                block.copy(text = lines.joinToString("\n") { it.text })
            }
        }

        val orderedWords = remainingWords
            .sortedBy { it.readingOrder }
            .mapIndexed { index, word -> word.copy(readingOrder = index) }

        val text = if (remainingBlocks.isNotEmpty()) {
            remainingBlocks
                .sortedBy { it.readingOrder }
                .joinToString("\n\n") { it.text.trim() }
                .trim()
        } else {
            remainingLines
                .sortedBy { it.readingOrder }
                .joinToString("\n") { it.text.trim() }
                .trim()
        }

        return base.copy(
            text = text,
            blocks = remainingBlocks,
            lines = remainingLines,
            words = orderedWords
        )
    }

    fun verify(
        base: OcrPageResult?,
        recipe: PageMarkupRecipe
    ): RedactionVerification {
        val normalized = recipe.normalized()
        val redactions = normalized.items.filter {
            it.kind == PageMarkupKind.REDACTION
        }
        if (base == null || redactions.isEmpty()) {
            return RedactionVerification(
                redactionCount = redactions.size,
                removedWordCount = 0,
                exposedWordCount = 0
            )
        }

        val removed = base.words.count { word ->
            redactions.any { intersects(word, base, it) }
        }
        val redacted = apply(base, normalized)
        val exposed = redacted.words.count { word ->
            redactions.any { intersects(word, redacted, it) }
        }
        return RedactionVerification(
            redactionCount = redactions.size,
            removedWordCount = removed,
            exposedWordCount = exposed
        )
    }

    private fun intersects(
        word: OcrWordBox,
        page: OcrPageResult,
        redaction: PageMarkupItem
    ): Boolean {
        if (page.sourceWidth <= 0 || page.sourceHeight <= 0) return false
        val left = word.left.toFloat() / page.sourceWidth
        val top = word.top.toFloat() / page.sourceHeight
        val right = word.right.toFloat() / page.sourceWidth
        val bottom = word.bottom.toFloat() / page.sourceHeight
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        if (
            centerX in redaction.left..redaction.right &&
            centerY in redaction.top..redaction.bottom
        ) {
            return true
        }

        val overlapWidth = (
            min(right, redaction.right) - max(left, redaction.left)
            ).coerceAtLeast(0f)
        val overlapHeight = (
            min(bottom, redaction.bottom) - max(top, redaction.top)
            ).coerceAtLeast(0f)
        val overlap = overlapWidth * overlapHeight
        val wordArea = ((right - left) * (bottom - top)).coerceAtLeast(0.000001f)
        return overlap / wordArea >= 0.12f
    }
}
