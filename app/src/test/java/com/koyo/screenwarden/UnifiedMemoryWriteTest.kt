package com.koyo.screenwarden

import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedMemoryWriteTest {
    @Test
    fun stableEventIdIsDeterministicAndCompanionScoped() {
        assertEquals(
            UnifiedMemoryWrite.stableEventId("koyo", "call-1"),
            UnifiedMemoryWrite.stableEventId("koyo", "call-1")
        )
        assertEquals(
            "memory_tool_koyo_call-1",
            UnifiedMemoryWrite.stableEventId("koyo", "call-1")
        )
    }

    @Test
    fun stableEventIdRemainsBounded() {
        val id = UnifiedMemoryWrite.stableEventId("koyo", "x".repeat(300))
        assertEquals(120, id.length)
    }
}
