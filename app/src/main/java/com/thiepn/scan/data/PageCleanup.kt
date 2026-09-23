package com.thiepn.scan.data

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class CleanupKind(val label: String) {
    MANUAL("Manual erase"),
    FINGER("Finger / hand"),
    PUNCH_HOLE("Punch hole"),
    STAIN("Stain / spot"),
    BORDER_SHADOW("Border shadow"),
    OBJECT("Object / mark")
}

data class CleanupStroke(
    val kind: CleanupKind,
    val points: List<NormalizedPoint>,
    val radius: Float,
    val confidence: Float = 1f
) {
    fun normalized(): CleanupStroke = copy(
        points = points.map(NormalizedPoint::clamped).take(MAX_POINTS),
        radius = radius.coerceIn(MIN_RADIUS, MAX_RADIUS),
        confidence = confidence.coerceIn(0f, 1f)
    )

    companion object {
        const val MIN_RADIUS = 0.003f
        const val MAX_RADIUS = 0.18f
        const val MAX_POINTS = 160
    }
}

data class PageCleanupRecipe(
    val version: Int = CURRENT_VERSION,
    val strokes: List<CleanupStroke> = emptyList()
) {
    fun normalized(): PageCleanupRecipe = copy(
        version = CURRENT_VERSION,
        strokes = strokes
            .map(CleanupStroke::normalized)
            .filter { it.points.isNotEmpty() }
            .take(MAX_STROKES)
    )

    fun isEmpty(): Boolean = strokes.isEmpty()

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_STROKES = 80
    }
}

data class CleanupSuggestion(
    val id: String,
    val label: String,
    val stroke: CleanupStroke
)

object PageCleanupRecipeCodec {
    private const val RECORD = "~"
    private const val FIELD = "|"
    private const val POINT = ";"

    fun encode(recipe: PageCleanupRecipe?): String? {
        val normalized = recipe?.normalized() ?: return null
        if (normalized.isEmpty()) return null
        return buildList {
            add("v${normalized.version}")
            normalized.strokes.forEach { stroke ->
                add(
                    listOf(
                        stroke.kind.name,
                        stroke.radius,
                        stroke.confidence,
                        stroke.points.joinToString(POINT) {
                            "${it.x},${it.y}"
                        }
                    ).joinToString(FIELD)
                )
            }
        }.joinToString(RECORD)
    }

    fun decode(encoded: String?): PageCleanupRecipe {
        if (encoded.isNullOrBlank()) return PageCleanupRecipe()
        val records = encoded.split(RECORD)
        val version = records.firstOrNull()
            ?.removePrefix("v")
            ?.toIntOrNull()
            ?: return PageCleanupRecipe()
        if (version != PageCleanupRecipe.CURRENT_VERSION) {
            return PageCleanupRecipe()
        }

        val strokes = records.drop(1).mapNotNull { record ->
            val parts = record.split(FIELD)
            if (parts.size != 4) return@mapNotNull null
            val kind = runCatching { CleanupKind.valueOf(parts[0]) }
                .getOrNull() ?: return@mapNotNull null
            val radius = parts[1].toFloatOrNull() ?: return@mapNotNull null
            val confidence = parts[2].toFloatOrNull() ?: return@mapNotNull null
            val points = parts[3].split(POINT).mapNotNull { token ->
                val xy = token.split(',')
                if (xy.size != 2) return@mapNotNull null
                val x = xy[0].toFloatOrNull() ?: return@mapNotNull null
                val y = xy[1].toFloatOrNull() ?: return@mapNotNull null
                NormalizedPoint(x, y)
            }
            CleanupStroke(kind, points, radius, confidence)
                .normalized()
                .takeIf { it.points.isNotEmpty() }
        }
        return PageCleanupRecipe(version, strokes).normalized()
    }
}

