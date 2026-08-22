package com.koyo.screenwarden

import com.koyo.screenwarden.enuman.DriveCell
import com.koyo.screenwarden.enuman.EnuManDrive
import com.koyo.screenwarden.enuman.EnuManPulseEngine
import com.koyo.screenwarden.enuman.EnuManSignal
import com.koyo.screenwarden.enuman.EnuManState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnuManPulseEngineTest {
    private val engine = EnuManPulseEngine()
    private val start = 1_800_000_000_000L

    @Test
    fun thresholdCrossingCreatesPulseNotAnAction() {
        val signal = EnuManSignal(
            id = "shared_image",
            occurredAt = start,
            source = "presence",
            excitation = mapOf(EnuManDrive.CURIOSITY to 1.2),
            causeRefs = listOf("presence_1")
        )

        val result = engine.step(EnuManState.initial(start), signal, start)

        assertNotNull(result.pulse)
        assertTrue(result.pulse!!.activations.containsKey(EnuManDrive.CURIOSITY))
        assertEquals(listOf("presence_1"), result.pulse!!.causeRefs)
        assertTrue(result.pulse!!.toJson().keys().asSequence().none { it.contains("action", ignoreCase = true) })
    }

    @Test
    fun sameSignalDependsOnInternalState() {
        val signal = EnuManSignal(
            id = "same",
            occurredAt = start,
            source = "test",
            excitation = mapOf(EnuManDrive.COHERENCE to 0.35)
        )
        val quiet = EnuManState.initial(start)
        val primed = quiet.copy(
            drives = quiet.drives + (
                EnuManDrive.COHERENCE to DriveCell(
                    potential = 0.75,
                    adaptiveThreshold = 1.0,
                    lastUpdatedAt = start
                )
                )
        )

        assertNull(engine.step(quiet, signal, start).pulse)
        assertNotNull(engine.step(primed, signal, start).pulse)
    }

    @Test
    fun inhibitionCanPreventFiring() {
        val base = EnuManState.initial(start).let { state ->
            state.copy(
                drives = state.drives + (
                    EnuManDrive.CONNECTION to DriveCell(
                        potential = 0.85,
                        adaptiveThreshold = 1.0,
                        lastUpdatedAt = start
                    )
                    )
            )
        }
        val excitationOnly = EnuManSignal(
            id = "excite",
            occurredAt = start,
            source = "test",
            excitation = mapOf(EnuManDrive.CONNECTION to 0.30)
        )
        val inhibited = excitationOnly.copy(
            id = "inhibited",
            inhibition = mapOf(EnuManDrive.CONNECTION to 0.40)
        )

        assertNotNull(engine.step(base, excitationOnly, start).pulse)
        assertNull(engine.step(base, inhibited, start).pulse)
    }

    @Test
    fun refractoryPeriodSuppressesImmediateRefire() {
        val signal = EnuManSignal(
            id = "first",
            occurredAt = start,
            source = "test",
            excitation = mapOf(EnuManDrive.CURIOSITY to 1.4)
        )
        val first = engine.step(EnuManState.initial(start), signal, start)
        assertNotNull(first.pulse)

        val second = engine.step(
            first.state,
            signal.copy(id = "second", excitation = mapOf(EnuManDrive.CURIOSITY to 2.0)),
            start + 1_000L
        )

        assertNull(second.pulse)
    }

    @Test
    fun intrinsicCurrentCanCreatePulseWithoutExternalEvent() {
        val state = EnuManState.initial(start)

        val result = engine.step(state, signal = null, now = start + 24 * 3_600_000L)

        assertNotNull(result.pulse)
        assertTrue(result.pulse!!.activations.containsKey(EnuManDrive.CONNECTION))
        assertTrue(result.pulse!!.causeRefs.isEmpty())
    }

    @Test
    fun stateSurvivesJsonRoundTrip() {
        val initial = EnuManState.initial(start)
        val evolved = engine.step(
            initial,
            EnuManSignal(
                id = "persisted_signal",
                occurredAt = start + 3_600_000L,
                source = "test",
                excitation = mapOf(EnuManDrive.AUTONOMY to 0.2)
            ),
            start + 3_600_000L
        ).state.copy(learnedAssociations = mapOf("channel:SYSTEM_SHARE" to 0.03))

        val restored = EnuManState.fromJson(evolved.toJson(), start)

        assertEquals(evolved, restored)
    }
}
