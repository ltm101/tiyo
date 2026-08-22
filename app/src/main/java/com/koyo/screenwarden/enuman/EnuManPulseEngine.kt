package com.koyo.screenwarden.enuman

import kotlin.math.exp
import kotlin.math.ln
import java.util.UUID

/**
 * Deterministic leaky integrate-and-fire core.
 *
 * Its only observable output is a pre-semantic pulse. This class deliberately
 * has no Android dependency and no action or messaging capability.
 */
class EnuManPulseEngine(
    private val configs: Map<EnuManDrive, DriveConfig> = defaultConfigs()
) {
    data class DriveConfig(
        val baseThreshold: Double,
        val leakHalfLifeHours: Double,
        val intrinsicCurrentPerHour: Double,
        val refractoryMs: Long,
        val adaptationIncrement: Double
    )

    data class StepResult(
        val state: EnuManState,
        val pulse: PreSemanticPulse?
    )

    fun step(
        previous: EnuManState,
        signal: EnuManSignal? = null,
        now: Long = signal?.occurredAt ?: previous.lastTickAt
    ): StepResult {
        val safeNow = maxOf(now, previous.lastTickAt)
        val elapsedHours = ((safeNow - previous.lastTickAt).coerceAtLeast(0L) / HOUR_MS.toDouble())
            .coerceAtMost(MAX_ELAPSED_HOURS)

        val evolved = EnuManDrive.entries.associateWith { drive ->
            val config = configs.getValue(drive)
            val old = previous.drives[drive] ?: DriveCell(
                adaptiveThreshold = config.baseThreshold,
                lastUpdatedAt = previous.lastTickAt
            )
            val potential = decay(old.potential, elapsedHours, config.leakHalfLifeHours) +
                config.intrinsicCurrentPerHour * elapsedHours +
                learnedCurrent(previous, drive) * elapsedHours +
                (signal?.excitation?.get(drive) ?: 0.0) * (signal?.salience ?: 1.0) -
                (signal?.inhibition?.get(drive) ?: 0.0) * (signal?.salience ?: 1.0)
            val threshold = config.baseThreshold + decay(
                old.adaptiveThreshold - config.baseThreshold,
                elapsedHours,
                THRESHOLD_ADAPTATION_HALF_LIFE_HOURS
            )
            old.copy(
                potential = potential.coerceIn(0.0, MAX_POTENTIAL),
                adaptiveThreshold = threshold.coerceIn(config.baseThreshold, MAX_THRESHOLD),
                lastUpdatedAt = safeNow
            )
        }.toMutableMap()

        val firing = EnuManDrive.entries.filter { drive ->
            val cell = evolved.getValue(drive)
            cell.refractoryUntil <= safeNow && cell.potential >= cell.adaptiveThreshold
        }

        val pulse = if (firing.isEmpty()) {
            null
        } else {
            val activations = firing.associateWith { drive ->
                val cell = evolved.getValue(drive)
                (cell.potential / cell.adaptiveThreshold).coerceIn(1.0, 4.0)
            }
            PreSemanticPulse(
                id = deterministicPulseId(safeNow, activations, signal?.causeRefs.orEmpty()),
                occurredAt = safeNow,
                activations = activations,
                conflicts = conflictsFor(evolved),
                causeRefs = signal?.causeRefs.orEmpty().takeLast(PreSemanticPulse.MAX_CAUSE_REFS)
            )
        }

        firing.forEach { drive ->
            val config = configs.getValue(drive)
            val cell = evolved.getValue(drive)
            evolved[drive] = cell.copy(
                potential = (cell.potential - cell.adaptiveThreshold * RESET_FRACTION).coerceAtLeast(0.0),
                adaptiveThreshold = (cell.adaptiveThreshold + config.adaptationIncrement)
                    .coerceAtMost(MAX_THRESHOLD),
                refractoryUntil = safeNow + config.refractoryMs
            )
        }

        val signalLoad = signal?.let {
            (it.excitation.values.sum() + it.inhibition.values.sum()) * it.salience * 0.10
        } ?: 0.0
        val conflictLoad = pulse?.conflicts?.sumOf { it.strength }?.times(0.08) ?: 0.0
        val cognitiveLoad = (
            decay(previous.cognitiveLoad, elapsedHours, COGNITIVE_LOAD_HALF_LIFE_HOURS) +
                signalLoad + conflictLoad
            ).coerceIn(0.0, 2.0)
        val sleepPressure = (
            decay(previous.sleepPressure, elapsedHours, SLEEP_PRESSURE_HALF_LIFE_HOURS) +
                elapsedHours * BASE_SLEEP_PRESSURE_PER_HOUR +
                cognitiveLoad * elapsedHours * LOAD_SLEEP_PRESSURE_PER_HOUR
            ).coerceIn(0.0, 2.0)
        val recentSignals = if (signal == null || signal.id in previous.recentSignalIds) {
            previous.recentSignalIds
        } else {
            (previous.recentSignalIds + signal.id).takeLast(EnuManState.MAX_RECENT_SIGNALS)
        }

        return StepResult(
            state = previous.copy(
                drives = evolved,
                cognitiveLoad = cognitiveLoad,
                sleepPressure = sleepPressure,
                lastTickAt = safeNow,
                recentSignalIds = recentSignals
            ),
            pulse = pulse
        )
    }

    private fun conflictsFor(cells: Map<EnuManDrive, DriveCell>): List<DriveConflict> =
        CONFLICT_PAIRS.mapNotNull { (first, second) ->
            val firstCell = cells.getValue(first)
            val secondCell = cells.getValue(second)
            val firstRatio = firstCell.potential / firstCell.adaptiveThreshold
            val secondRatio = secondCell.potential / secondCell.adaptiveThreshold
            val strength = minOf(firstRatio, secondRatio)
            if (strength >= CONFLICT_RATIO) DriveConflict(first, second, strength.coerceAtMost(2.0))
            else null
        }

    private fun decay(value: Double, elapsedHours: Double, halfLifeHours: Double): Double {
        if (value == 0.0 || elapsedHours <= 0.0) return value
        return value * exp(-ln(2.0) * elapsedHours / halfLifeHours)
    }

    private fun learnedCurrent(state: EnuManState, drive: EnuManDrive): Double =
        (state.learnedAssociations["drive:${drive.name}"] ?: 0.0) * LEARNED_CURRENT_SCALE

    private fun deterministicPulseId(
        at: Long,
        activations: Map<EnuManDrive, Double>,
        causes: List<String>
    ): String {
        val seed = buildString {
            append(at)
            EnuManDrive.entries.forEach { drive ->
                activations[drive]?.let { value -> append('|').append(drive.name).append('=').append(value) }
            }
            causes.takeLast(PreSemanticPulse.MAX_CAUSE_REFS).forEach { append('|').append(it) }
        }
        return UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()
    }

    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val MAX_ELAPSED_HOURS = 72.0
        private const val MAX_POTENTIAL = 4.0
        private const val MAX_THRESHOLD = 2.5
        private const val RESET_FRACTION = 0.92
        private const val THRESHOLD_ADAPTATION_HALF_LIFE_HOURS = 12.0
        private const val COGNITIVE_LOAD_HALF_LIFE_HOURS = 4.0
        private const val SLEEP_PRESSURE_HALF_LIFE_HOURS = 30.0
        private const val BASE_SLEEP_PRESSURE_PER_HOUR = 0.012
        private const val LOAD_SLEEP_PRESSURE_PER_HOUR = 0.008
        private const val CONFLICT_RATIO = 0.55
        private const val LEARNED_CURRENT_SCALE = 0.02

        private val CONFLICT_PAIRS = listOf(
            EnuManDrive.CONNECTION to EnuManDrive.AUTONOMY,
            EnuManDrive.CURIOSITY to EnuManDrive.SAFETY,
            EnuManDrive.COHERENCE to EnuManDrive.REST
        )

        fun defaultConfigs(): Map<EnuManDrive, DriveConfig> = mapOf(
            EnuManDrive.CONNECTION to DriveConfig(1.0, 30.0, 0.075, 90 * 60_000L, 0.18),
            EnuManDrive.CURIOSITY to DriveConfig(1.0, 24.0, 0.045, 60 * 60_000L, 0.16),
            EnuManDrive.SAFETY to DriveConfig(1.0, 5.0, 0.002, 45 * 60_000L, 0.22),
            EnuManDrive.AUTONOMY to DriveConfig(1.0, 20.0, 0.018, 75 * 60_000L, 0.16),
            EnuManDrive.COHERENCE to DriveConfig(1.0, 16.0, 0.022, 75 * 60_000L, 0.16),
            EnuManDrive.REST to DriveConfig(1.0, 36.0, 0.025, 3 * 60 * 60_000L, 0.20)
        )
    }
}
