package com.koyo.screenwarden

import com.koyo.screenwarden.enuman.EnuManSleepEngine
import com.koyo.screenwarden.enuman.EnuManState
import com.koyo.screenwarden.enuman.ReflectionKind
import com.koyo.screenwarden.enuman.SleepDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnuManSleepEngineTest {
    private val now = 1_800_000_000_000L

    @Test
    fun quietUnresolvedExperienceAllowsShortReflection() {
        val state = EnuManState.initial(now - 2 * 3_600_000L).copy(cognitiveLoad = 0.4)

        val decision = EnuManSleepEngine.decide(
            state = state,
            unresolvedCount = 1,
            now = now,
            lastMeaningfulEventAt = now - 40 * 60_000L,
            userResting = false
        )

        assertEquals(SleepDecision.SHORT_REFLECTION, decision)
    }

    @Test
    fun companionCanNeedDeepSleepWithoutUserRestSignal() {
        val state = EnuManState.initial(now - 12 * 3_600_000L).copy(
            cognitiveLoad = 1.4,
            sleepPressure = 1.2
        )

        assertEquals(
            SleepDecision.DEEP_SLEEP,
            EnuManSleepEngine.decide(state, 4, now, now - 60_000L, userResting = false)
        )
    }

    @Test
    fun shortReflectionCannotChangePlasticity() {
        val state = EnuManState.initial(now).copy(learnedAssociations = mapOf("drive:CURIOSITY" to 0.1))

        val (updated, applied) = EnuManSleepEngine.consolidate(
            state,
            ReflectionKind.SHORT_REFLECTION,
            mapOf("drive:CURIOSITY" to 1.0),
            now
        )

        assertEquals(state.learnedAssociations, updated.learnedAssociations)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun deepSleepPlasticityIsSlowAndGloballyBounded() {
        val state = EnuManState.initial(now).copy(
            learnedAssociations = mapOf(
                "positive" to 0.39,
                "negative" to -0.39
            )
        )

        val (updated, applied) = EnuManSleepEngine.consolidate(
            state,
            ReflectionKind.DEEP_SLEEP,
            mapOf("positive" to 8.0, "negative" to -8.0, "new" to 0.02),
            now
        )

        assertEquals(0.03, applied.getValue("positive"), 0.000001)
        assertEquals(-0.03, applied.getValue("negative"), 0.000001)
        assertEquals(0.40, updated.learnedAssociations.getValue("positive"), 0.000001)
        assertEquals(-0.40, updated.learnedAssociations.getValue("negative"), 0.000001)
        assertEquals(0.02, updated.learnedAssociations.getValue("new"), 0.000001)
    }
}
