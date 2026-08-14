package com.koyo.screenwarden.events

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 把现有小时采集结果转成稀疏事件，避免每次采集都惊动模型。 */
object UsageEventEmitter {
    private const val PREFS = "tiyo_event_milestones"
    private val stepMilestones = intArrayOf(3_000, 6_000, 10_000)
    private val appThresholds = intArrayOf(105, 165, 225, 285)

    fun emit(context: Context, screenReport: String, steps: Int) {
        emitStepMilestone(context, steps)
        emitApproachingLimits(context, screenReport)
        EventBus.publish(
            context,
            TiyoEvent(TiyoEventType.TIME_ANCHOR, "每小时状态心跳，结合当前时间和本地状态判断")
        )
    }

    private fun emitStepMilestone(context: Context, steps: Int) {
        if (steps < 0) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = dayKey()
        val previous = if (prefs.getString("step_day", "") == day) {
            prefs.getInt("step_milestone", 0)
        } else 0
        val reached = stepMilestones.lastOrNull { steps >= it } ?: return
        if (reached <= previous) return
        prefs.edit().putString("step_day", day).putInt("step_milestone", reached).apply()
        EventBus.publish(
            context,
            TiyoEvent(TiyoEventType.STEP_MILESTONE, "今天的步数刚达到${reached}步活动里程碑")
        )
    }

    private fun emitApproachingLimits(context: Context, report: String) {
        val monitored = mapOf("抖音" to "抖音", "aweme" to "抖音", "王者荣耀" to "王者荣耀", "sgame" to "王者荣耀")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = dayKey()
        report.lineSequence().forEach { line ->
            val parts = line.split(": ", limit = 2)
            if (parts.size != 2) return@forEach
            val app = monitored[parts[0]] ?: return@forEach
            val minutes = parseMinutes(parts[1])
            val reached = appThresholds.lastOrNull { minutes >= it } ?: return@forEach
            val key = "app_${app.hashCode()}"
            val previous = if (prefs.getString("${key}_day", "") == day) prefs.getInt(key, 0) else 0
            if (reached <= previous) return@forEach
            prefs.edit().putString("${key}_day", day).putInt(key, reached).apply()
            EventBus.publish(
                context,
                TiyoEvent(TiyoEventType.APP_LIMIT_APPROACHING, "$app 今日使用时长刚达到${reached}分钟提醒档")
            )
        }
    }

    private fun parseMinutes(value: String): Int {
        val hours = Regex("(\\d+)h").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(\\d+)min").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return hours * 60 + minutes
    }

    private fun dayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
