package com.koyo.screenwarden.events

import android.content.Context

/**
 * 把高频、低信息量的原始设备事件压成少量行为片段
 *
 * 这里只保存时间和计数，不保存通知标题或正文
 */
data class BehaviorEpisodeState(
    val screenOnAt: Long = 0L,
    val recentScreenMinutes: Int = 0,
    val recentScreenEndedAt: Long = 0L,
    val lastScreenEpisodeAt: Long = 0L,
    val notificationBurstStartedAt: Long = 0L,
    val notificationBurstCount: Int = 0
)

data class EpisodeReduction(
    val state: BehaviorEpisodeState,
    val events: List<TiyoEvent>
)

object BehaviorEpisodeReducer {
    private const val MIN_SCREEN_EPISODE_MS = 25 * 60_000L
    private const val SCREEN_HEARTBEAT_EPISODE_MS = 30 * 60_000L
    private const val SCREEN_EPISODE_REPEAT_MS = 45 * 60_000L
    private const val MAX_VALID_SCREEN_SESSION_MS = 12 * 60 * 60_000L
    private const val NOTIFICATION_BURST_WINDOW_MS = 10 * 60_000L
    private const val NOTIFICATION_BURST_SIZE = 3

    fun reduce(
        state: BehaviorEpisodeState,
        event: TiyoEvent,
        now: Long = event.occurredAt
    ): EpisodeReduction = when (event.type) {
        TiyoEventType.SCREEN_ON -> {
            val started = state.screenOnAt.takeIf {
                it > 0L && now - it in 0..MAX_VALID_SCREEN_SESSION_MS
            } ?: now
            EpisodeReduction(state.copy(screenOnAt = started), emptyList())
        }

        TiyoEventType.SCREEN_OFF -> closeScreenEpisode(state, now)

        TiyoEventType.TIME_ANCHOR -> heartbeat(state, event, now)

        TiyoEventType.NOTIFICATION -> notificationBurst(state, event, now)

        TiyoEventType.POWER_CONNECTED -> EpisodeReduction(
            state,
            listOf(
                event.copy(
                    topicKey = "rest_after_charge",
                    expiresAt = minOf(event.expiresAt, now + 60 * 60_000L)
                )
            )
        )

        TiyoEventType.STEP_MILESTONE -> EpisodeReduction(
            state,
            listOf(
                event.copy(
                    topicKey = "movement_today",
                    expiresAt = minOf(event.expiresAt, now + 4 * 60 * 60_000L)
                )
            )
        )

        TiyoEventType.APP_LIMIT_APPROACHING -> EpisodeReduction(
            state,
            listOf(
                event.copy(
                    topicKey = "screen_wellbeing:${safeAppKey(event.summary)}",
                    expiresAt = minOf(event.expiresAt, now + 45 * 60_000L)
                )
            )
        )

        TiyoEventType.COMPANION_CONTEXT -> EpisodeReduction(
            state,
            listOf(event.copy(expiresAt = minOf(event.expiresAt, now + 25 * 60_000L)))
        )

        TiyoEventType.POWER_DISCONNECTED -> EpisodeReduction(state, emptyList())

        TiyoEventType.SCREEN_SESSION,
        TiyoEventType.NOTIFICATION_BURST,
        TiyoEventType.DEFERRED -> EpisodeReduction(state, listOf(event))
    }

    private fun closeScreenEpisode(state: BehaviorEpisodeState, now: Long): EpisodeReduction {
        val started = state.screenOnAt
        if (started <= 0L || now <= started) {
            return EpisodeReduction(state.copy(screenOnAt = 0L), emptyList())
        }
        val duration = now - started
        if (duration > MAX_VALID_SCREEN_SESSION_MS) {
            return EpisodeReduction(state.copy(screenOnAt = 0L), emptyList())
        }
        val minutes = (duration / 60_000L).toInt().coerceAtLeast(1)
        val next = state.copy(
            screenOnAt = 0L,
            recentScreenMinutes = minutes,
            recentScreenEndedAt = now
        )
        if (duration < MIN_SCREEN_EPISODE_MS) return EpisodeReduction(next, emptyList())
        return EpisodeReduction(
            next.copy(lastScreenEpisodeAt = now),
            listOf(screenEpisode(minutes, now, stillActive = false))
        )
    }

