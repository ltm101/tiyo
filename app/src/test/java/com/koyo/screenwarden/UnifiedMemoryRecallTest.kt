package com.koyo.screenwarden

import com.koyo.screenwarden.enuman.experience.ExperienceKind
import com.koyo.screenwarden.enuman.experience.ExperiencePrivacy
import com.koyo.screenwarden.enuman.experience.ExperienceRecord
import com.koyo.screenwarden.enuman.experience.ExperienceLedger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UnifiedMemoryRecallTest {

    @Test
    fun mergePrefersMemoryWhenQueryMatchesMemory() {
        val memory = listOf(
            UnifiedMemoryRecall.Item("memory", "m1", title = "用户偏好", summary = "喜欢拿铁")
        )
        val shelf = listOf(
            UnifiedMemoryRecall.Item("shelf", "s1", title = "咖啡", summary = "今天喝了咖啡")
        )
        val experience = listOf(
            UnifiedMemoryRecall.Item("experience", "e1", title = "presence", summary = "咖啡")
        )

        val merged = UnifiedMemoryRecall.merge(experience, memory, shelf, limit = 10)

        assertEquals("memory", merged.first().source)
        assertTrue(merged.map { it.id }.containsAll(listOf("m1", "s1", "e1")))
    }

    @Test
    fun mergeRecentSortsByOccurredAtDescending() {
        val experience = listOf(
            UnifiedMemoryRecall.Item("experience", "e_old", occurredAt = 100L),
            UnifiedMemoryRecall.Item("experience", "e_new", occurredAt = 300L)
        )
        val shelf = listOf(
            UnifiedMemoryRecall.Item("shelf", "s_mid", occurredAt = 200L)
        )

        val merged = UnifiedMemoryRecall.merge(experience, emptyList(), shelf, limit = 10)

        assertEquals(listOf("e_new", "s_mid", "e_old"), merged.map { it.id })
    }

    @Test
    fun experienceRecordRoundTripKeepsMultiChannelFields() {
        val record = ExperienceRecord(
            id = "exp_douyin_1",
            companionId = "koyo",
            kind = ExperienceKind.PRESENCE,
            occurredAt = 1_800_000_000_000L,
            recordedAt = 1_800_000_000_100L,
            sourceChannel = "DOUYIN",
            modality = "VIDEO",
            explicitUserAction = true,
            privacyClass = ExperiencePrivacy.EXPLICIT,
            summary = "视频分享",
            conversationKey = "conv_123",
            sourceRef = "douyin_msg_456"
        )

        val restored = ExperienceRecord.fromJson(record.toJson())

        assertEquals("DOUYIN", restored!!.sourceChannel)
        assertEquals("VIDEO", restored.modality)
        assertEquals("conv_123", restored.conversationKey)
        assertEquals("douyin_msg_456", restored.sourceRef)
        assertEquals("视频分享", restored.summary)
    }
}
