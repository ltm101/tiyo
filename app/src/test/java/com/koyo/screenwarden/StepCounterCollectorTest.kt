package com.koyo.screenwarden

import org.junit.Assert.assertEquals
import org.junit.Test

class StepCounterCollectorTest {
    @Test
    fun repeatedReadsOnlyAddNewSteps() {
        val start = advanceStepProgress(null, 10_000)
        val first = advanceStepProgress(start, 10_120)
        val second = advanceStepProgress(first, 10_150)

        assertEquals(120, first.todaySteps)
        assertEquals(150, second.todaySteps)
    }

    @Test
    fun rebootKeepsTodayStepsAndStartsFromNewBaseline() {
        val beforeReboot = StepProgress(10_000, 0, 10_150, 150)
        val rebooted = advanceStepProgress(beforeReboot, 8)
        val afterWalking = advanceStepProgress(rebooted, 28)

        assertEquals(150, rebooted.todaySteps)
        assertEquals(170, afterWalking.todaySteps)
    }
}
