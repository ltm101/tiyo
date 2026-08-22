package com.koyo.screenwarden

import com.koyo.screenwarden.presence.ExternalSharePolicy
import com.koyo.screenwarden.presence.PresenceAttachment
import com.koyo.screenwarden.presence.PresenceChannel
import com.koyo.screenwarden.presence.PresenceChannelRegistry
import com.koyo.screenwarden.presence.PresenceDirection
import com.koyo.screenwarden.presence.PresenceEvent
import com.koyo.screenwarden.presence.PresenceModality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceEventTest {
    @Test
    fun eventRoundTripsWithoutTemporaryUris() {
        val original = PresenceEvent(
            id = "share_123",
            channel = PresenceChannel.DOUYIN,
            direction = PresenceDirection.TO_COMPANION,
            modality = PresenceModality.COMPOSITE,
            sourcePackage = "COM.SS.Android.UGC.Aweme!",
            sourceLabel = "抖音\n",
            text = "看看这个\u0000视频",
            attachments = listOf(
                PresenceAttachment(
                    privatePath = "C:/private/presence-inbox/video.mp4",
                    displayName = "video.mp4\n",
                    mimeType = "Video/MP4;bad",
                    sizeBytes = 42
                )
            ),
            explicitUserAction = true,
            occurredAt = 1234L
        )

        val restored = PresenceEvent.fromJson(original.toJson())!!

        assertEquals("share_123", restored.id)
        assertEquals(PresenceChannel.DOUYIN, restored.channel)
        assertEquals("com.ss.android.ugc.aweme", restored.sourcePackage)
        assertEquals("抖音", restored.sourceLabel)
        assertEquals("看看这个视频", restored.text)
        assertEquals("video.mp4", restored.attachments.single().displayName)
        assertEquals("video/mp4bad", restored.attachments.single().mimeType)
        assertTrue(restored.explicitUserAction)
        assertFalse(restored.toJson().toString().contains("content://"))
    }

    @Test
    fun malformedEventIsRejected() {
        val json = PresenceEvent(
            id = "valid",
            channel = PresenceChannel.SYSTEM_SHARE,
            direction = PresenceDirection.TO_COMPANION,
            modality = PresenceModality.TEXT
        ).toJson().put("id", "contains spaces")

        assertNull(PresenceEvent.fromJson(json))
    }

    @Test
    fun sharePolicyCombinesSubjectAndClassifiesMedia() {
        assertEquals("标题\n正文", ExternalSharePolicy.normalizedText("标题", "正文"))
        assertEquals(
            PresenceModality.COMPOSITE,
            ExternalSharePolicy.modality(listOf("image/png"), hasText = true)
        )
        assertEquals(
            PresenceModality.VIDEO,
            ExternalSharePolicy.modality(listOf("video/mp4"), hasText = false)
        )
        assertEquals("危险_名字_.png", ExternalSharePolicy.safeFileName("危险/名字?.png", "附件"))
    }

    @Test
    fun knownPackagesResolveToPlatformAdapters() {
        val douyin = PresenceChannelRegistry.forPackage("com.ss.android.ugc.aweme")
        val wechat = PresenceChannelRegistry.forPackage("com.tencent.mm")

        assertEquals(PresenceChannel.DOUYIN, douyin?.channel)
        assertEquals(PresenceChannel.WECHAT, wechat?.channel)
        assertNull(PresenceChannelRegistry.forPackage("unknown.app"))
    }
}
