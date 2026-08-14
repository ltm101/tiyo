package com.koyo.screenwarden.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecisionResponseParserTest {
    @Test
    fun parsesSendJsonInsideFence() {
        val result = DecisionResponseParser.parse(
            "```json\n{\"action\":\"send\",\"delay_minutes\":0,\"message_context\":\"自然问候\"}\n```"
        )
        assertEquals(DecisionAction.SEND, result?.action)
        assertEquals("自然问候", result?.messageContext)
    }

    @Test
    fun clampsDelayToAllowedWindow() {
        val result = DecisionResponseParser.parse("{\"action\":\"delay\",\"delay_minutes\":999}")
        assertEquals(DecisionAction.DELAY, result?.action)
        assertEquals(360, result?.delayMinutes)
    }

    @Test
    fun rejectsUnknownAction() {
        assertNull(DecisionResponseParser.parse("{\"action\":\"maybe\"}"))
    }
}
