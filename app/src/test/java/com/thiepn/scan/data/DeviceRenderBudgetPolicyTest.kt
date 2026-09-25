package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRenderBudgetPolicyTest {
    @Test
    fun lowRamFlagForcesLowTier() {
        val budget = DeviceRenderBudgetPolicy.forMemory(
            memoryClassMb = 768,
            lowRamDevice = true
        )

        assertEquals(DeviceMemoryTier.LOW, budget.memoryTier)
        assertEquals(1800, budget.pdfImportLongEdge)
        assertEquals(3000, budget.editedExportLongEdge)
    }

    @Test
    fun memoryClassSelectsBalancedAndHighTiers() {
        assertEquals(
            DeviceMemoryTier.BALANCED,
            DeviceRenderBudgetPolicy.forMemory(384, false).memoryTier
        )
        assertEquals(
            DeviceMemoryTier.HIGH,
            DeviceRenderBudgetPolicy.forMemory(512, false).memoryTier
        )
    }

    @Test
    fun largeDocumentsUseSmallerPreviewBudget() {
        val budget = DeviceRenderBudgetPolicy.forMemory(384, false)

        assertEquals(
            budget.pagePreviewLongEdge,
            budget.previewLongEdge(1600, pageCount = 20)
        )
        assertEquals(
            budget.largeDocumentPreviewLongEdge,
            budget.previewLongEdge(1600, pageCount = 500)
        )
    }

    @Test
    fun editedExportNeverExceedsDeviceCap() {
        val budget = DeviceRenderBudgetPolicy.forMemory(192, false)

        assertEquals(3000, budget.exportLongEdge(null))
        assertEquals(2200, budget.exportLongEdge(2200))
        assertEquals(3000, budget.exportLongEdge(5000))
        assertTrue(budget.enhancementLongEdge(1400) <= 900)
    }
}
