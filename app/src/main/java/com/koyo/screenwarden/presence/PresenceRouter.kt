package com.koyo.screenwarden.presence

import android.content.Context
import android.util.Log
import com.koyo.screenwarden.enuman.EnuManRuntime
import com.koyo.screenwarden.enuman.experience.ExperienceRecorder
import com.koyo.screenwarden.events.TiyoEvent
import com.koyo.screenwarden.events.TiyoEventType

/** 所有“身体”最终汇入同一人格事件流。 */
object PresenceRouter {
    fun publish(context: Context, event: PresenceEvent): PresenceEvent {
        val safe = event.persistedCopy()
        val recorded = runCatching {
            PresenceEventStore.record(context.applicationContext, safe)
        }.onFailure { error ->
            Log.w("PresenceRouter", "presence event persistence failed", error)
        }.getOrDefault(safe)
        EnuManRuntime.observePresence(context.applicationContext, recorded)
        runCatching {
            ExperienceRecorder.presence(context.applicationContext, recorded)
        }.onFailure { error ->
            Log.w("PresenceRouter", "experience ledger presence write failed", error)
        }
        PresenceConversationCoordinator.accept(context.applicationContext, recorded)
        return recorded
    }

    fun observeDecisionEvent(context: Context, event: TiyoEvent) {
        val channel = when (event.type) {
            TiyoEventType.NOTIFICATION,
            TiyoEventType.NOTIFICATION_BURST -> PresenceChannel.NOTIFICATION
            TiyoEventType.COMPANION_CONTEXT -> PresenceChannel.SCREEN_COMPANION
            else -> PresenceChannel.TIYO
        }
        publish(
            context,
            PresenceEvent(
                id = "decision_${event.id}",
                channel = channel,
                direction = PresenceDirection.OBSERVED,
                modality = PresenceModality.APP_CONTEXT,
                text = event.persistedCopy().summary,
                conversationKey = event.topicKey,
                explicitUserAction = false,
                occurredAt = event.occurredAt
            )
        )
    }
}
