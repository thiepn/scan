package com.thiepn.scan.data

import java.util.Base64
import kotlin.math.roundToInt

enum class AssemblyPageKind {
    SOURCE,
    INSERTED_IMAGE,
    INSERTED_PDF,
    BLANK,
    DIVIDER,
    COPIED,
    TRANSFERRED,
    REPLACED
}

data class PageAssemblyMetadata(
    val version: Int = 1,
    val kind: AssemblyPageKind = AssemblyPageKind.SOURCE,
    val label: String = "",
    val bookmarkTitle: String = "",
    val bookmarkLevel: Int = 0,
    val generatedTitle: String = "",
    val generatedSubtitle: String = ""
) {
    fun normalized() = copy(
        version = 1,
        label = label.trim().take(120),
        bookmarkTitle = bookmarkTitle.trim().take(180),
        bookmarkLevel = bookmarkLevel.coerceIn(0, 3),
        generatedTitle = generatedTitle.trim().take(240),
        generatedSubtitle = generatedSubtitle.trim().take(500)
    )

    fun isNativeSource(): Boolean =
        kind == AssemblyPageKind.SOURCE &&
            label.isBlank() &&
            bookmarkTitle.isBlank() &&
            generatedTitle.isBlank() &&
            generatedSubtitle.isBlank()
}

object PageAssemblyMetadataCodec {
    fun encode(value: PageAssemblyMetadata?): String? {
        val normalized = value?.normalized() ?: return null
        if (normalized.isNativeSource()) return null
        return listOf(
            "1",
            normalized.kind.name,
            enc(normalized.label),
            enc(normalized.bookmarkTitle),
            normalized.bookmarkLevel.toString(),
            enc(normalized.generatedTitle),
            enc(normalized.generatedSubtitle)
        ).joinToString("\t")
    }

    fun decode(encoded: String?): PageAssemblyMetadata {
        if (encoded.isNullOrBlank()) return PageAssemblyMetadata()
        val p = encoded.split("\t")
        if (p.size != 7 || p[0] != "1") return PageAssemblyMetadata()
        return runCatching {
            PageAssemblyMetadata(
                kind = AssemblyPageKind.valueOf(p[1]),
                label = dec(p[2]),
                bookmarkTitle = dec(p[3]),
                bookmarkLevel = p[4].toInt(),
                generatedTitle = dec(p[5]),
                generatedSubtitle = dec(p[6])
            ).normalized()
        }.getOrDefault(PageAssemblyMetadata())
    }

    private fun enc(value: String): String =
        if (value.isEmpty()) "~" else Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun dec(value: String): String =
        if (value == "~") "" else runCatching {
            Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)
        }.getOrDefault("")
}

enum class PageNumberStyle {
    ARABIC,
    ROMAN_LOWER,
    ROMAN_UPPER
}

enum class PageNumberFormat {
    NUMBER_ONLY,
    PAGE_NUMBER,
    PAGE_NUMBER_OF_TOTAL
}

enum class PageNumberPosition {
    NONE,
    HEADER_LEFT,
    HEADER_CENTER,
    HEADER_RIGHT,
    FOOTER_LEFT,
    FOOTER_CENTER,
    FOOTER_RIGHT
}

data class PublishingSettings(
    val version: Int = 1,
    val headerLeft: String = "",
    val headerCenter: String = "",
    val headerRight: String = "",
    val footerLeft: String = "",
    val footerCenter: String = "",
    val footerRight: String = "",
    val pageNumberPosition: PageNumberPosition = PageNumberPosition.FOOTER_CENTER,
    val pageNumberStyle: PageNumberStyle = PageNumberStyle.ARABIC,
    val pageNumberFormat: PageNumberFormat = PageNumberFormat.NUMBER_ONLY,
    val pageNumberStart: Int = 1,
    val watermarkText: String = "",
    val watermarkOpacity: Float = 0.14f,
    val watermarkAngle: Float = -35f,
    val metadataTitle: String = "",
    val metadataAuthor: String = "",
    val metadataSubject: String = "",
    val metadataKeywords: String = ""
) {
    fun normalized() = copy(
        version = 1,
        headerLeft = headerLeft.take(300),
        headerCenter = headerCenter.take(300),
        headerRight = headerRight.take(300),
        footerLeft = footerLeft.take(300),
        footerCenter = footerCenter.take(300),
        footerRight = footerRight.take(300),
        pageNumberStart = pageNumberStart.coerceIn(-9999, 999999),
        watermarkText = watermarkText.take(300),
        watermarkOpacity = watermarkOpacity.coerceIn(0.04f, 0.75f),
        watermarkAngle = watermarkAngle.coerceIn(-75f, 75f),
        metadataTitle = metadataTitle.take(300),
        metadataAuthor = metadataAuthor.take(300),
        metadataSubject = metadataSubject.take(500),
        metadataKeywords = metadataKeywords.take(500)
    )

    fun isEmpty(): Boolean {
        val n = normalized()
        return n.headerLeft.isBlank() &&
            n.headerCenter.isBlank() &&
            n.headerRight.isBlank() &&
            n.footerLeft.isBlank() &&
            n.footerCenter.isBlank() &&
            n.footerRight.isBlank() &&
            n.pageNumberPosition == PageNumberPosition.NONE &&
            n.watermarkText.isBlank() &&
            n.metadataTitle.isBlank() &&
            n.metadataAuthor.isBlank() &&
            n.metadataSubject.isBlank() &&
            n.metadataKeywords.isBlank()
    }

    fun hasVisualDecorations(): Boolean {
        val n = normalized()
        return n.headerLeft.isNotBlank() ||
            n.headerCenter.isNotBlank() ||
            n.headerRight.isNotBlank() ||
            n.footerLeft.isNotBlank() ||
            n.footerCenter.isNotBlank() ||
            n.footerRight.isNotBlank() ||
            n.pageNumberPosition != PageNumberPosition.NONE ||
            n.watermarkText.isNotBlank()
    }
}