object PageCleanupRenderer {
    fun apply(
        source: Bitmap,
        recipe: PageCleanupRecipe
    ): Bitmap {
        val normalized = recipe.normalized()
        if (normalized.isEmpty()) return source

        val width = source.width
        val height = source.height
        if (width < 3 || height < 3) return source

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        normalized.strokes.forEach { stroke ->
            applyStroke(pixels, width, height, stroke)
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun applyStroke(
        pixels: IntArray,
        width: Int,
        height: Int,
        stroke: CleanupStroke
    ) {
        val mask = ByteArray(width * height)
        val radiusPx = (
            stroke.radius * min(width, height)
            ).roundToInt().coerceIn(2, max(2, min(width, height) / 5))

        var minX = width - 1
        var minY = height - 1
        var maxX = 0
        var maxY = 0

        fun stamp(point: NormalizedPoint) {
            val cx = (point.x * (width - 1)).roundToInt()
            val cy = (point.y * (height - 1)).roundToInt()
            minX = min(minX, cx - radiusPx)
            minY = min(minY, cy - radiusPx)
            maxX = max(maxX, cx + radiusPx)
            maxY = max(maxY, cy + radiusPx)
            markCircle(mask, width, height, cx, cy, radiusPx)
        }

        if (stroke.points.size == 1) {
            stamp(stroke.points.first())
        } else {
            stroke.points.zipWithNext().forEach { (a, b) ->
                val ax = a.x * width
                val ay = a.y * height
                val bx = b.x * width
                val by = b.y * height
                val distance = hypot(
                    (bx - ax).toDouble(),
                    (by - ay).toDouble()
                ).toFloat()
                val steps = ceil(
                    distance / max(1f, radiusPx * 0.45f)
                ).roundToInt().coerceAtLeast(1)
                for (step in 0..steps) {
                    val t = step.toFloat() / steps
                    stamp(
                        NormalizedPoint(
                            x = a.x + (b.x - a.x) * t,
                            y = a.y + (b.y - a.y) * t
                        )
                    )
                }
            }
        }

        minX = (minX - radiusPx * 2).coerceIn(0, width - 1)
        minY = (minY - radiusPx * 2).coerceIn(0, height - 1)
        maxX = (maxX + radiusPx * 2).coerceIn(0, width - 1)
        maxY = (maxY + radiusPx * 2).coerceIn(0, height - 1)
        if (minX >= maxX || minY >= maxY) return

        val leftColor = sampleSide(
            pixels, mask, width, height,
            minX, minY, minX + radiusPx, maxY
        )
        val rightColor = sampleSide(
            pixels, mask, width, height,
            maxX - radiusPx, minY, maxX, maxY
        )
        val topColor = sampleSide(
            pixels, mask, width, height,
            minX, minY, maxX, minY + radiusPx
        )
        val bottomColor = sampleSide(
            pixels, mask, width, height,
            minX, maxY - radiusPx, maxX, maxY
        )
        val fallback = sampleSide(
            pixels, mask, width, height,
            minX, minY, maxX, maxY
        )

        for (y in minY..maxY) {
            val ty = if (maxY == minY) 0.5f else
                (y - minY).toFloat() / (maxY - minY)
            for (x in minX..maxX) {
                val index = y * width + x
                if (mask[index].toInt() == 0) continue

                val tx = if (maxX == minX) 0.5f else
                    (x - minX).toFloat() / (maxX - minX)
                val horizontal = blendColor(
                    leftColor ?: fallback,
                    rightColor ?: fallback,
                    tx
                )
                val vertical = blendColor(
                    topColor ?: fallback,
                    bottomColor ?: fallback,
                    ty
                )
                val fill = blendColor(horizontal, vertical, 0.5f)

                var unmaskedNeighbors = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (
                            nx in 0 until width &&
                            ny in 0 until height &&
                            mask[ny * width + nx].toInt() == 0
                        ) {
                            unmaskedNeighbors++
                        }
                    }
                }
                val feather = if (unmaskedNeighbors > 0) 0.82f else 1f
                pixels[index] = blendColor(
                    pixels[index],
                    fill,
                    feather
                )
            }
        }
    }

