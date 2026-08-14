package com.koyo.screenwarden.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveOpportunityPlannerTest {
    private val now = 100 * 60 * 60_000L

    @Test
    fun ordinaryScreenEventNeverCallsReflection() {
        val candidates = ProactiveOpportunityPlanner.candidates(
            listOf(TiyoEvent(TiyoEventType.SCREEN_ON, "普通亮屏", occurredAt = now)),
            environment()
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun chargingAloneIsNotAReasonToInterrupt() {
        val event = TiyoEvent(
            TiyoEventType.POWER_CONNECTED,
            "刚接上充电",
            occurredAt = now,
            topicKey = "rest_after_charge",
            expiresAt = now + 60 * 60_000L
        )
        val candidate = ProactiveOpportunityPlanner.candidates(listOf(event), environment()).single()
        val assessment = ProactiveOpportunityPlanner.assess(candidate, now, 0L)

        assertEquals("low_value", assessment.blockReason)
        assertNull(assessment.opportunity)
    }

    @Test
    fun chargingAfterLongUseCreatesANaturalRestWindow() {
        val event = TiyoEvent(
            TiyoEventType.POWER_CONNECTED,
            "刚接上充电",
            occurredAt = now,
            topicKey = "rest_after_charge",
            expiresAt = now + 60 * 60_000L
        )
        val candidate = ProactiveOpportunityPlanner.candidates(
            listOf(event),
            environment(
                lastUserMessageAt = now - 9 * 60 * 60_000L,
                recentScreenMinutes = 40,
                recentScreenEndedAt = now - 5 * 60_000L
            )
        ).single()
        val assessment = ProactiveOpportunityPlanner.assess(candidate, now, 0L)

        assertTrue(assessment.score >= ProactiveOpportunityPlanner.MIN_REFLECTION_SCORE)
        assertEquals("rest_after_charge", assessment.opportunity?.topicKey)
    }

    @Test
    fun threeThousandStepsAloneIsTooSmallToInterrupt() {
        val event = TiyoEvent(
            TiyoEventType.STEP_MILESTONE,
            "今天的步数刚达到3000步活动里程碑",
            occurredAt = now,
            topicKey = "movement_today",
            expiresAt = now + 4 * 60 * 60_000L
        )
        val candidate = ProactiveOpportunityPlanner.candidates(listOf(event), environment()).single()
        val assessment = ProactiveOpportunityPlanner.assess(candidate, now, 0L)

        assertEquals("low_value", assessment.blockReason)
        assertNull(assessment.opportunity)
    }

    @Test
    fun focusRequestSuppressesEveryNonEmergencyOpportunity() {
        val event = TiyoEvent(
            TiyoEventType.STEP_MILESTONE,
            "达到活动里程碑",
            occurredAt = now,
            topicKey = "movement_today",
            expiresAt = now + 60 * 60_000L
        )

        assertTrue(
            ProactiveOpportunityPlanner.candidates(
                listOf(event),
                environment(focusProtected = true)
            ).isEmpty()
        )
    }

    @Test
    fun sameTopicIsCooledDownIndependently() {
        val event = TiyoEvent(
            TiyoEventType.STEP_MILESTONE,
            "达到活动里程碑",
            occurredAt = now,
            topicKey = "movement_today",
            expiresAt = now + 4 * 60 * 60_000L
        )
        val candidate = ProactiveOpportunityPlanner.candidates(listOf(event), environment()).single()
        val assessment = ProactiveOpportunityPlanner.assess(
            candidate,
            now,
            topicLastSentAt = now - 2 * 60 * 60_000L
        )

        assertEquals("topic_cooldown", assessment.blockReason)
        assertNull(assessment.opportunity)
    }

    @Test
    fun oneUnansweredMessageMakesMarginalCelebrationStaySilent() {
        val event = TiyoEvent(
            TiyoEventType.STEP_MILESTONE,
            "达到活动里程碑",
            occurredAt = now,
            topicKey = "movement_today",
            expiresAt = now + 4 * 60 * 60_000L
        )
        val candidate = ProactiveOpportunityPlanner.candidates(
            listOf(event),
            environment(consecutiveNoReply = 1)
        ).single()
        val assessment = ProactiveOpportunityPlanner.assess(candidate, now, 0L)

        assertEquals("low_value", assessment.blockReason)
        assertNull(assessment.opportunity)
    }

    @Test
    fun companionshipNeverUsesOldConversationAsAnOpener() {
        val event = TiyoEvent(
            TiyoEventType.TIME_ANCHOR,
            "心跳",
            occurredAt = now,
            topicKey = "companionship_window",
            expiresAt = now + 60 * 60_000L
        )
        val candidate = ProactiveOpportunityPlanner.candidates(
            listOf(event),
            environment(lastUserMessageAt = now - 30 * 60 * 60_000L)
        ).single()

        assertTrue(!candidate.contextLine.contains("上次说到"))
        assertTrue(!candidate.contextLine.contains("要不要继续"))
        assertTrue(candidate.fallbackText.contains("想起你"))
    }

    @Test
    fun gameResultCanBecomeANaturalCompanionMoment() {
        val event = TiyoEvent(
            TiyoEventType.COMPANION_CONTEXT,
            "陪伴会话在王者荣耀里遇到一个经过筛选的画面节点",
            occurredAt = now,
            topicKey = "companion:wangzhe:result",
            expiresAt = now + 20 * 60_000L,
            sensitiveContext = "结算页显示胜利"
        )
        val candidate = ProactiveOpportunityPlanner.candidates(listOf(event), environment()).single()
        val assessment = ProactiveOpportunityPlanner.assess(candidate, now, 0L)

        assertEquals("companion:wangzhe:result", assessment.opportunity?.topicKey)
        assertTrue(assessment.score >= ProactiveOpportunityPlanner.MIN_REFLECTION_SCORE)
    }

    private fun environment(
        lastUserMessageAt: Long = now - 2 * 60 * 60_000L,
        focusProtected: Boolean = false,
        consecutiveNoReply: Int = 0,
        recentScreenMinutes: Int = 0,
        recentScreenEndedAt: Long = 0L
    ) = OpportunityEnvironment(
        now = now,
        hour = 20,
        lastUserMessageAt = lastUserMessageAt,
        focusProtected = focusProtected,
        consecutiveNoReply = consecutiveNoReply,
        recentScreenMinutes = recentScreenMinutes,
        recentScreenEndedAt = recentScreenEndedAt
    )
}
