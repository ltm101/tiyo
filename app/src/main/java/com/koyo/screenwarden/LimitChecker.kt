package com.koyo.screenwarden

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

object LimitChecker {

    private const val PREFS_NAME = "tiyo_limits"
    private const val KEY_ALERT_PREFIX = "alert_"

    /** 阶梯阈值（分钟）：2h → 3h → 4h → 5h */
    private val thresholds = listOf(120, 180, 240, 300)

    /** 需要监控的应用名（中文名 + fallback 包名尾巴） */
    private val monitoredApps = mapOf(
        "抖音" to "抖音",
        "aweme" to "抖音",
        "王者荣耀" to "王者荣耀",
        "sgame" to "王者荣耀"
    )

    /**
     * 扫描报告，阶梯式超限提醒。
     * 例如抖音 2h 提醒一次，3h 再提醒，4h 再提醒……
     * 每天重置。
     */
    fun check(context: Context, report: String): List<String> {
        val alerts = mutableListOf<String>()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        for (line in report.lines()) {
            val parts = line.split(": ")
            if (parts.size < 2) continue
            val appRaw = parts[0]
            val appName = monitoredApps[appRaw] ?: continue
            val timeStr = parts[1]
            val minutes = parseMinutes(timeStr)

            // 找到第一个还没触发过的阈值
            val alertKey = KEY_ALERT_PREFIX + appName
            val lastThreshold = prefs.getString(alertKey, "")
            val lastDate = prefs.getString(alertKey + "_date", "")

            // 新的一天，重置
            if (lastDate != today) {
                prefs.edit()
                    .putString(alertKey + "_date", today)
                    .putString(alertKey, "")
                    .apply()
            }

            val lastValue = if (lastDate == today) lastThreshold?.toIntOrNull() ?: 0 else 0

            for (t in thresholds) {
                if (minutes >= t && t > lastValue) {
                    val h = t / 60
                    alerts.add("$appName: ${formatTime(minutes)}，已超过 ${h} 小时")
                    prefs.edit().putString(alertKey, t.toString()).apply()
                    break  // 每次只触发当前最低的未触发阈值
                }
            }
        }

        return alerts
    }

    private fun parseMinutes(s: String): Int {
        var mins = 0
        val h = Regex("(\\d+)h").find(s)
        val m = Regex("(\\d+)min").find(s)
        if (h != null) mins += h.groupValues[1].toInt() * 60
        if (m != null) mins += m.groupValues[1].toInt()
        return mins
    }

    private fun formatTime(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}min" else "${m}min"
    }
}
