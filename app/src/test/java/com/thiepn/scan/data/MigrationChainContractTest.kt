package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationChainContractTest {
    @Test
    fun registeredMigrationsAreContiguousFromV1ToV22() {
        val expected = (1 until 22).map { version ->
            version to version + 1
        }

        assertEquals(
            expected,
            ScanDatabase.migrationEdgesForTest()
        )
    }
}
