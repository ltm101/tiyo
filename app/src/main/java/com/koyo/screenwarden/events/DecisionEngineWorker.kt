package com.koyo.screenwarden.events

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class DecisionEngineWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val events = EventBus.restoreVolatile(EventQueue.takeReady(applicationContext, now))
        if (events.isNotEmpty()) {
            DecisionEngine.evaluate(applicationContext, events)
        }
        EventQueue.nextReadyAt(applicationContext)?.let { next ->
            EventBus.schedule(applicationContext, (next - System.currentTimeMillis()).coerceAtLeast(1_000L))
        }
        return Result.success()
    }

    companion object {
        const val WORK_TAG = "tiyo_decision_engine"
    }
}
