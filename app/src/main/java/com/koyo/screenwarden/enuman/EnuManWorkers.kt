package com.koyo.screenwarden.enuman

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.koyo.screenwarden.CompanionScope
import com.koyo.screenwarden.enuman.experience.ExperienceRecorder
import com.koyo.screenwarden.presence.PresenceEventStore
import java.util.concurrent.TimeUnit

class EnuManInterpretationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val scope = inputScope() ?: return Result.failure()
        val pulseId = inputData.getString(KEY_PULSE_ID).orEmpty()
        val pulse = EnuManStore.pulse(applicationContext, scope, pulseId) ?: return Result.success()
        if (EnuManStore.latestInterpretation(applicationContext, scope, pulseId) != null) {
            return Result.success()
        }
        val result = EnuManInterpreter.interpret(applicationContext, scope, pulse)
            ?: return if (runAttemptCount < 3) Result.retry() else Result.success()
        EnuManStore.appendInterpretation(applicationContext, scope, result.interpretation)
        return Result.success()
    }

    private fun inputScope(): CompanionScope? {
        val id = inputData.getString(KEY_COMPANION_ID).orEmpty()
        if (id.isBlank()) return null
        return CompanionScope.of(id, inputData.getString(KEY_COMPANION_NAME).orEmpty())
    }

    companion object {
        private const val KEY_PULSE_ID = "pulse_id"
        internal const val KEY_COMPANION_ID = "companion_id"
        internal const val KEY_COMPANION_NAME = "companion_name"

        fun enqueue(context: Context, scope: CompanionScope, pulseId: String) {
            val request = OneTimeWorkRequestBuilder<EnuManInterpretationWorker>()
                .setInputData(workDataOf(
                    KEY_PULSE_ID to pulseId,
                    KEY_COMPANION_ID to scope.companionId,
                    KEY_COMPANION_NAME to scope.displayName
                ))
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
                .addTag("enuman_interpretation")
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "enuman_interpret_${scope.companionId}_$pulseId",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}

class EnuManReflectionWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(EnuManInterpretationWorker.KEY_COMPANION_ID).orEmpty()
        if (id.isBlank()) return Result.failure()
        val scope = CompanionScope.of(
            id,
            inputData.getString(EnuManInterpretationWorker.KEY_COMPANION_NAME).orEmpty()
        )
        val kind = runCatching {
            enumValueOf<ReflectionKind>(inputData.getString(KEY_KIND).orEmpty())
        }.getOrNull() ?: return Result.failure()
        val unresolved = EnuManStore.unresolvedInterpretations(
            applicationContext,
            scope,
            if (kind == ReflectionKind.DEEP_SLEEP) 8 else 3
        )
        if (unresolved.isEmpty()) return Result.success()

        val startedAt = System.currentTimeMillis()
        val produced = mutableListOf<ImpulseInterpretation>()
        val proposedPlasticity = mutableMapOf<String, Double>()
        for (parent in unresolved) {
            val pulse = EnuManStore.pulse(applicationContext, scope, parent.pulseId) ?: continue
            val result = EnuManInterpreter.interpret(
                applicationContext,
                scope,
                pulse,
                parent,
                kind,
                unresolved.filterNot { it.id == parent.id }
            ) ?: continue
            EnuManStore.appendInterpretation(applicationContext, scope, result.interpretation)
            produced += result.interpretation
            result.proposedPlasticity.forEach { (key, delta) ->
                proposedPlasticity[key] = (proposedPlasticity[key] ?: 0.0) + delta
            }
        }
        if (produced.isEmpty()) return if (runAttemptCount < 2) Result.retry() else Result.success()

        val completedAt = System.currentTimeMillis()
        val state = EnuManStore.loadState(applicationContext, scope, completedAt)
        val (consolidated, applied) = EnuManSleepEngine.consolidate(
            state,
            kind,
            proposedPlasticity,
            completedAt
        )
        EnuManStore.saveState(applicationContext, scope, consolidated)
        val cycle = SleepCycle(
            kind = kind,
            startedAt = startedAt,
            completedAt = completedAt,
            replayedPulseIds = unresolved.map { it.pulseId },
            producedInterpretationIds = produced.map { it.id },
            appliedPlasticity = applied
        )
        EnuManStore.appendSleepCycle(applicationContext, scope, cycle)
        if (kind == ReflectionKind.DEEP_SLEEP) {
            runCatching {
                ExperienceRecorder.deepSleep(applicationContext, scope, cycle)
            }.onFailure { error ->
                Log.w("EnuManReflection", "experience ledger sleep write failed", error)
            }
        }
        return Result.success()
    }

    companion object {
        private const val KEY_KIND = "kind"

        fun enqueue(context: Context, scope: CompanionScope, kind: ReflectionKind) {
            val request = OneTimeWorkRequestBuilder<EnuManReflectionWorker>()
                .setInputData(workDataOf(
                    EnuManInterpretationWorker.KEY_COMPANION_ID to scope.companionId,
                    EnuManInterpretationWorker.KEY_COMPANION_NAME to scope.displayName,
                    KEY_KIND to kind.name
                ))
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("enuman_reflection")
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "enuman_reflect_${scope.companionId}_${kind.name.lowercase()}",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}

class EnuManRhythmWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val scope = CompanionScope.capture(applicationContext)
            val now = System.currentTimeMillis()
            EnuManRuntime.tick(applicationContext, scope, now)
            EnuManStore.pendingPulses(applicationContext, scope, 4).forEach { pulse ->
                EnuManInterpretationWorker.enqueue(applicationContext, scope, pulse.id)
            }
            val unresolved = EnuManStore.unresolvedInterpretations(applicationContext, scope, 24)
            if (unresolved.isNotEmpty()) {
                val state = EnuManStore.loadState(applicationContext, scope, now)
                val lastPresenceAt = PresenceEventStore.recent(applicationContext, 1)
                    .firstOrNull()?.occurredAt ?: 0L
                val lastInnerExperienceAt = unresolved.maxOfOrNull { it.generatedAt } ?: 0L
                val lastMeaningfulEventAt = maxOf(lastPresenceAt, lastInnerExperienceAt)
                    .takeIf { it > 0L } ?: state.lastTickAt
                val power = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                when (EnuManSleepEngine.decide(
                    state = state,
                    unresolvedCount = unresolved.size,
                    now = now,
                    lastMeaningfulEventAt = lastMeaningfulEventAt,
                    userResting = !power.isInteractive
                )) {
                    SleepDecision.SHORT_REFLECTION -> EnuManReflectionWorker.enqueue(
                        applicationContext,
                        scope,
                        ReflectionKind.SHORT_REFLECTION
                    )
                    SleepDecision.DEEP_SLEEP -> EnuManReflectionWorker.enqueue(
                        applicationContext,
                        scope,
                        ReflectionKind.DEEP_SLEEP
                    )
                    SleepDecision.NONE -> Unit
                }
            }
            Result.success()
        }.getOrElse { error ->
            Log.w("EnuManRhythm", "private rhythm failed", error)
            Result.success()
        }
    }

    companion object {
        private const val UNIQUE_WORK = "enuman_private_rhythm_v1"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<EnuManRhythmWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(2, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .addTag("enuman_rhythm")
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
