package com.koyo.screenwarden.enuman

import android.content.Context
import android.util.AtomicFile
import com.koyo.screenwarden.CompanionScope
import com.koyo.screenwarden.CompanionWorkspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Companion-scoped, bounded and crash-safe private state. */
object EnuManStore {
    private const val MAX_PULSES = 160
    private const val MAX_INTERPRETATIONS = 240
    private const val MAX_SLEEP_CYCLES = 80
    private val lock = Any()

    fun loadState(context: Context, scope: CompanionScope, now: Long): EnuManState = synchronized(lock) {
        val atomic = atomic(context, scope, "state.json")
        readText(atomic)?.let { raw ->
            runCatching { EnuManState.fromJson(JSONObject(raw), now) }.getOrNull()
        } ?: EnuManState.initial(now)
    }

    fun saveState(context: Context, scope: CompanionScope, state: EnuManState) = synchronized(lock) {
        writeText(atomic(context, scope, "state.json"), state.toJson().toString())
    }

    fun appendPulse(context: Context, scope: CompanionScope, pulse: PreSemanticPulse) = synchronized(lock) {
        val existing = readPulsesLocked(context, scope)
        if (existing.none { it.id == pulse.id }) {
            writeArray(
                atomic(context, scope, "pulses.json"),
                (existing + pulse).takeLast(MAX_PULSES).map(PreSemanticPulse::toJson)
            )
        }
    }

    fun pulses(context: Context, scope: CompanionScope): List<PreSemanticPulse> = synchronized(lock) {
        readPulsesLocked(context, scope)
    }

    fun pulse(context: Context, scope: CompanionScope, pulseId: String): PreSemanticPulse? = synchronized(lock) {
        readPulsesLocked(context, scope).firstOrNull { it.id == pulseId }
    }

    fun pendingPulses(context: Context, scope: CompanionScope, limit: Int = 8): List<PreSemanticPulse> =
        synchronized(lock) {
            val interpreted = readInterpretationsLocked(context, scope).mapTo(HashSet()) { it.pulseId }
            readPulsesLocked(context, scope)
                .asReversed()
                .filterNot { it.id in interpreted }
                .take(limit.coerceIn(0, MAX_PULSES))
        }

    fun appendInterpretation(
        context: Context,
        scope: CompanionScope,
        interpretation: ImpulseInterpretation
    ) = synchronized(lock) {
        val existing = readInterpretationsLocked(context, scope)
        if (existing.none { it.id == interpretation.id }) {
            writeArray(
                atomic(context, scope, "interpretations.json"),
                (existing + interpretation).takeLast(MAX_INTERPRETATIONS)
                    .map(ImpulseInterpretation::toJson)
            )
        }
    }

    fun interpretations(context: Context, scope: CompanionScope): List<ImpulseInterpretation> =
        synchronized(lock) { readInterpretationsLocked(context, scope) }

    fun latestInterpretation(
        context: Context,
        scope: CompanionScope,
        pulseId: String
    ): ImpulseInterpretation? = synchronized(lock) {
        readInterpretationsLocked(context, scope)
            .filter { it.pulseId == pulseId }
            .maxByOrNull { it.version }
    }

    fun unresolvedInterpretations(
        context: Context,
        scope: CompanionScope,
        limit: Int = 12
    ): List<ImpulseInterpretation> = synchronized(lock) {
        readInterpretationsLocked(context, scope)
            .groupBy { it.pulseId }
            .values
            .mapNotNull { versions -> versions.maxByOrNull { it.version } }
            .filter { it.status == InterpretationStatus.UNRESOLVED }
            .sortedByDescending { it.generatedAt }
            .take(limit.coerceIn(0, 24))
    }

    fun appendSleepCycle(context: Context, scope: CompanionScope, cycle: SleepCycle) = synchronized(lock) {
        val existing = readSleepCyclesLocked(context, scope)
        if (existing.none { it.id == cycle.id }) {
            writeArray(
                atomic(context, scope, "sleep_cycles.json"),
                (existing + cycle).takeLast(MAX_SLEEP_CYCLES).map(SleepCycle::toJson)
            )
        }
    }

    fun sleepCycles(context: Context, scope: CompanionScope): List<SleepCycle> = synchronized(lock) {
        readSleepCyclesLocked(context, scope)
    }

    private fun readPulsesLocked(context: Context, scope: CompanionScope): List<PreSemanticPulse> =
        readArray(atomic(context, scope, "pulses.json"), PreSemanticPulse::fromJson)

    private fun readInterpretationsLocked(
        context: Context,
        scope: CompanionScope
    ): List<ImpulseInterpretation> =
        readArray(atomic(context, scope, "interpretations.json"), ImpulseInterpretation::fromJson)

    private fun readSleepCyclesLocked(context: Context, scope: CompanionScope): List<SleepCycle> =
        readArray(atomic(context, scope, "sleep_cycles.json"), SleepCycle::fromJson)

    private fun <T> readArray(atomic: AtomicFile, parser: (JSONObject) -> T?): List<T> {
        val raw = readText(atomic) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let(parser)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun readText(atomic: AtomicFile): String? {
        if (!atomic.baseFile.isFile) return null
        return runCatching {
            atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
    }

    private fun writeArray(atomic: AtomicFile, items: List<JSONObject>) {
        writeText(atomic, JSONArray(items).toString())
    }

    private fun writeText(atomic: AtomicFile, text: String) {
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun atomic(context: Context, scope: CompanionScope, name: String): AtomicFile {
        val dir = File(CompanionWorkspace.privateRoot(context, scope.companionId), "enuman")
            .apply { mkdirs() }
        return AtomicFile(File(dir, name))
    }
}