    private fun markCircle(
        mask: ByteArray,
        width: Int,
        height: Int,
        cx: Int,
        cy: Int,
        radius: Int
    ) {
        val r2 = radius * radius
        val minY = (cy - radius).coerceAtLeast(0)
        val maxY = (cy + radius).coerceAtMost(height - 1)
        val minX = (cx - radius).coerceAtLeast(0)
        val maxX = (cx + radius).coerceAtMost(width - 1)
        for (y in minY..maxY) {
            val dy = y - cy
            for (x in minX..maxX) {
                val dx = x - cx
                if (dx * dx + dy * dy <= r2) {
                    mask[y * width + x] = 1
                }
            }
        }
    }

    private fun sampleSide(
        pixels: IntArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Int? {
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        val step = max(1, max(right - left, bottom - top) / 48)
        for (y in top.coerceAtLeast(0)..bottom.coerceAtMost(height - 1) step step) {
            for (x in left.coerceAtLeast(0)..right.coerceAtMost(width - 1) step step) {
                val index = y * width + x
                if (mask[index].toInt() != 0) continue
                val color = pixels[index]
                red += Color.red(color)
                green += Color.green(color)
                blue += Color.blue(color)
                count++
            }
        }
        if (count == 0L) return null
        return Color.rgb(
            (red / count).toInt().coerceIn(0, 255),
            (green / count).toInt().coerceIn(0, 255),
            (blue / count).toInt().coerceIn(0, 255)
        )
    }

    private fun blendColor(a: Int?, b: Int?, t: Float): Int {
        val first = a ?: b ?: Color.WHITE
        val second = b ?: a ?: Color.WHITE
        val clamped = t.coerceIn(0f, 1f)
        return Color.rgb(
            (
                Color.red(first) +
                    (Color.red(second) - Color.red(first)) * clamped
                ).roundToInt().coerceIn(0, 255),
            (
                Color.green(first) +
                    (Color.green(second) - Color.green(first)) * clamped
                ).roundToInt().coerceIn(0, 255),
            (
                Color.blue(first) +
                    (Color.blue(second) - Color.blue(first)) * clamped
                ).roundToInt().coerceIn(0, 255)
        )
    }
}

object CleanupSuggestionDetector {
    private const val MAX_EDGE = 560

