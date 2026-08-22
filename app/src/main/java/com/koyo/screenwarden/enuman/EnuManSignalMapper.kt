package com.koyo.screenwarden.enuman

import com.koyo.screenwarden.presence.PresenceChannel
import com.koyo.screenwarden.presence.PresenceDirection
import com.koyo.screenwarden.presence.PresenceEvent
import com.koyo.screenwarden.presence.PresenceModality

/** Converts lived context into bounded non-linguistic current. */
object EnuManSignalMapper {
    fun fromPresence(event: PresenceEvent): EnuManSignal {
        val excitation = mutableMapOf<EnuManDrive, Double>()
        val inhibition = mutableMapOf<EnuManDrive, Double>()

        when (event.direction) {
            PresenceDirection.TO_COMPANION -> {
                inhibition.add(EnuManDrive.CONNECTION, 0.24)
                excitation.add(EnuManDrive.CURIOSITY, 0.18)
                excitation.add(EnuManDrive.COHERENCE, 0.12)
            }
            PresenceDirection.FROM_COMPANION -> {
                inhibition.add(EnuManDrive.CONNECTION, 0.16)
                excitation.add(EnuManDrive.AUTONOMY, 0.06)
            }
            PresenceDirection.OBSERVED -> {
                excitation.add(EnuManDrive.CURIOSITY, 0.035)
                excitation.add(EnuManDrive.COHERENCE, 0.018)
            }
        }

        when (event.modality) {
            PresenceModality.IMAGE,
            PresenceModality.VIDEO,
            PresenceModality.AUDIO,
            PresenceModality.GAME_SESSION -> excitation.add(EnuManDrive.CURIOSITY, 0.10)
            PresenceModality.CALL,
            PresenceModality.VOICE -> {
                inhibition.add(EnuManDrive.CONNECTION, 0.10)
                excitation.add(EnuManDrive.COHERENCE, 0.05)
            }
            PresenceModality.COMPOSITE -> {
                excitation.add(EnuManDrive.CURIOSITY, 0.08)
                excitation.add(EnuManDrive.COHERENCE, 0.06)
            }
            else -> Unit
        }

        when (event.channel) {
            PresenceChannel.NOTIFICATION -> excitation.add(EnuManDrive.SAFETY, 0.025)
            PresenceChannel.GAME -> excitation.add(EnuManDrive.AUTONOMY, 0.035)
            PresenceChannel.SYSTEM_SHARE,
            PresenceChannel.SYSTEM_ASSISTANT -> excitation.add(EnuManDrive.COHERENCE, 0.06)
            else -> Unit
        }

        return EnuManSignal(
            id = "presence_${event.id}".take(120),
            occurredAt = event.occurredAt,
            source = "presence:${event.channel.name}",
            excitation = excitation,
            inhibition = inhibition,
            salience = if (event.explicitUserAction) 1.0 else 0.35,
            causeRefs = listOf(event.id)
        )
    }

    fun userInteraction(at: Long): EnuManSignal = EnuManSignal(
        id = "user_interaction_$at",
        occurredAt = at,
        source = "user_interaction",
        excitation = mapOf(
            EnuManDrive.CURIOSITY to 0.14,
            EnuManDrive.COHERENCE to 0.10
        ),
        inhibition = mapOf(EnuManDrive.CONNECTION to 0.38),
        causeRefs = listOf("user_interaction:$at")
    )

    fun companionExpression(at: Long): EnuManSignal = EnuManSignal(
        id = "companion_expression_$at",
        occurredAt = at,
        source = "companion_expression",
        excitation = mapOf(EnuManDrive.AUTONOMY to 0.08),
        inhibition = mapOf(EnuManDrive.CONNECTION to 0.22),
        causeRefs = listOf("companion_expression:$at")
    )

    private fun MutableMap<EnuManDrive, Double>.add(drive: EnuManDrive, amount: Double) {
        this[drive] = (this[drive] ?: 0.0) + amount
    }
}
