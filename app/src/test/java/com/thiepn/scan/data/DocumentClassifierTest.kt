package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentClassifierTest {
    @Test
    fun stronglyIdentifiesInvoices() {
        val suggestion = DocumentClassifier.suggest(
            title = "September billing",
            ocrText = "INVOICE\nInvoice number 1042\nAmount due EUR 98.00\nPayment terms 14 days"
        )

        assertEquals(DocumentType.INVOICE, suggestion?.type)
    }

    @Test
    fun stronglyIdentifiesReceipts() {
        val suggestion = DocumentClassifier.suggest(
            title = "Store scan",
            ocrText = "RECEIPT\nSubtotal 18.50\nVAT 3.50\nCash 22.00\nChange 0.00"
        )

        assertEquals(DocumentType.RECEIPT, suggestion?.type)
    }

    @Test
    fun ambiguousTextDoesNotForceAType() {
        val suggestion = DocumentClassifier.suggest(
            title = "Scan",
            ocrText = "Meeting tomorrow at ten. Bring the document."
        )

        assertNull(suggestion)
    }

    @Test
    fun certificateSignalsBeatGenericCompletionWords() {
        val suggestion = DocumentClassifier.suggest(
            title = "Award",
            ocrText = "CERTIFICATE\nThis certifies that Alex completed the program\nAwarded to Alex"
        )

        assertEquals(DocumentType.CERTIFICATE, suggestion?.type)
    }
}
