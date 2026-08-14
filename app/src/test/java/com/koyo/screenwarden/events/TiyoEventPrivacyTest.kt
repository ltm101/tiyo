package com.koyo.screenwarden.events

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TiyoEventPrivacyTest {
    @Test
    fun serializationNeverContainsNotificationBody() {
        val event = TiyoEvent(
            type = TiyoEventType.NOTIFICATION,
            summary = "微信收到一条新通知",
            sensitiveContext = "这是不能落盘的通知正文"
        )
        val serialized = event.toJson().toString()
        assertFalse(serialized.contains("不能落盘"))
        assertNull(TiyoEvent.fromJson(event.toJson())?.sensitiveContext)
    }

    @Test
    fun serializationNeverContainsCompanionFrameSummary() {
        val event = TiyoEvent(
            type = TiyoEventType.COMPANION_CONTEXT,
            summary = "陪伴会话遇到一个画面节点",
            topicKey = "companion:wangzhe:result",
            sensitiveContext = "不能落盘的战绩与画面摘要"
        )
        val serialized = event.toJson().toString()
        assertFalse(serialized.contains("战绩"))
        assertNull(TiyoEvent.fromJson(event.toJson())?.sensitiveContext)
    }
}
