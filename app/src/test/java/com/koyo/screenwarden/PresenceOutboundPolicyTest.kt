package com.koyo.screenwarden

import com.koyo.screenwarden.presence.OutboundAuthorization
import com.koyo.screenwarden.presence.OutboundPolicyInput
import com.koyo.screenwarden.presence.PresenceAvailability
import com.koyo.screenwarden.presence.PresenceCapability
import com.koyo.screenwarden.presence.PresenceOutboundPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresenceOutboundPolicyTest {
    private fun valid() = OutboundPolicyInput(
        adapterRegistered = true,
        availability = PresenceAvailability.BRIDGE_READY,
        capabilities = setOf(PresenceCapability.SEND_TEXT),
        healthy = true,
        hasConversationKey = true,
        textLength = 12,
        attachmentCount = 0,
        authorization = OutboundAuthorization.DIRECT_REPLY,
        sourceMatches = true,
        elapsedSinceLastSendMs = Long.MAX_VALUE
    )

    @Test fun allowsBoundDirectReply() = assertNull(PresenceOutboundPolicy.evaluate(valid()))

    @Test fun blocksUnboundDirectReply() = assertEquals(
        "source_event_not_authorized",
        PresenceOutboundPolicy.evaluate(valid().copy(sourceMatches = false))
    )

    @Test fun blocksPlannedOrUnhealthyAdapter() {
        assertEquals("adapter_not_ready", PresenceOutboundPolicy.evaluate(valid().copy(availability = PresenceAvailability.PLANNED)))
        assertEquals("adapter_unhealthy", PresenceOutboundPolicy.evaluate(valid().copy(healthy = false)))
    }

    @Test fun blocksUnsupportedMediaAndRapidDuplicate() {
        assertEquals("send_media_not_supported", PresenceOutboundPolicy.evaluate(valid().copy(attachmentCount = 1)))
        assertEquals("rate_limited", PresenceOutboundPolicy.evaluate(valid().copy(elapsedSinceLastSendMs = 20)))
    }
}
