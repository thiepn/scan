package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecializedFieldExtractorTest {
    @Test
    fun receiptExtractsTotalDateAndTax() {
        val fields = SpecializedFieldExtractor.extract(
            mode = ScanMode.RECEIPT,
            title = "Cafe",
            ocrText = """
                Corner Cafe
                23.09.2026
                Subtotal EUR 12.00
                VAT EUR 2.28
                Total EUR 14.28
            """.trimIndent()
        ).associateBy { it.key }

        assertEquals("EUR 14.28", fields["total"]?.value)
        assertEquals("23.09.2026", fields["date"]?.value)
        assertEquals("EUR 2.28", fields["tax"]?.value)
    }

    @Test
    fun businessCardExtractsContactChannels() {
        val fields = SpecializedFieldExtractor.extract(
            mode = ScanMode.BUSINESS_CARD,
            title = "Card",
            ocrText = """
                Jane Example
                Product Director
                jane@example.com
                +49 221 1234567
                www.example.com
            """.trimIndent()
        ).associateBy { it.key }

        assertEquals("jane@example.com", fields["email"]?.value)
        assertTrue(fields["phone"]?.value?.contains("49 221") == true)
        assertEquals("www.example.com", fields["website"]?.value)
    }

    @Test
    fun idCardExtractsLabeledIdentityDates() {
        val fields = SpecializedFieldExtractor.extract(
            mode = ScanMode.ID_CARD,
            title = "ID Card",
            ocrText = """
                Nationality DEU
                Date of birth 01.02.2000
                Valid until 01.02.2030
                Document number ABC123456
            """.trimIndent()
        ).associateBy { it.key }

        assertEquals("DEU", fields["nationality"]?.value)
        assertEquals("01.02.2000", fields["date_of_birth"]?.value)
        assertEquals("01.02.2030", fields["expiry"]?.value)
    }

    @Test
    fun generalDocumentDoesNotInventStructuredFields() {
        val fields = SpecializedFieldExtractor.extract(
            mode = ScanMode.DOCUMENT,
            title = "Document",
            ocrText = "A normal paragraph with a date 23.09.2026."
        )

        assertTrue(fields.isEmpty())
    }
}
