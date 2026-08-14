package com.koyo.screenwarden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class MemoryShelfCoordinatorTest {
    @Test
    fun naturalGoodnightTriggersSettlement() {
        assertTrue(GoodnightMessageClassifier.isGoodnight("晚安"))
        assertTrue(GoodnightMessageClassifier.isGoodnight("我先睡啦，明天见"))
    }

    @Test
    fun quotedOrNegativeGoodnightDoesNotTrigger() {
        assertFalse(GoodnightMessageClassifier.isGoodnight("帮我生成一段晚安文案"))
        assertFalse(GoodnightMessageClassifier.isGoodnight("你怎么还不睡"))
        assertFalse(GoodnightMessageClassifier.isGoodnight("今天不睡了"))
    }

    @Test
    fun beforeFiveAmBelongsToPreviousLifeDay() {
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.AUGUST, 9, 2, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }

        assertEquals("2026-08-08", MemoryDayKey.from(calendar.timeInMillis))
    }

    @Test
    fun retentionKeepsRecentAndArchivesOlderWithoutLoss() {
        val entries = (1..6).map { day ->
            MemoryShelfStore.Entry(
                date = "2026-08-0$day",
                objectId = "glass_orb",
                summary = "第${day}天",
                mood = "平静"
            )
        }
        val result = MemoryShelfRetentionPolicy.partition(entries, recentLimit = 3)

        assertEquals(listOf("2026-08-01", "2026-08-02", "2026-08-03"), result.archived.map { it.date })
        assertEquals(listOf("2026-08-04", "2026-08-05", "2026-08-06"), result.recent.map { it.date })
        assertEquals(6, result.archived.size + result.recent.size)
    }
}
