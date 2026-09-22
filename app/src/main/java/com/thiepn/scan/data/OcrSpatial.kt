package com.thiepn.scan.data

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.roundToInt

enum class OcrScript(val label: String) {
    LATIN("Latin"),
    CHINESE("Chinese + Latin"),
    DEVANAGARI("Devanagari + Latin"),
    JAPANESE("Japanese + Latin"),
    KOREAN("Korean + Latin");

    companion object {
        fun fromStored(value: String?): OcrScript =
            entries.firstOrNull { it.name == value } ?: LATIN
    }
}

data class OcrPoint(
    val x: Int,
    val y: Int
)

data class OcrTextBlock(
    val blockIndex: Int,
    val readingOrder: Int,
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val cornerPoints: List<OcrPoint>,
    val languageTag: String
)

data class OcrTextLine(
    val blockIndex: Int,
    val lineIndex: Int,
    val readingOrder: Int,
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val cornerPoints: List<OcrPoint>,
    val languageTag: String,
    val confidence: Float,
    val angleDegrees: Float
)

data class OcrWordBox(
    val blockIndex: Int,
    val lineIndex: Int,
    val wordIndex: Int,
    val readingOrder: Int,
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val cornerPoints: List<OcrPoint>,
    val languageTag: String,
    val confidence: Float,
    val angleDegrees: Float
)

data class OcrPageResult(
    val text: String,
    val blocks: List<OcrTextBlock>,
    val lines: List<OcrTextLine>,
    val words: List<OcrWordBox>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val script: OcrScript
)

object OcrLayoutCodec {
    private const val VERSION = 1
    private const val RECORD_SEPARATOR = "\n"
    private const val FIELD_SEPARATOR = "\t"

    fun encode(result: OcrPageResult): String {
        val lines = ArrayList<String>(
            1 + result.blocks.size + result.lines.size + result.words.size
        )
        lines += listOf(
            "H",
            VERSION.toString(),
            result.script.name,
            result.sourceWidth.toString(),
            result.sourceHeight.toString(),
            encodeText(result.text)
        ).joinToString(FIELD_SEPARATOR)

        result.blocks.forEach { block ->
            lines += listOf(
                "B",
                block.blockIndex.toString(),
                block.readingOrder.toString(),
                block.left.toString(),
                block.top.toString(),
                block.right.toString(),
                block.bottom.toString(),
                encodePoints(block.cornerPoints),
                encodeText(block.languageTag),
                encodeText(block.text)
            ).joinToString(FIELD_SEPARATOR)
        }

        result.lines.forEach { line ->
            lines += listOf(
                "L",
                line.blockIndex.toString(),
                line.lineIndex.toString(),
                line.readingOrder.toString(),
                line.left.toString(),
                line.top.toString(),
                line.right.toString(),
                line.bottom.toString(),
                encodePoints(line.cornerPoints),
                encodeText(line.languageTag),
                line.confidence.toString(),
                line.angleDegrees.toString(),
                encodeText(line.text)
            ).joinToString(FIELD_SEPARATOR)
        }

        result.words.forEach { word ->
            lines += listOf(
                "W",
                word.blockIndex.toString(),
                word.lineIndex.toString(),
                word.wordIndex.toString(),
                word.readingOrder.toString(),
                word.left.toString(),
                word.top.toString(),
                word.right.toString(),
                word.bottom.toString(),
                encodePoints(word.cornerPoints),
                encodeText(word.languageTag),
                word.confidence.toString(),
                word.angleDegrees.toString(),
                encodeText(word.text)
            ).joinToString(FIELD_SEPARATOR)
        }

        return lines.joinToString(RECORD_SEPARATOR)
    }

