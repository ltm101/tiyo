package com.koyo.screenwarden

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.work.*
import com.koyo.screenwarden.events.UsageEventEmitter
import java.text.SimpleDateFormat
import java.util.*

class UsageReportWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val ctx = applicationContext
            val collector = ScreenUsageCollector(ctx)
            val screenReport = collector.collectDailyUsage()

            // 采集天气
            val weather = WeatherFetcher.fetch()
            val weatherLine = if (weather.isNotEmpty()) "Weather: $weather\n\n" else ""

            // 采集步数
            val steps = StepCounterCollector.refreshAndGetSteps(ctx)
            val stepSummary = if (steps >= 0) {
                val km = StepCounterCollector.stepsToKm(steps)
                val kcal = StepCounterCollector.stepsToKcal(steps)
                "\n---\nSteps today: %,d  (≈%s km, ≈%,d kcal)".format(steps, km, kcal)
            } else ""

            // 采集电量
            val batteryPct = getBatteryPercent(ctx)
            val batteryLine = if (batteryPct >= 0) "Battery: $batteryPct%%\n" else ""

            val report = weatherLine + screenReport + stepSummary
            saveToFile(ctx, report)

            // 事件化入口：步数里程碑、App 限额逼近和小时兜底心跳。
            UsageEventEmitter.emit(ctx, screenReport, steps)

            // ── 实时状态草稿（可又随时查） ──
            val loc = LocationCollector.getCurrentLocation(ctx)
            val stateText = buildStateText(screenReport, weather, steps, batteryPct, LocationCollector.format(loc))
            EmailSender.saveDraft(stateText)

            // ── 超限检查 ──
            val alerts = LimitChecker.check(ctx, screenReport)
            for (alert in alerts) {
                Log.i(TAG, "Limit exceeded: $alert")
                ActionExecutor.notify(ctx, alert)
                EmailSender.sendReport(alert, MailConfig.agentEmail(), "tiyo-alert")
            }

            // ── 久坐提醒 ──
            checkSedentary(ctx)

            // ── 睡觉提醒 ──
            checkSleepReminder(ctx)

            // ── 低电量提醒 ──
            checkBatteryReminder(ctx, batteryPct)

            // ── 每日定时报告（22:00） ──
            val shouldDaily = shouldSendDailyReport()
            if (shouldDaily) {
                // 22 点只做兜底；用户稍后说晚安时会再按最新对话幂等更新当天物件
                // 模型不可用时 MemoryShelfStore 自行退化，不阻塞原日报发送
                runCatching { MemoryShelfStore.settleToday(ctx) }
                    .onFailure { Log.w(TAG, "Memory shelf settlement failed") }
                Log.i(TAG, "Sending daily report")
                val result = EmailSender.sendReport(report, MailConfig.agentEmail())
                if (result.isSuccess) {
                    Log.i(TAG, "Daily report sent")
                    markDailyReportSent()
                } else {
                    Log.e(TAG, "Daily report failed: ${result.exceptionOrNull()?.message}")
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Worker failed", e)
            Result.retry()
        }
    }

    // ── 构建实时状态草稿 ──
    private fun buildStateText(screenReport: String, weather: String, steps: Int, battery: Int, loc: String): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val topApps = screenReport.lines()
            .filter { it.contains(":") && !it.startsWith("=") && !it.startsWith("-") && !it.startsWith("Screen") && !it.startsWith("Total") }
            .take(3)
            .joinToString(", ") { it.trim() }

        val stepStr = if (steps >= 0) {
            val km = StepCounterCollector.stepsToKm(steps)
            "$steps 步 ≈${km}km"
        } else "—"

        return buildString {
            appendLine("tiyo-state $dateStr")
            appendLine("Top: $topApps")
            appendLine("Steps: $stepStr")
            appendLine("Location: $loc")
            if (weather.isNotEmpty()) appendLine("Weather: $weather")
            if (battery >= 0) appendLine("Battery: ${battery}%")
        }
    }

    // ── 久坐提醒：每小时弹一次（8-22点） ──
    private fun checkSedentary(ctx: Context) {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        if (hour < 8 || hour > 22) return

        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastHour = prefs.getString(KEY_LAST_SEDENTARY, "")
        val currentHour = "$today-$hour"

        if (lastHour == currentHour) return

        ActionExecutor.notify(ctx, "起来走走，坐太久了")
        prefs.edit().putString(KEY_LAST_SEDENTARY, currentHour).apply()
        Log.i(TAG, "Sedentary reminder sent for $currentHour")
    }

    // ── 睡觉提醒：23:00 ──
    private fun checkSleepReminder(ctx: Context) {
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) != 23) return

        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastSent = prefs.getString(KEY_LAST_SLEEP, "")

        if (lastSent == today) return

        ActionExecutor.notify(ctx, "不早了，该睡了")
        prefs.edit().putString(KEY_LAST_SLEEP, today).apply()
        Log.i(TAG, "Sleep reminder sent")
    }

    // ── 低电量提醒：早上 6-10 点，电量 <30% ──
    private fun checkBatteryReminder(ctx: Context, batteryPct: Int) {
        if (batteryPct < 0 || batteryPct >= 30) return

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        if (hour < 6 || hour > 10) return

        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastSent = prefs.getString(KEY_LAST_BATTERY, "")

        if (lastSent == today) return

        ActionExecutor.notify(ctx, "电量只有 ${batteryPct}%，出门记得带充电宝")
        prefs.edit().putString(KEY_LAST_BATTERY, today).apply()
        Log.i(TAG, "Battery reminder sent ($batteryPct%)")
    }

    // ── 获取电量百分比 ──
    private fun getBatteryPercent(ctx: Context): Int {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val status = ctx.registerReceiver(null, filter) ?: return -1
            val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        } catch (e: Exception) {
            -1
        }
    }

    // ── 每日定时报告 ──
    private fun shouldSendDailyReport(): Boolean {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastSent = prefs.getString(KEY_LAST_DAILY, "")
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour == 22 && lastSent != today
    }

    private fun markDailyReportSent() {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        prefs.edit().putString(KEY_LAST_DAILY, today).apply()
    }

    private fun saveToFile(context: Context, data: String) {
        context.openFileOutput("usage_report.txt", Context.MODE_PRIVATE).use {
            it.write(data.toByteArray())
        }
    }

    companion object {
        private const val TAG = "UsageReportWorker"
        private const val PREFS_NAME = "tiyo_worker"
        private const val KEY_LAST_DAILY = "last_daily_report"
        private const val KEY_LAST_SEDENTARY = "last_sedentary"
        private const val KEY_LAST_SLEEP = "last_sleep"
        private const val KEY_LAST_BATTERY = "last_battery"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<UsageReportWorker>(
                1, java.util.concurrent.TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10, java.util.concurrent.TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "usage_report",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
        }
    }
}
