package com.koyo.screenwarden

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Runs persisted extraction jobs serially and retries transient failures. */
class TiyoMemoryExtractionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = extractionMutex.withLock {
        val jobId = inputData.getString(KEY_JOB_ID).orEmpty()
        val scope = CompanionScope.of(
            inputData.getString(KEY_COMPANION_ID).orEmpty(),
            inputData.getString(KEY_COMPANION_NAME).orEmpty()
        )
        if (jobId.isBlank()) return@withLock Result.failure()
        val job = TiyoMemoryJobStore.load(applicationContext, scope, jobId)
            ?: return@withLock Result.failure()
        TiyoMemoryJobStore.markRunning(applicationContext, scope)
        val extraction = withContext(Dispatchers.IO) {
            TiyoMemoryExtractor.extractAndWrite(applicationContext, scope, job.turns)
        }
        if (extraction.succeeded) {
            TiyoMemoryJobStore.markSucceeded(applicationContext, job, extraction)
            TiyoMemoryJobStore.delete(applicationContext, scope, jobId)
            return@withLock Result.success()
        }
        if (extraction.retryable && runAttemptCount + 1 < MAX_ATTEMPTS) {
            TiyoMemoryJobStore.markRetrying(applicationContext, scope, extraction.message)
            Result.retry()
        } else {
            val message = if (extraction.retryable) {
                "${extraction.message}，已停止自动重试"
            } else {
                extraction.message
            }
            TiyoMemoryJobStore.markFailed(applicationContext, job, message)
            TiyoMemoryJobStore.delete(applicationContext, scope, jobId)
            Result.failure()
        }
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_COMPANION_ID = "companion_id"
        const val KEY_COMPANION_NAME = "companion_name"
        private const val MAX_ATTEMPTS = 5
        private val extractionMutex = Mutex()
    }
}