object PublishingSettingsCodec {
    fun encode(value: PublishingSettings?): String? {
        val n = value?.normalized() ?: return null
        if (n.isEmpty()) return null
        return listOf(
            "1",
            enc(n.headerLeft),
            enc(n.headerCenter),
            enc(n.headerRight),
            enc(n.footerLeft),
            enc(n.footerCenter),
            enc(n.footerRight),
            n.pageNumberPosition.name,
            n.pageNumberStyle.name,
            n.pageNumberFormat.name,
            n.pageNumberStart.toString(),
            enc(n.watermarkText),
            n.watermarkOpacity.toString(),
            n.watermarkAngle.toString(),
            enc(n.metadataTitle),
            enc(n.metadataAuthor),
            enc(n.metadataSubject),
            enc(n.metadataKeywords)
        ).joinToString("\t")
    }

    fun decode(encoded: String?): PublishingSettings {
        if (encoded.isNullOrBlank()) return PublishingSettings(
            pageNumberPosition = PageNumberPosition.NONE
        )
        val p = encoded.split("\t")
        if (p.size != 18 || p[0] != "1") {
            return PublishingSettings(pageNumberPosition = PageNumberPosition.NONE)
        }
        return runCatching {
            PublishingSettings(
                headerLeft = dec(p[1]),
                headerCenter = dec(p[2]),
                headerRight = dec(p[3]),
                footerLeft = dec(p[4]),
                footerCenter = dec(p[5]),
                footerRight = dec(p[6]),
                pageNumberPosition = PageNumberPosition.valueOf(p[7]),
                pageNumberStyle = PageNumberStyle.valueOf(p[8]),
                pageNumberFormat = PageNumberFormat.valueOf(p[9]),
                pageNumberStart = p[10].toInt(),
                watermarkText = dec(p[11]),
                watermarkOpacity = p[12].toFloat(),
                watermarkAngle = p[13].toFloat(),
                metadataTitle = dec(p[14]),
                metadataAuthor = dec(p[15]),
                metadataSubject = dec(p[16]),
                metadataKeywords = dec(p[17])
            ).normalized()
        }.getOrDefault(
            PublishingSettings(pageNumberPosition = PageNumberPosition.NONE)
        )
    }

    private fun enc(value: String): String =
        if (value.isEmpty()) "~" else Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun dec(value: String): String =
        if (value == "~") "" else runCatching {
            Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)
        }.getOrDefault("")
}

object PublishingText {
    fun resolve(
        template: String,
        documentTitle: String,
        pageIndex: Int,
        pageCount: Int,
        metadata: PageAssemblyMetadata,
        settings: PublishingSettings
    ): String {
        if (template.isBlank()) return ""
        val absolute = settings.pageNumberStart + pageIndex
        val number = formatNumber(absolute, settings.pageNumberStyle)
        return template
            .replace("{title}", documentTitle)
            .replace("{page}", number)
            .replace("{pages}", pageCount.toString())
            .replace("{label}", metadata.label)
    }

    fun pageNumber(
        pageIndex: Int,
        pageCount: Int,
        settings: PublishingSettings
    ): String {
        val number = formatNumber(
            settings.pageNumberStart + pageIndex,
            settings.pageNumberStyle
        )
        return when (settings.pageNumberFormat) {
            PageNumberFormat.NUMBER_ONLY -> number
            PageNumberFormat.PAGE_NUMBER -> "Page $number"
            PageNumberFormat.PAGE_NUMBER_OF_TOTAL -> "Page $number of $pageCount"
        }
    }

    fun formatNumber(value: Int, style: PageNumberStyle): String {
        if (style == PageNumberStyle.ARABIC || value <= 0 || value > 3999) {
            return value.toString()
        }
        val roman = toRoman(value)
        return if (style == PageNumberStyle.ROMAN_LOWER) roman.lowercase() else roman
    }

    private fun toRoman(value: Int): String {
        var remaining = value
        val pairs = listOf(
            1000 to "M", 900 to "CM", 500 to "D", 400 to "CD",
            100 to "C", 90 to "XC", 50 to "L", 40 to "XL",
            10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I"
        )
        return buildString {
            pairs.forEach { (number, token) ->
                while (remaining >= number) {
                    append(token)
                    remaining -= number
                }
            }
        }
    }
}
