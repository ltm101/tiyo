package com.koyo.screenwarden

import com.koyo.screenwarden.enuman.DriveReading
import com.koyo.screenwarden.enuman.EnuManDrive
import com.koyo.screenwarden.enuman.EnuManMindSnapshot
import com.koyo.screenwarden.enuman.EnuManPulseEngine
import com.koyo.screenwarden.enuman.EnuManReplayEngine
import com.koyo.screenwarden.enuman.EnuManSignal
import com.koyo.screenwarden.enuman.EnuManState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnuManMindKernelTest {
    private val start = 1_800_000_000_000L

    @Test
    fun sameReplayProducesSamePulseLineage() {
        val signals = listOf(
            EnuManSignal(
                id = "experience_a",
                occurredAt = start + 2 * 3_600_000L,
                source = "test",
                excitation = mapOf(EnuManDrive.CURIOSITY to 0.5)
            ),
            EnuManSignal(
                id = "experience_b",
                occurredAt = start + 9 * 3_600_000L,
                source = "test",
                excitation = mapOf(EnuManDrive.COHERENCE to 0.7),
                inhibition = mapOf(EnuManDrive.SAFETY to 0.1)
            )
        )
        val engine = EnuManReplayEngine()

        val first = engine.replay(EnuManState.initial(start), signals, start + 30 * 3_600_000L)
        val second = engine.replay(EnuManState.initial(start), signals, start + 30 * 3_600_000L)

        assertEquals(first.fingerprint(), second.fingerprint())
        assertEquals(first.pulses, second.pulses)
    }

    @Test
    fun intrinsicLifeContinuesAcrossSparseExecution() {
        val direct = EnuManPulseEngine().step(
            EnuManState.initial(start),
            signal = null,
            now = start + 24 * 3_600_000L
        )
        val replayed = EnuManReplayEngine().replay(
            EnuManState.initial(start),
            emptyList(),
            start + 24 * 3_600_000L
        )

        assertTrue(direct.pulse != null)
        assertTrue(replayed.pulses.isNotEmpty())
    }

    @Test
    fun expressionPolicyContainsBehaviorOnlyAndNoPrivateSemantics() {
        val policy = snapshot().toExpressionPolicyJson()
        val serialized = policy.toString()

        assertEquals("enuman_expression_v1", policy.getString("schema"))
        assertTrue(serialized.contains("follow_user_topic_only"))
        assertFalse(serialized.contains("dominant_tendencies"))
        assertFalse(serialized.contains("private_felt_meaning"))
        assertFalse(serialized.contains("candidate_desires"))
        assertFalse(serialized.contains("好奇"))
        assertFalse(serialized.contains("连接"))
    }

    @Test
    fun highLoadPolicyReducesInitiativeWithoutDisclosingWhy() {
        val highLoad = snapshot().copy(cognitiveLoad = 0.9, sleepPressure = 0.8)
        val directives = highLoad.toExpressionPolicyJson().getJSONArray("directives").toString()

        assertTrue(directives.contains("keep_response_concise"))
        assertTrue(directives.contains("avoid_unnecessary_follow_up"))
        assertEquals(0, highLoad.toExpressionPolicyJson().getInt("max_follow_up_questions"))
        assertFalse(directives.contains("sleep"))
        assertFalse(directives.contains("cognitive"))
    }

    private fun snapshot(): EnuManMindSnapshot {
        val readings = EnuManDrive.entries.associateWith { drive ->
            DriveReading(drive, 0.4, 1.0, 0.4, 0L)
        }
        return EnuManMindSnapshot(
            companionId = "koyo",
            capturedAt = start,
            drives = readings,
            dominantDrives = listOf(EnuManDrive.CURIOSITY),
            conflicts = emptyList(),
            latestInterpretation = null,
            unresolvedCount = 1,
            cognitiveLoad = 0.2,
            sleepPressure = 0.1,
            lastDeepSleepAt = 0L,
            pulseCount = 1,
            interpretationCount = 0,
            sleepCycleCount = 0
        )
    }
}
