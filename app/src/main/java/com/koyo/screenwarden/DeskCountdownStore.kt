package com.koyo.screenwarden

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Locale

internal object DeskCountdownStore {
    data class State(
        val label: String,
        val remainingMs: Long,
        val running: Boolean,
        val paused: Boolean,
        val scope: CompanionScope
    )

    data class Finished(val label: String, val scope: CompanionScope)

    private const val PREFS = "deep_companion_desk_timer"
    private const val KEY_END_AT = "end_at"
    private const val KEY_REMAINING = "remaining"
    private const val KEY_LABEL = "label"
    private const val KEY_COMPANION_ID = "companion_id"
    private const val KEY_COMPANION_NAME = "companion_name"
    private const val DEFAULT_MINUTES = 25

    fun state(context: Context, now: Long = System.currentTimeMillis()): State {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val endAt = prefs.getLong(KEY_END_AT, 0L)
        val pausedRemaining = prefs.getLong(KEY_REMAINING, 0L)
        val scope = CompanionScope.of(
            prefs.getString(KEY_COMPANION_ID, CompanionProfileStore.activeId(context)).orEmpty(),
            prefs.getString(KEY_COMPANION_NAME, CompanionProfileStore.activeName(context)).orEmpty()
        )
        val running = endAt > 0L && endAt > now
        val expired = endAt > 0L && endAt <= now
        return State(
            label = prefs.getString(KEY_LABEL, "专注时间").orEmpty().ifBlank { "专注时间" },
            remainingMs = when {
                running -> endAt - now
                expired -> 0L
                pausedRemaining > 0L -> pausedRemaining
                else -> DEFAULT_MINUTES * 60_000L
            },
            running = running,
            paused = endAt == 0L && pausedRemaining > 0L,
            scope = scope
        )
    }

    fun start(context: Context, minutes: Int, label: String = "专注时间") {
        startMillis(
            context,
            minutes.coerceIn(1, 24 * 60) * 60_000L,
            label,
            CompanionScope.capture(context)
        )
    }

    fun pause(context: Context) {
        val state = state(context)
        if (!state.running) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_END_AT, 0L)
            .putLong(KEY_REMAINING, state.remainingMs.coerceAtLeast(1_000L))
            .apply()
        cancelAlarm(context)
    }

    fun resume(context: Context) {
        val state = state(context)
        if (!state.paused) return
        startMillis(context, state.remainingMs, state.label, state.scope)
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        cancelAlarm(context)
    }

    @Synchronized
    fun consumeFinished(context: Context, now: Long = System.currentTimeMillis()): Finished? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val endAt = prefs.getLong(KEY_END_AT, 0L)
        if (endAt <= 0L || endAt > now) return null
        val label = prefs.getString(KEY_LABEL, "专注时间").orEmpty().ifBlank { "专注时间" }
        val scope = CompanionScope.of(
            prefs.getString(KEY_COMPANION_ID, CompanionProfileRules.DEFAULT_COMPANION_ID).orEmpty(),
            prefs.getString(KEY_COMPANION_NAME, CompanionProfileRules.DEFAULT_COMPANION_NAME).orEmpty()
        )
        prefs.edit().clear().commit()
        cancelAlarm(context)
        return Finished(label, scope)
    }

    fun format(remainingMs: Long): String {
        val totalSeconds = ((remainingMs.coerceAtLeast(0L) + 999L) / 1_000L).coerceAtMost(99 * 60 * 60L)
        val hours = totalSeconds / 3_600L
        val minutes = totalSeconds % 3_600L / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    private fun startMillis(
        context: Context,
        durationMs: Long,
        label: String,
        scope: CompanionScope
    ) {
        val safeDuration = durationMs.coerceIn(1_000L, 24L * 60L * 60_000L)
        val endAt = System.currentTimeMillis() + safeDuration
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_END_AT, endAt)
            .putLong(KEY_REMAINING, safeDuration)
            .putString(KEY_LABEL, label.trim().take(40).ifBlank { "专注时间" })
            .putString(KEY_COMPANION_ID, scope.companionId)
            .putString(KEY_COMPANION_NAME, scope.displayName)
            .apply()
        scheduleAlarm(context, endAt)
    }

    private fun scheduleAlarm(context: Context, endAt: Long) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pendingIntent(context))
    }

    private fun cancelAlarm(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        manager.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        4127,
        Intent(context, DeskCountdownReceiver::class.java).setAction(DeskCountdownReceiver.ACTION_FINISH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
