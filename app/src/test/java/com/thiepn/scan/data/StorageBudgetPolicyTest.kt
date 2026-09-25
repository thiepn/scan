package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageBudgetPolicyTest {
    @Test
    fun keepsEmergencyStorageReserve() {
        assertEquals(
            64L * 1024L * 1024L,
            StorageBudgetPolicy.requiredFreeBytes(0L)
        )
    }

    @Test
    fun pdfImportUsesThreeTimesSourceSize() {
        assertEquals(
            300L,
            StorageBudgetPolicy.pdfImportWorkingBytes(
                100L
            )
        )
    }

    @Test
    fun pdfExportUsesTwoTimesSourceSize() {
        assertEquals(
            200L,
            StorageBudgetPolicy.pdfExportWorkingBytes(
                100L
            )
        )
    }

    @Test
    fun spaceCheckIncludesReserve() {
        val work = 10L * 1024L * 1024L
        val required =
            StorageBudgetPolicy.requiredFreeBytes(work)

        assertTrue(
            StorageBudgetPolicy.hasEnoughSpace(
                required,
                work
            )
        )
        assertFalse(
            StorageBudgetPolicy.hasEnoughSpace(
                required - 1L,
                work
            )
        )
    }

    @Test
    fun arithmeticSaturatesInsteadOfOverflowing() {
        assertEquals(
            Long.MAX_VALUE,
            StorageBudgetPolicy.pdfImportWorkingBytes(
                Long.MAX_VALUE
            )
        )
        assertEquals(
            Long.MAX_VALUE,
            StorageBudgetPolicy.requiredFreeBytes(
                Long.MAX_VALUE
            )
        )
    }
}
