package com.koyo.screenwarden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionPerceptionPolicyTest {
    @Test
    fun enteringTargetSchedulesOneSettledCapture() {
        val decision = CompanionCapturePolicy.onForegroundEvent(
            CompanionCaptureState(),
            "com.tencent.tmgp.sgame",
            now = 10_000L,
            contentChanged = false
        )

        assertEquals(CompanionTargets.WANGZHE, decision.target)
        assertEquals(CompanionTargets.WANGZHE.settleDelayMs, decision.captureAfterMs)
    }

    @Test
    fun repeatedContentInsideIntervalDoesNotCaptureAgain() {
        val captured = CompanionCapturePolicy.markCaptured(
            CompanionCaptureState(activePackage = "com.ss.android.ugc.aweme", enteredAt = 1_000L),
            CompanionTargets.DOUYIN,
            now = 10_000L
        )
        val decision = CompanionCapturePolicy.onForegroundEvent(
            captured,
            "com.ss.android.ugc.aweme",
            now = 20_000L,
            contentChanged = true
        )

        assertEquals(-1L, decision.captureAfterMs)
    }

    @Test
    fun leavingTargetClearsActiveSession() {
        val decision = CompanionCapturePolicy.onForegroundEvent(
            CompanionCaptureState(activePackage = "com.ss.android.ugc.aweme", enteredAt = 1_000L),
            "com.bbk.launcher2",
            now = 2_000L,
            contentChanged = false
        )

        assertTrue(decision.leftTarget)
        assertTrue(decision.state.activePackage.isBlank())
    }

    @Test
    fun visionParserRequiresExplicitSafetyAndUsefulness() {
        val parsed = CompanionVisionDecisionParser.parse(
            "{\"useful\":true,\"safe_to_discuss\":true,\"should_respond\":false," +
                "\"moment\":\"result\",\"summary\":\"对局结算页\"}"
        )

        assertTrue(parsed?.useful == true)
        assertTrue(parsed?.safeToDiscuss == true)
        assertFalse(parsed?.shouldRespond == true)
    }
}
