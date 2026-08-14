package com.koyo.screenwarden.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviorEpisodeReducerTest {
    private val start = 1_000_000L

    @Test
    fun shortScreenUseStaysLocalAndSilent() {
        val opened = BehaviorEpisodeReducer.reduce(
            BehaviorEpisodeState(),
            TiyoEvent(TiyoEventType.SCREEN_ON, "亮屏", occurredAt = start)
        )
        val closed = BehaviorEpisodeReducer.reduce(
            opened.state,
            TiyoEvent(TiyoEventType.SCREEN_OFF, "熄屏", occurredAt = start + 5 * 60_000L)
        )

        assertTrue(opened.events.isEmpty())
        assertTrue(closed.events.isEmpty())
        assertEquals(5, closed.state.recentScreenMinutes)
    }

    @Test
    fun continuousScreenUseBecomesOneEpisode() {
        val opened = BehaviorEpisodeReducer.reduce(
            BehaviorEpisodeState(),
            TiyoEvent(TiyoEventType.SCREEN_ON, "亮屏", occurredAt = start)
        )
        val closed = BehaviorEpisodeReducer.reduce(
            opened.state,
            TiyoEvent(TiyoEventType.SCREEN_OFF, "熄屏", occurredAt = start + 35 * 60_000L)
        )

        assertEquals(1, closed.events.size)
        assertEquals(TiyoEventType.SCREEN_SESSION, closed.events.single().type)
        assertEquals("screen_wellbeing:continuous", closed.events.single().topicKey)
    }

    @Test
    fun heartbeatDoesNotRepeatSameScreenEpisode() {
        val opened = BehaviorEpisodeReducer.reduce(
            BehaviorEpisodeState(),
            TiyoEvent(TiyoEventType.SCREEN_ON, "亮屏", occurredAt = start)
        )
        val first = BehaviorEpisodeReducer.reduce(
            opened.state,
            TiyoEvent(TiyoEventType.TIME_ANCHOR, "心跳", occurredAt = start + 31 * 60_000L)
        )
        val second = BehaviorEpisodeReducer.reduce(
            first.state,
            TiyoEvent(TiyoEventType.TIME_ANCHOR, "心跳", occurredAt = start + 40 * 60_000L)
        )

        assertEquals(1, first.events.count { it.type == TiyoEventType.SCREEN_SESSION })
        assertEquals(0, second.events.count { it.type == TiyoEventType.SCREEN_SESSION })
        assertEquals(1, second.events.count { it.type == TiyoEventType.TIME_ANCHOR })
    }

    @Test
    fun bootHeartbeatClearsStaleScreenSession() {
        val result = BehaviorEpisodeReducer.reduce(
            BehaviorEpisodeState(screenOnAt = start),
            TiyoEvent(TiyoEventType.TIME_ANCHOR, "设备开机后的兜底心跳", occurredAt = start + 40 * 60_000L)
        )

        assertEquals(0L, result.state.screenOnAt)
        assertEquals(0, result.events.count { it.type == TiyoEventType.SCREEN_SESSION })
    }

    @Test
    fun notificationsNeedABurstBeforeTheyReachDecisionLayer() {
        val first = BehaviorEpisodeReducer.reduce(
            BehaviorEpisodeState(),
            TiyoEvent(TiyoEventType.NOTIFICATION, "通知", occurredAt = start)
        )
        val second = BehaviorEpisodeReducer.reduce(
            first.state,
            TiyoEvent(TiyoEventType.NOTIFICATION, "通知", occurredAt = start + 60_000L)
        )
        val third = BehaviorEpisodeReducer.reduce(
            second.state,
            TiyoEvent(
                TiyoEventType.NOTIFICATION,
                "通知",
                occurredAt = start + 2 * 60_000L,
                sensitiveContext = "不能落盘的正文"
            )
        )

        assertTrue(first.events.isEmpty())
        assertTrue(second.events.isEmpty())
        assertEquals(TiyoEventType.NOTIFICATION_BURST, third.events.single().type)
        assertTrue(!third.events.single().toJson().toString().contains("不能落盘"))
    }
}
