package com.example.nothingwidgets.core
import org.junit.Assert.*
import org.junit.Test
class LimitTest {
    @Test fun strictThreshold() {
        assertFalse(limitExceeded(10_800_000L, 180))
        assertTrue(limitExceeded(10_801_000L, 180))
        assertFalse(limitExceeded(null, 180))
    }
}
