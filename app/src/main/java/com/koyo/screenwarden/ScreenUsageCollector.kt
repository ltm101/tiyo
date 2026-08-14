package com.koyo.screenwarden

import android.content.Context
import android.content.pm.PackageManager
import android.app.usage.UsageStatsManager
import java.text.SimpleDateFormat
import java.util.*

class ScreenUsageCollector(private val context: Context) {

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    fun collectDailyUsage(): String {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            cal.timeInMillis,
            System.currentTimeMillis()
        )

        val sb = StringBuilder()
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        sb.appendLine("Screen Usage Report - $dateStr")
        sb.appendLine("=".repeat(30))

        var totalTime = 0L

        stats
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(15)
            .forEach { stat ->
                val label = getAppLabel(stat.packageName)
                val formatted = formatDuration(stat.totalTimeInForeground)
                totalTime += stat.totalTimeInForeground
                sb.appendLine("$label: $formatted")
            }

        sb.appendLine("-".repeat(30))
        val totalFormatted = formatDuration(totalTime)
        sb.appendLine("Total: $totalFormatted")

        return sb.toString()
    }

    private fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 1000 / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}min" else "${minutes}min"
    }

    private fun getAppLabel(packageName: String): String {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            // 系统包或已卸载的应用，返回简化包名
            packageName.split(".").lastOrNull() ?: packageName
        } catch (_: Exception) {
            packageName
        }
    }
}