    fun decode(encoded: String?): OcrPageResult? {
        if (encoded.isNullOrBlank()) return null
        val records = encoded.split(RECORD_SEPARATOR)
        val header = records.firstOrNull()?.split(FIELD_SEPARATOR) ?: return null
        if (header.size != 6 || header[0] != "H" || header[1].toIntOrNull() != VERSION) {
            return null
        }

        return runCatching {
            val script = OcrScript.fromStored(header[2])
            val sourceWidth = header[3].toInt()
            val sourceHeight = header[4].toInt()
            val fullText = decodeText(header[5])
            val blocks = mutableListOf<OcrTextBlock>()
            val textLines = mutableListOf<OcrTextLine>()
            val words = mutableListOf<OcrWordBox>()

            records.drop(1).forEach { record ->
                if (record.isBlank()) return@forEach
                val fields = record.split(FIELD_SEPARATOR)
                when (fields.firstOrNull()) {
                    "B" -> if (fields.size == 10) {
                        blocks += OcrTextBlock(
                            blockIndex = fields[1].toInt(),
                            readingOrder = fields[2].toInt(),
                            left = fields[3].toInt(),
                            top = fields[4].toInt(),
                            right = fields[5].toInt(),
                            bottom = fields[6].toInt(),
                            cornerPoints = decodePoints(fields[7]),
                            languageTag = decodeText(fields[8]),
                            text = decodeText(fields[9])
                        )
                    }

                    "L" -> if (fields.size == 13) {
                        textLines += OcrTextLine(
                            blockIndex = fields[1].toInt(),
                            lineIndex = fields[2].toInt(),
                            readingOrder = fields[3].toInt(),
                            left = fields[4].toInt(),
                            top = fields[5].toInt(),
                            right = fields[6].toInt(),
                            bottom = fields[7].toInt(),
                            cornerPoints = decodePoints(fields[8]),
                            languageTag = decodeText(fields[9]),
                            confidence = fields[10].toFloat(),
                            angleDegrees = fields[11].toFloat(),
                            text = decodeText(fields[12])
                        )
                    }

                    "W" -> if (fields.size == 14) {
                        words += OcrWordBox(
                            blockIndex = fields[1].toInt(),
                            lineIndex = fields[2].toInt(),
                            wordIndex = fields[3].toInt(),
                            readingOrder = fields[4].toInt(),
                            left = fields[5].toInt(),
                            top = fields[6].toInt(),
                            right = fields[7].toInt(),
                            bottom = fields[8].toInt(),
                            cornerPoints = decodePoints(fields[9]),
                            languageTag = decodeText(fields[10]),
                            confidence = fields[11].toFloat(),
                            angleDegrees = fields[12].toFloat(),
                            text = decodeText(fields[13])
                        )
                    }
                }
            }

            OcrPageResult(
                text = fullText,
                blocks = blocks.sortedBy { it.readingOrder },
                lines = textLines.sortedBy { it.readingOrder },
                words = words.sortedBy { it.readingOrder },
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                script = script
            )
        }.getOrNull()
    }

    private fun encodeText(value: String): String =
        if (value.isEmpty()) {
            "~"
        } else {
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toByteArray(Charsets.UTF_8))
        }

    private fun decodeText(value: String): String {
        if (value == "~") return ""
        return Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)
    }

    private fun encodePoints(points: List<OcrPoint>): String =
        points.joinToString(";") { "${it.x},${it.y}" }

    private fun decodePoints(value: String): List<OcrPoint> =
        value.split(';').mapNotNull { token ->
            if (token.isBlank()) return@mapNotNull null
            val xy = token.split(',')
            if (xy.size != 2) return@mapNotNull null
            val x = xy[0].toIntOrNull() ?: return@mapNotNull null
            val y = xy[1].toIntOrNull() ?: return@mapNotNull null
            OcrPoint(x, y)
        }
}

object OcrFingerprint {
    const val MODEL_VERSION = "ocr-v2-1"

    fun create(
        file: File,
        cropQuad: String?,
        rotationDegrees: Int,
        script: OcrScript
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.update((cropQuad ?: "FULL").toByteArray())
        digest.update(rotationDegrees.toString().toByteArray())
        digest.update(script.name.toByteArray())
        digest.update(MODEL_VERSION.toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

object OcrSearchTerms {
    private val tokenRegex = Regex("\"([^\"]+)\"|(\\S+)")
    private val normalizer = Regex("[^\\p{L}\\p{N}_-]+")

    fun buildFtsQuery(raw: String): String? {
        val input = raw.trim()
        if (input.isBlank()) return null

        val parts = tokenRegex.findAll(input).mapNotNull { match ->
            val phrase = match.groups[1]?.value?.trim()
            val token = match.groups[2]?.value?.trim()
            when {
                !phrase.isNullOrBlank() -> quote(phrase)
                !token.isNullOrBlank() && token.endsWith("*") -> {
                    val base = token.dropLast(1).replace(normalizer, "")
                    base.takeIf { it.isNotBlank() }?.let { quote(it) + "*" }
                }
                !token.isNullOrBlank() -> {
                    val cleaned = token.replace(normalizer, "")
                    cleaned.takeIf { it.isNotBlank() }?.let(::quote)
                }
                else -> null
            }
        }.toList()

        return parts.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
    }

    fun matchingWords(result: OcrPageResult?, raw: String): List<OcrWordBox> {
        result ?: return emptyList()
        val requested = raw.trim()
        if (requested.isBlank()) return emptyList()

        val terms = tokenRegex.findAll(requested).flatMap { match ->
            val phrase = match.groups[1]?.value
            val token = match.groups[2]?.value
            val source = phrase ?: token ?: ""
            source.split(Regex("\\s+")).asSequence()
        }.map { it.trim() }.filter { it.isNotBlank() }.map { token ->
            val prefix = token.endsWith("*")
            val clean = token.removeSuffix("*")
                .lowercase()
                .replace(normalizer, "")
            clean to prefix
        }.filter { it.first.isNotBlank() }.toList()

        if (terms.isEmpty()) return emptyList()
        return result.words.filter { word ->
            val normalized = word.text.lowercase().replace(normalizer, "")
            terms.any { (term, prefix) ->
                if (prefix) normalized.startsWith(term) else normalized == term
            }
        }
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\"", "\"\"") + "\""
}

fun Float.formatConfidence(): String = "${(this * 100f).roundToInt()}%"
