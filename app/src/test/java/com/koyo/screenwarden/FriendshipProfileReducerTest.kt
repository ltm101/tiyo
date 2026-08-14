package com.koyo.screenwarden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendshipProfileReducerTest {
    private val identity = FriendshipIdentity(
        key = "friend-key",
        packageName = "com.tencent.mm",
        platform = "微信",
        displayName = "小林"
    )

    @Test
    fun duplicateNotificationRoutesOnlyLearnOnce() {
        val first = FriendshipProfileReducer.observe(
            null, identity, "明天一起吃饭吗", 1_000L, claimAnalysis = false
        )
        val duplicate = FriendshipProfileReducer.observe(
            first.profile, identity, "明天一起吃饭吗", 20_000L, claimAnalysis = true
        )

        assertTrue(first.accepted)
        assertFalse(duplicate.accepted)
        assertEquals(1, duplicate.profile.incomingCount)
    }

    @Test
    fun repeatedExplicitFactRaisesConfidenceWithoutKeepingRawMessage() {
        val observed = FriendshipProfileReducer.observe(
            null, identity, "普通来信", 1_000L, claimAnalysis = false
        ).profile
        val first = FriendshipProfileReducer.applyDelta(
            observed,
            FriendshipProfileDelta(facts = listOf("明天要考试" to 14)),
            2_000L
        )
        val second = FriendshipProfileReducer.applyDelta(
            first,
            FriendshipProfileDelta(facts = listOf("明天要考试" to 14)),
            3_000L
        )

        assertEquals(1, second.facts.size)
        assertEquals(2, second.facts.single().evidenceCount)
        assertTrue(second.facts.single().confidence > first.facts.single().confidence)
    }

    @Test
    fun sensitiveFactsAreRejected() {
        val observed = FriendshipProfileReducer.observe(
            null, identity, "普通来信", 1_000L, claimAnalysis = false
        ).profile
        val updated = FriendshipProfileReducer.applyDelta(
            observed,
            FriendshipProfileDelta(facts = listOf("银行卡密码是123456" to 365)),
            2_000L
        )

        assertTrue(updated.facts.isEmpty())
    }

    @Test
    fun groupConversationNeverBecomesOnePersonsTraits() {
        val group = identity.copy(groupLike = true, displayName = "同学群")
        val observed = FriendshipProfileReducer.observe(
            null, group, "小张：哈哈明天见", 1_000L, claimAnalysis = true
        )

        assertTrue(observed.profile.groupLike)
        assertTrue(observed.profile.traitCounts.isEmpty())
        assertFalse(observed.shouldAnalyze)
    }
}