    fun detect(
        file: File,
        cropQuad: CropQuad
    ): List<CleanupSuggestion> {
        val bitmap = runCatching {
            PageGeometryRenderer.renderUnrotatedForPdf(
                file = file,
                cropQuad = cropQuad,
                maxLongEdge = MAX_EDGE
            )
        }.getOrNull() ?: return emptyList()

        return try {
            detect(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun detect(bitmap: Bitmap): List<CleanupSuggestion> {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 64 || height < 64) return emptyList()

        val suggestions = mutableListOf<CleanupSuggestion>()
        suggestions += detectBorderShadows(bitmap)
        suggestions += detectComponents(bitmap, CleanupKind.PUNCH_HOLE)
        suggestions += detectComponents(bitmap, CleanupKind.FINGER)
        suggestions += detectComponents(bitmap, CleanupKind.STAIN)

        return suggestions
            .sortedByDescending { it.stroke.confidence }
            .filter { it.stroke.confidence >= 0.58f }
            .take(18)
    }

    private fun detectBorderShadows(bitmap: Bitmap): List<CleanupSuggestion> {
        val width = bitmap.width
        val height = bitmap.height
        val edge = max(2, (min(width, height) * 0.035f).roundToInt())
        val inner = max(edge + 2, (min(width, height) * 0.10f).roundToInt())

        data class EdgeProbe(
            val id: String,
            val label: String,
            val edgeLuma: Float,
            val innerLuma: Float,
            val points: List<NormalizedPoint>
        )

        val probes = listOf(
            EdgeProbe(
                "shadow-left", "Left border shadow",
                meanLuma(bitmap, 0, 0, edge, height),
                meanLuma(bitmap, inner, 0, min(width, inner + edge), height),
                listOf(NormalizedPoint(0.018f, 0.08f), NormalizedPoint(0.018f, 0.92f))
            ),
            EdgeProbe(
                "shadow-right", "Right border shadow",
                meanLuma(bitmap, max(0, width - edge), 0, width, height),
                meanLuma(bitmap, max(0, width - inner - edge), 0, max(1, width - inner), height),
                listOf(NormalizedPoint(0.982f, 0.08f), NormalizedPoint(0.982f, 0.92f))
            ),
            EdgeProbe(
                "shadow-top", "Top border shadow",
                meanLuma(bitmap, 0, 0, width, edge),
                meanLuma(bitmap, 0, inner, width, min(height, inner + edge)),
                listOf(NormalizedPoint(0.08f, 0.018f), NormalizedPoint(0.92f, 0.018f))
            ),
            EdgeProbe(
                "shadow-bottom", "Bottom border shadow",
                meanLuma(bitmap, 0, max(0, height - edge), width, height),
                meanLuma(bitmap, 0, max(0, height - inner - edge), width, max(1, height - inner)),
                listOf(NormalizedPoint(0.08f, 0.982f), NormalizedPoint(0.92f, 0.982f))
            )
        )

        return probes.mapNotNull { probe ->
            val delta = probe.innerLuma - probe.edgeLuma
            if (delta < 0.11f || probe.innerLuma < 0.55f) return@mapNotNull null
            CleanupSuggestion(
                id = probe.id,
                label = probe.label,
                stroke = CleanupStroke(
                    kind = CleanupKind.BORDER_SHADOW,
                    points = probe.points,
                    radius = 0.035f,
                    confidence = (0.58f + delta * 1.8f).coerceIn(0f, 0.96f)
                )
            )
        }
    }

    private fun detectComponents(
        bitmap: Bitmap,
        kind: CleanupKind
    ): List<CleanupSuggestion> {
        val width = bitmap.width
        val height = bitmap.height
        val total = width * height
        val candidate = BooleanArray(total)
        val visited = BooleanArray(total)
        val paperLuma = estimatePaperLuma(bitmap)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = bitmap.getPixel(x, y)
                val luma = luma(color)
                val saturation = saturation(color)
                val borderDistance = min(
                    min(x, width - 1 - x).toFloat() / width,
                    min(y, height - 1 - y).toFloat() / height
                )
                candidate[y * width + x] = when (kind) {
                    CleanupKind.PUNCH_HOLE ->
                        borderDistance < 0.10f &&
                            luma < min(0.36f, paperLuma - 0.32f)
                    CleanupKind.FINGER ->
                        borderDistance < 0.16f &&
                            isSkinLike(color)
                    CleanupKind.STAIN ->
                        paperLuma > 0.67f &&
                            luma in 0.34f..(paperLuma - 0.10f) &&
                            saturation < 0.34f
                    else -> false
                }
            }
        }

        val queue = IntArray(total)
        val output = mutableListOf<CleanupSuggestion>()
        val minArea = max(5, total / 9000)
        val maxArea = when (kind) {
            CleanupKind.FINGER -> total / 4
            CleanupKind.PUNCH_HOLE -> total / 70
            CleanupKind.STAIN -> total / 55
            else -> total / 20
        }

        for (start in 0 until total) {
            if (!candidate[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var count = 0
            var minX = width
            var minY = height
            var maxX = 0
            var maxY = 0
            var sumX = 0L
            var sumY = 0L

            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                count++
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
                sumX += x
                sumY += y

                val neighbors = intArrayOf(
                    index - 1, index + 1,
                    index - width, index + width
                )
                neighbors.forEach { next ->
                    if (next !in 0 until total) return@forEach
                    val nx = next % width
                    val ny = next / width
                    if (abs(nx - x) + abs(ny - y) != 1) return@forEach
                    if (candidate[next] && !visited[next]) {
                        visited[next] = true
                        queue[tail++] = next
                    }
                }
            }

            if (count !in minArea..maxArea) continue
            val boxWidth = maxX - minX + 1
            val boxHeight = maxY - minY + 1
            val compactness = count.toFloat() /
                max(1, boxWidth * boxHeight).toFloat()
            if (kind == CleanupKind.PUNCH_HOLE && compactness < 0.42f) continue

            val centerX = sumX.toFloat() / count / width
            val centerY = sumY.toFloat() / count / height
            val majorX = boxWidth >= boxHeight
            val span = max(boxWidth, boxHeight).toFloat()
            val minor = min(boxWidth, boxHeight).toFloat()
            val radius = (
                minor * 0.62f / min(width, height)
                ).coerceIn(0.006f, 0.12f)
            val half = span * 0.30f
            val points = if (span / max(1f, minor) > 1.5f) {
                if (majorX) {
                    listOf(
                        NormalizedPoint(
                            (sumX.toFloat() / count - half) / width,
                            centerY
                        ),
                        NormalizedPoint(
                            (sumX.toFloat() / count + half) / width,
                            centerY
                        )
                    )
                } else {
                    listOf(
                        NormalizedPoint(
                            centerX,
                            (sumY.toFloat() / count - half) / height
                        ),
                        NormalizedPoint(
                            centerX,
                            (sumY.toFloat() / count + half) / height
                        )
                    )
                }
            } else {
                listOf(NormalizedPoint(centerX, centerY))
            }

            val areaScore = when (kind) {
                CleanupKind.PUNCH_HOLE ->
                    (1f - abs(count - total * 0.0018f) / (total * 0.004f))
                CleanupKind.FINGER ->
                    (count.toFloat() / (total * 0.025f)).coerceAtMost(1f)
                CleanupKind.STAIN ->
                    (count.toFloat() / (total * 0.006f)).coerceAtMost(1f)
                else -> 0.5f
            }.coerceIn(0f, 1f)
            val confidence = when (kind) {
                CleanupKind.PUNCH_HOLE -> 0.64f + areaScore * 0.28f
                CleanupKind.FINGER -> 0.60f + areaScore * 0.24f
                CleanupKind.STAIN -> 0.58f + areaScore * 0.20f
                else -> 0.6f
            }.coerceIn(0f, 0.94f)

            output += CleanupSuggestion(
                id = "${kind.name.lowercase()}-$minX-$minY-$maxX-$maxY",
                label = when (kind) {
                    CleanupKind.PUNCH_HOLE -> "Possible punch hole"
                    CleanupKind.FINGER -> "Possible finger / hand"
                    CleanupKind.STAIN -> "Possible stain / spot"
                    else -> kind.label
                },
                stroke = CleanupStroke(
                    kind = kind,
                    points = points,
                    radius = radius,
                    confidence = confidence
                )
            )
        }

        return output
    }

    private fun estimatePaperLuma(bitmap: Bitmap): Float {
        val samples = mutableListOf<Float>()
        val step = max(1, max(bitmap.width, bitmap.height) / 48)
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val value = luma(bitmap.getPixel(x, y))
                if (value > 0.45f) samples += value
            }
        }
        if (samples.isEmpty()) return 0.75f
        samples.sort()
        return samples[(samples.size * 0.72f).roundToInt()
            .coerceIn(0, samples.lastIndex)]
    }

    private fun meanLuma(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Float {
        var total = 0f
        var count = 0
        val step = max(1, max(right - left, bottom - top) / 40)
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(bitmap.height) step step) {
            for (x in left.coerceAtLeast(0) until right.coerceAtMost(bitmap.width) step step) {
                total += luma(bitmap.getPixel(x, y))
                count++
            }
        }
        return if (count == 0) 0.5f else total / count
    }

    private fun luma(color: Int): Float =
        (
            Color.red(color) * 0.299f +
                Color.green(color) * 0.587f +
                Color.blue(color) * 0.114f
            ) / 255f

    private fun saturation(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        return if (hi <= 0.001f) 0f else (hi - lo) / hi
    }

    private fun isSkinLike(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val maxValue = max(r, max(g, b))
        val minValue = min(r, min(g, b))
        return r > 92 &&
            g > 45 &&
            b > 30 &&
            r > g &&
            g > b * 0.78f &&
            maxValue - minValue > 18 &&
            abs(r - g) > 10
    }
}