    private fun heartbeat(
        state: BehaviorEpisodeState,
        original: TiyoEvent,
        now: Long
    ): EpisodeReduction {
        val events = mutableListOf(original.copy(
            topicKey = "companionship_window",
            expiresAt = minOf(original.expiresAt, now + 90 * 60_000L)
        ))
        if (original.summary.contains("开机")) {
            return EpisodeReduction(state.copy(screenOnAt = 0L), events)
        }
        val started = state.screenOnAt
        if (started <= 0L || now <= started) return EpisodeReduction(state, events)
        val duration = now - started
        if (duration < SCREEN_HEARTBEAT_EPISODE_MS || duration > MAX_VALID_SCREEN_SESSION_MS) {
            return EpisodeReduction(state, events)
        }
        if (now - state.lastScreenEpisodeAt < SCREEN_EPISODE_REPEAT_MS) {
            return EpisodeReduction(state, events)
        }
        val minutes = (duration / 60_000L).toInt()
        events += screenEpisode(minutes, now, stillActive = true)
        return EpisodeReduction(
            state.copy(
                recentScreenMinutes = minutes,
                recentScreenEndedAt = 0L,
                lastScreenEpisodeAt = now
            ),
            events
        )
    }

    private fun notificationBurst(
        state: BehaviorEpisodeState,
        event: TiyoEvent,
        now: Long
    ): EpisodeReduction {
        val withinBurst = state.notificationBurstStartedAt > 0L &&
            now - state.notificationBurstStartedAt in 0..NOTIFICATION_BURST_WINDOW_MS
        val startedAt = if (withinBurst) state.notificationBurstStartedAt else now
        val count = if (withinBurst) state.notificationBurstCount + 1 else 1
        val next = state.copy(
            notificationBurstStartedAt = startedAt,
            notificationBurstCount = count
        )
        if (count != NOTIFICATION_BURST_SIZE) return EpisodeReduction(next, emptyList())
        return EpisodeReduction(
            next,
            listOf(
                TiyoEvent(
                    type = TiyoEventType.NOTIFICATION_BURST,
                    summary = "短时间内连续收到多条通知，可能正在忙",
                    occurredAt = now,
                    topicKey = "notification_load",
                    expiresAt = now + 20 * 60_000L,
                    sensitiveContext = event.sensitiveContext
                )
            )
        )
    }

    private fun screenEpisode(minutes: Int, now: Long, stillActive: Boolean) = TiyoEvent(
        type = TiyoEventType.SCREEN_SESSION,
        summary = if (stillActive) {
            "屏幕已经持续使用约${minutes}分钟，目前仍在使用"
        } else {
            "刚结束一段约${minutes}分钟的连续屏幕使用"
        },
        occurredAt = now,
        topicKey = "screen_wellbeing:continuous",
        expiresAt = now + 30 * 60_000L
    )

    private fun safeAppKey(summary: String): String {
        val app = summary.substringBefore(' ').ifBlank { "app" }
        return "app_${app.hashCode().toUInt().toString(16)}"
    }
}

object BehaviorEpisodeStore {
    private const val PREFS = "tiyo_behavior_episodes"
    private val lock = Any()

    fun ingest(context: Context, event: TiyoEvent): List<TiyoEvent> = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val result = BehaviorEpisodeReducer.reduce(load(prefs), event)
        prefs.edit()
            .putLong("screen_on_at", result.state.screenOnAt)
            .putInt("recent_screen_minutes", result.state.recentScreenMinutes)
            .putLong("recent_screen_ended_at", result.state.recentScreenEndedAt)
            .putLong("last_screen_episode_at", result.state.lastScreenEpisodeAt)
            .putLong("notification_burst_started_at", result.state.notificationBurstStartedAt)
            .putInt("notification_burst_count", result.state.notificationBurstCount)
            .apply()
        result.events
    }

    fun snapshot(context: Context): BehaviorEpisodeState = synchronized(lock) {
        load(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }

    private fun load(prefs: android.content.SharedPreferences) = BehaviorEpisodeState(
        screenOnAt = prefs.getLong("screen_on_at", 0L),
        recentScreenMinutes = prefs.getInt("recent_screen_minutes", 0),
        recentScreenEndedAt = prefs.getLong("recent_screen_ended_at", 0L),
        lastScreenEpisodeAt = prefs.getLong("last_screen_episode_at", 0L),
        notificationBurstStartedAt = prefs.getLong("notification_burst_started_at", 0L),
        notificationBurstCount = prefs.getInt("notification_burst_count", 0)
    )
}
