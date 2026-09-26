package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseStressContractTest {
    @Test
    fun thousandPageDocumentsStayOnBoundedPreviewBudgets() {
        val tiers = listOf(
            DeviceRenderBudgetPolicy.forMemory(
                memoryClassMb = 192,
                lowRamDevice = true
            ),
            DeviceRenderBudgetPolicy.forMemory(
                memoryClassMb = 384,
                lowRamDevice = false
            ),
            DeviceRenderBudgetPolicy.forMemory(
                memoryClassMb = 768,
                lowRamDevice = false
            )
        )

        tiers.forEach { budget ->
            repeat(1_000) {
                assertEquals(
                    budget.largeDocumentPreviewLongEdge,
                    budget.previewLongEdge(
                        requested = Int.MAX_VALUE,
                        pageCount = 1_000
                    )
                )
            }
        }
    }

    @Test
    fun storagePreflightsSaturateInsteadOfOverflowing() {
        assertEquals(
            Long.MAX_VALUE,
            StorageBudgetPolicy.requiredFreeBytes(Long.MAX_VALUE)
        )
        assertEquals(
            Long.MAX_VALUE,
            StorageBudgetPolicy.pdfImportWorkingBytes(Long.MAX_VALUE)
        )
        assertTrue(
            StorageBudgetPolicy.requiredFreeBytes(0L) >=
                StorageBudgetPolicy.RESERVED_FREE_BYTES
        )
    }
}
