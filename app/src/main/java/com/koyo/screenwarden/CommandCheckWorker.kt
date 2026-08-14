package com.koyo.screenwarden

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * 轻量级 Worker：只检查 IMAP 指令邮件。每 2 分钟跑一次。
 */
class CommandCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val cmd = MailChecker.checkForCommand() ?: return Result.success()

            when (cmd) {
                is Command.Report -> handleReport()
                is Command.FileQuery -> handleFileQuery(cmd.path)
                is Command.Ring -> handleAction(ActionExecutor.ring(applicationContext))
                is Command.Notify -> handleAction(ActionExecutor.notify(applicationContext, cmd.text))
                is Command.LaunchApp -> handleAction(ActionExecutor.launchApp(applicationContext, cmd.pkg))
                is Command.SendSms -> handleAction(ActionExecutor.sendSms(applicationContext, cmd.number, cmd.text))
                is Command.Suggest -> handleAction(ActionExecutor.suggestReply(applicationContext, cmd.contact, cmd.text))
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Worker failed", e)
            Result.retry()
        }
    }

    private suspend fun handleReport() {
        Log.i(TAG, "Report command detected")

        val collector = ScreenUsageCollector(applicationContext)
        val screenReport = collector.collectDailyUsage()

        val weather = WeatherFetcher.fetch()
        val weatherLine = if (weather.isNotEmpty()) "Weather: $weather\n\n" else ""

        val steps = StepCounterCollector.refreshAndGetSteps(applicationContext)
        val stepSummary = if (steps >= 0) {
            val km = StepCounterCollector.stepsToKm(steps)
            val kcal = StepCounterCollector.stepsToKcal(steps)
            "\n---\nSteps today: %,d  (≈%s km, ≈%,d kcal)".format(steps, km, kcal)
        } else ""

        val result = EmailSender.sendReport(
            weatherLine + screenReport + stepSummary,
            MailConfig.agentEmail()
        )
        if (result.isSuccess) Log.i(TAG, "Report sent") else Log.e(TAG, "Report failed")
    }

    private suspend fun handleFileQuery(path: String) {
        Log.i(TAG, "File query: $path")

        if (!FileManager.isAllowed(path)) {
            EmailSender.sendReport(
                "拒绝访问: $path\n只允许访问 /sdcard 目录。",
                MailConfig.agentEmail(),
                "tiyo-file"
            )
            return
        }

        val content = FileManager.listDir(path)
        val result = EmailSender.sendReport(content, MailConfig.agentEmail(), "tiyo-file")
        if (result.isSuccess) Log.i(TAG, "File list sent") else Log.e(TAG, "File list failed")
    }

    /** 动作类指令统一回执 */
    private suspend fun handleAction(result: String) {
        Log.i(TAG, "action result: $result")
        EmailSender.sendReport(result, MailConfig.agentEmail(), "tiyo-ack")
    }

    companion object {
        private const val TAG = "CommandCheckWorker"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CommandCheckWorker>(
                2, TimeUnit.MINUTES
            )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "command_check",
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }
    }
}
