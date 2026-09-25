package com.thiepn.scan

import com.thiepn.scan.data.ScanMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingScanActionCodecTest {
    @Test
    fun roundTripsEveryPendingScannerAction() {
        val actions = listOf(
            PendingScanAction.NewDocument(
                mode = ScanMode.RECEIPT,
                rapid = true
            ),
            PendingScanAction.RapidExistingStart(
                documentId = "doc-a",
                mode = ScanMode.BOOK
            ),
            PendingScanAction.RapidContinue(
                documentId = "doc-b",
                mode = ScanMode.DOCUMENT,
                sessionId = "session-1"
            ),
            PendingScanAction.IdBack(
                documentId = "doc-id-card"
            ),
            PendingScanAction.Append(
                documentId = "doc-c",
                mode = ScanMode.NOTES
            ),
            PendingScanAction.Insert(
                documentId = "doc-d",
                index = 42,
                mode = ScanMode.FORM
            ),
            PendingScanAction.Retake(
                documentId = "doc-e",
                pageId = "page-9",
                mode = ScanMode.WHITEBOARD
            )
        )

        actions.forEach { action ->
            assertEquals(
                action,
                PendingScanActionCodec.decode(
                    PendingScanActionCodec.encode(action)
                )
            )
        }
    }

    @Test
    fun malformedOrUnknownStateFailsClosed() {
        assertEquals(
            null,
            PendingScanActionCodec.decode("not-valid")
        )
    }
}
