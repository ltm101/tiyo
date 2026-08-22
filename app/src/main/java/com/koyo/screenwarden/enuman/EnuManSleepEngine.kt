package com.koyo.screenwarden.enuman

enum class SleepDecision {
    NONE,
    SHORT_REFLECTION,
    DEEP_SLEEP
}

/** Pure rhythm and bounded-plasticity rules. */
object EnuManSleepEngine {
    const val MAX_DELTA_PER_DEEP_SLEEP = 0.03

    fun decide(
        state: EnuManState,
        unresolvedCount: Int,
        now: Long,
        lastMeaningfulEventAt: Long,
        userResting: Boolean
    ): SleepDecision {
        if (unresolvedCount <= 0) return SleepDecision.NONE
        val sinceDeep = now - state.lastDeepSleepAt
        val deepAllowed = state.lastDeepSleepAt == 0L || sinceDeep >= MIN_DEEP_SLEEP_GAP_MS
        val overloaded = state.sleepPressure >= 1.10 ||
            state.cognitiveLoad >= 1.30 || unresolvedCount >= 10
        val synchronizedRest = userResting && state.sleepPressure >= 0.50 && unresolvedCount >= 2
        if (deepAllowed && (overloaded || synchronizedRest)) return SleepDecision.DEEP_SLEEP

        val quietFor = now - lastMeaningfulEventAt
        if (quietFor >= SHORT_REFLECTION_IDLE_MS &&
            (state.cognitiveLoad >= 0.20 || unresolvedCount >= 3)
        ) return SleepDecision.SHORT_REFLECTION

        return SleepDecision.NONE
    }

    fun consolidate(
        state: EnuManState,
        kind: ReflectionKind,
        proposedPlasticity: Map<String, Double>,
        completedAt: Long
    ): Pair<EnuManState, Map<String, Double>> {
        if (kind != ReflectionKind.DEEP_SLEEP) {
            return state.copy(cognitiveLoad = (state.cognitiveLoad * 0.82).coerceAtLeast(0.0)) to emptyMap()
        }

        val applied = proposedPlasticity.entries
            .asSequence()
            .filter { it.key.isNotBlank() }
            .take(EnuManState.MAX_ASSOCIATIONS)
            .associate { (rawKey, proposed) ->
                val key = rawKey.take(EnuManState.MAX_ASSOCIATION_KEY_CHARS)
                key to proposed.finiteOr(0.0)
                    .coerceIn(-MAX_DELTA_PER_DEEP_SLEEP, MAX_DELTA_PER_DEEP_SLEEP)
            }
        val learned = state.learnedAssociations.toMutableMap()
        applied.forEach { (key, delta) ->
            learned[key] = ((learned[key] ?: 0.0) + delta)
                .coerceIn(-EnuManState.MAX_LEARNED_BIAS, EnuManState.MAX_LEARNED_BIAS)
        }
        return state.copy(
            cognitiveLoad = (state.cognitiveLoad * 0.35).coerceAtLeast(0.0),
            sleepPressure = (state.sleepPressure * 0.18).coerceAtLeast(0.0),
            lastDeepSleepAt = completedAt,
            learnedAssociations = learned.entries.toList().takeLast(EnuManState.MAX_ASSOCIATIONS)
                .associate { it.toPair() }
        ) to applied
    }

    private const val SHORT_REFLECTION_IDLE_MS = 30 * 60_000L
    private const val MIN_DEEP_SLEEP_GAP_MS = 8 * 60 * 60_000L
}
