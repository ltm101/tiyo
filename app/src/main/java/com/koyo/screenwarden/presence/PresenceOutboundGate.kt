package com.koyo.screenwarden.presence

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** Why an external reply is allowed to leave the device. */
enum class OutboundAuthorization {
    /** A bounded reply to an inbound event that the user explicitly sent to the companion. */
    DIRECT_REPLY,
    /** The user reviewed and approved this exact outbound message. */
    USER_APPROVED
}

data class PresenceOutboundRequest(
    val message: AdapterOutboundMessage,
    val authorization: OutboundAuthorization,
    val sourceEventId: String? = null
)

data class OutboundGateResult(
    val sent: Boolean,
    val reason: String
)

internal data class OutboundPolicyInput(
    val adapterRegistered: Boolean,
    val availability: PresenceAvailability,
    val capabilities: Set<PresenceCapability>,
    val healthy: Boolean,
    val hasConversationKey: Boolean,
    val textLength: Int,
    val attachmentCount: Int,
    val authorization: OutboundAuthorization,
    val sourceMatches: Boolean,
    val elapsedSinceLastSendMs: Long
)

internal object PresenceOutboundPolicy {
    const val MIN_SEND_INTERVAL_MS = 1_500L

    fun evaluate(input: OutboundPolicyInput): String? = when {
        !input.adapterRegistered -> "adapter_not_registered"
        input.availability == PresenceAvailability.PLANNED -> "adapter_not_ready"
        PresenceCapability.SEND_TEXT !in input.capabilities -> "send_text_not_supported"
        !input.healthy -> "adapter_unhealthy"
        !input.hasConversationKey -> "conversation_required"
        input.textLength !in 1..4_000 -> "invalid_text"
        input.attachmentCount > 0 && PresenceCapability.SEND_MEDIA !in input.capabilities ->
            "send_media_not_supported"
        input.authorization == OutboundAuthorization.DIRECT_REPLY && !input.sourceMatches ->
            "source_event_not_authorized"
        input.elapsedSinceLastSendMs < MIN_SEND_INTERVAL_MS -> "rate_limited"
        else -> null
    }
}

/**
 * The only path from tiyo's shared brain to an external Presence Adapter.
 *
 * Adapters remain transport-only. This gate binds direct replies to the exact inbound event,
 * checks capability/health, applies duplicate protection, and records successful expression.
 */
object PresenceOutboundGate {
    private val lastSentAt = ConcurrentHashMap<String, Long>()

    suspend fun dispatch(context: Context, request: PresenceOutboundRequest): OutboundGateResult {
        val app = context.applicationContext
        val message = request.message.copy(
            conversationKey = request.message.conversationKey?.trim()?.take(160),
            text = request.message.text.trim().take(4_000),
            attachments = request.message.attachments.take(PresenceEvent.MAX_ATTACHMENTS)
        )
        val adapter = PresenceAdapterRegistry.get(message.channel)
        val source = request.sourceEventId?.let { eventId ->
            PresenceEventStore.recent(app, 200).firstOrNull { it.id == eventId }
        }
        val sourceMatches = request.authorization == OutboundAuthorization.USER_APPROVED ||
            source?.let { event ->
                event.direction == PresenceDirection.TO_COMPANION &&
                    event.explicitUserAction &&
                    event.consumedAt == null &&
                    event.channel == message.channel &&
                    event.conversationKey == message.conversationKey
            } == true
        val key = "${message.channel.name}:${message.conversationKey.orEmpty()}:${request.sourceEventId ?: "approved"}"
        val now = System.currentTimeMillis()
        val lastAt = lastSentAt[key] ?: Long.MIN_VALUE
        val health = adapter?.let { runCatching { it.health() }.getOrNull() }
        val blocked = PresenceOutboundPolicy.evaluate(
            OutboundPolicyInput(
                adapterRegistered = adapter != null,
                availability = adapter?.availability ?: PresenceAvailability.PLANNED,
                capabilities = adapter?.capabilities.orEmpty(),
                healthy = health?.healthy == true,
                hasConversationKey = !message.conversationKey.isNullOrBlank(),
                textLength = message.text.length,
                attachmentCount = message.attachments.size,
                authorization = request.authorization,
                sourceMatches = sourceMatches,
                elapsedSinceLastSendMs = if (lastAt == Long.MIN_VALUE) Long.MAX_VALUE else now - lastAt
            )
        )
        if (blocked != null) return OutboundGateResult(false, blocked)

        val sent = runCatching { adapter!!.send(message) }.getOrDefault(false)
        if (!sent) return OutboundGateResult(false, "transport_failed")

        lastSentAt[key] = now
        source?.let { PresenceEventStore.markConsumed(app, it.id, now) }
        PresenceRouter.publish(
            app,
            PresenceEvent(
                id = "out_${message.channel.name.lowercase()}_$now",
                channel = message.channel,
                direction = PresenceDirection.FROM_COMPANION,
                modality = if (message.attachments.isEmpty()) PresenceModality.TEXT else PresenceModality.COMPOSITE,
                text = message.text,
                attachments = message.attachments,
                conversationKey = message.conversationKey,
                explicitUserAction = request.authorization == OutboundAuthorization.USER_APPROVED,
                occurredAt = now
            )
        )
        return OutboundGateResult(true, "sent")
    }
}
