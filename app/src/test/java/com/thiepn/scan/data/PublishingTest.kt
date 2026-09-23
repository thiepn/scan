package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishingTest {
    @Test
    fun publishingSettingsRoundTrip() {
        val settings = PublishingSettings(
            headerCenter = "{title}",
            footerLeft = "{label}",
            pageNumberPosition = PageNumberPosition.FOOTER_RIGHT,
            pageNumberStyle = PageNumberStyle.ROMAN_LOWER,
            pageNumberFormat = PageNumberFormat.PAGE_NUMBER_OF_TOTAL,
            pageNumberStart = 3,
            watermarkText = "DRAFT",
            metadataAuthor = "Author"
        )
        val decoded = PublishingSettingsCodec.decode(
            PublishingSettingsCodec.encode(settings)
        )
        assertEquals(settings.normalized(), decoded)
    }

    @Test
    fun assemblyMetadataRoundTrip() {
        val metadata = PageAssemblyMetadata(
            kind = AssemblyPageKind.DIVIDER,
            label = "Appendix",
            bookmarkTitle = "Appendix",
            bookmarkLevel = 1,
            generatedTitle = "Appendix A"
        )
        assertEquals(
            metadata.normalized(),
            PageAssemblyMetadataCodec.decode(
                PageAssemblyMetadataCodec.encode(metadata)
            )
        )
    }

    @Test
    fun placeholdersAndRomanNumbersResolve() {
        val settings = PublishingSettings(
            pageNumberPosition = PageNumberPosition.FOOTER_CENTER,
            pageNumberStyle = PageNumberStyle.ROMAN_UPPER,
            pageNumberStart = 4
        )
        val metadata = PageAssemblyMetadata(label = "Methods")
        assertEquals(
            "Doc · IV · Methods",
            PublishingText.resolve(
                "{title} · {page} · {label}",
                "Doc",
                0,
                8,
                metadata,
                settings
            )
        )
        assertEquals("IV", PublishingText.pageNumber(0, 8, settings))
    }

    @Test
    fun nativeMetadataIsCompact() {
        assertTrue(PageAssemblyMetadata().isNativeSource())
        assertEquals(null, PageAssemblyMetadataCodec.encode(PageAssemblyMetadata()))
        assertFalse(
            PageAssemblyMetadata(kind = AssemblyPageKind.COPIED).isNativeSource()
        )
    }
}
