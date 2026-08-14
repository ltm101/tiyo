package com.koyo.screenwarden

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object GoodnightMessageClassifier {
    private val positive = listOf(
        "晚安", "我睡了", "先睡了", "去睡了", "我要睡了", "我去睡了",
        "睡觉了", "去睡觉", "睡啦", "睡咯", "困了先睡", "明天见"
    )
    private val negative = listOf(
        "不睡", "还不睡", "别睡", "不能睡", "不晚安", "晚安是什么意思",
        "说晚安", "回复晚安", "生成晚安", "晚安文案"
    )

    fun isGoodnight(text: String): Boolean {
        val clean = text.replace(Regex("""[\r\n\s，。！？,.!?~～]+"""), "").trim()
        if (clean.isBlank() || clean.length > 32) return false
        if (negative.any(clean::contains)) return false
        return positive.any(clean::contains)
    }
}

object MemoryDayKey {
    private const val DAY_BOUNDARY_MS = 5 * 60 * 60_000L

    /** 凌晨五点前仍算作前一个生活日，符合晚睡场景 */
    fun from(timestamp: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        .format(Date(timestamp - DAY_BOUNDARY_MS))
}

object MemoryShelfCoordinator {
    fun onUserMessage(context: Context, text: String, timestamp: Long = System.currentTimeMillis()) {
        if (!GoodnightMessageClassifier.isGoodnight(text)) return
        val date = MemoryDayKey.from(timestamp)
        val scope = CompanionScope.capture(context)
        val input = Data.Builder()
            .putString(MemoryShelfSettlementWorker.KEY_DATE, date)
            .putString(MemoryShelfSettlementWorker.KEY_TRIGGER, "goodnight")
            .putString(MemoryShelfSettlementWorker.KEY_COMPANION_ID, scope.companionId)
            .putString(MemoryShelfSettlementWorker.KEY_COMPANION_NAME, scope.displayName)
            .build()
        val request = OneTimeWorkRequestBuilder<MemoryShelfSettlementWorker>()
            .setInputData(input)
            // 留一点时间让Tiyo的晚安回复也进入同一份会话历史
            .setInitialDelay(20, TimeUnit.SECONDS)
            .addTag(scope.namespaced("memory_shelf_settlement"))
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            scope.namespaced("memory_shelf_goodnight_$date"),
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}

class MemoryShelfSettlementWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val date = inputData.getString(KEY_DATE).orEmpty()
        if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(date)) return@withContext Result.failure()
        val trigger = inputData.getString(KEY_TRIGGER).orEmpty().ifBlank { "goodnight" }
        val storedId = inputData.getString(KEY_COMPANION_ID).orEmpty()
        val scope = if (storedId.isBlank()) {
            CompanionScope.capture(applicationContext)
        } else {
            CompanionScope.of(storedId, inputData.getString(KEY_COMPANION_NAME).orEmpty())
        }
        return@withContext if (MemoryShelfStore.settleDate(applicationContext, date, trigger, scope)) {
            Result.success()
        } else {
            // 当天没有可结算对话不是网络错误，不做无限重试
            Result.success()
        }
    }

    companion object {
        const val KEY_DATE = "date"
        const val KEY_TRIGGER = "trigger"
        const val KEY_COMPANION_ID = "companion_id"
        const val KEY_COMPANION_NAME = "companion_name"
    }
}
