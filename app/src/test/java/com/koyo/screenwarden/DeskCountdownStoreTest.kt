package com.koyo.screenwarden

import org.junit.Assert.assertEquals
import org.junit.Test

class DeskCountdownStoreTest {
    @Test
    fun formatRoundsVisibleTimeUpToNextSecond() {
        assertEquals("00:01", DeskCountdownStore.format(1L))
        assertEquals("25:00", DeskCountdownStore.format(25L * 60_000L))
    }

    @Test
    fun formatKeepsHoursWhenCountdownIsLong() {
        assertEquals("01:02:03", DeskCountdownStore.format(3_723_000L))
    }
}
