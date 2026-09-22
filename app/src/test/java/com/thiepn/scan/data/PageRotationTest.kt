package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PageRotationTest {
    @Test
    fun normalizesPositiveAndNegativeAngles() {
        assertEquals(0, PageRotation.normalize(360))
        assertEquals(270, PageRotation.normalize(-90))
        assertEquals(90, PageRotation.normalize(450))
    }

    @Test
    fun fourClockwiseTurnsReturnToZero() {
        var rotation = 0
        repeat(4) { rotation = PageRotation.clockwise(rotation) }
        assertEquals(0, rotation)
    }
}
