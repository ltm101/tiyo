package com.koyo.screenwarden.events

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.koyo.screenwarden.presence.PresenceRouter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 统一事件入口：先压成行为片段，再落脱敏队列并唤醒 DecisionEngineWorker。 */
object EventBus {
    private const val MAX_VOLATILE_CONTEXTS = 24
    private val volatileContexts = ConcurrentHashMap<String, String>()

    fun publish(context: Context, event: TiyoEvent) {
        val ctx = context.applicationContext
        PresenceRouter.observeDecisionEvent(ctx, event)
        val episodes = BehaviorEpisodeStore.ingest(ctx, event)
        if (episodes.isEmpty()) return
        var queued = false
        var shortestDelay = Long.MAX_VALUE
        episodes.forEach { episode ->
            episode.sensitiveContext?.takeIf { it.isNotBlank() }?.let {
                if (volatileContexts.size >= MAX_VOLATILE_CONTEXTS) volatileContexts.clear()
                volatileContexts[episode.id] = it.take(1_200)
            }
            if (EventQueue.enqueue(ctx, episode)) {
                queued = true
                shortestDelay = minOf(shortestDelay, debounceMs(episode.type))
            } else {
                volatileContexts.remove(episode.id)
            }
        }
        if (queued) schedule(ctx, shortestDelay)
    }

    internal fun restoreVolatile(events: List<TiyoEvent>): List<TiyoEvent> = events.map { event ->
        event.copy(sensitiveContext = volatileContexts.remove(event.id))
    }

    internal fun schedule(context: Context, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<DecisionEngineWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag(DecisionEngineWorker.WORK_TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueue(request)
    }

    private fun debounceMs(type: TiyoEventType): Long = when (type) {
        TiyoEventType.NOTIFICATION,
        TiyoEventType.NOTIFICATION_BURST -> 8_000L
        TiyoEventType.COMPANION_CONTEXT -> 3_000L
        TiyoEventType.TIME_ANCHOR, TiyoEventType.DEFERRED -> 0L
        else -> 20_000L
    }
}
