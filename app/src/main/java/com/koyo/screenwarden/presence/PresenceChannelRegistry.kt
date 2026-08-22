package com.koyo.screenwarden.presence

/** 平台接入能力。这里描述真实可用的通道，不把“能感知”冒充成“能代发”。 */
enum class PresenceCapability {
    RECEIVE_TEXT,
    RECEIVE_MEDIA,
    SEND_TEXT,
    SEND_MEDIA,
    OBSERVE_CONTEXT,
    DRAFT_REPLY,
    OPEN_NATIVE_APP,
    NATIVE_IDENTITY,
    LIVE_VOICE,
    LIVE_CALL,
    REALTIME_CO_PRESENCE
}

enum class PresenceTransport {
    IN_APP,
    ANDROID_SHARE,
    ANDROID_ASSISTANT_ROLE,
    NOTIFICATION_LISTENER,
    ACCESSIBILITY_HANDOFF,
    DESKTOP_BRIDGE,
    OFFICIAL_PLATFORM_API,
    GAME_SDK
}

enum class PresenceAvailability {
    AVAILABLE,
    BRIDGE_READY,
    PLANNED
}

data class PresenceChannelAdapter(
    val channel: PresenceChannel,
    val displayName: String,
    val packageNames: Set<String>,
    val transports: Set<PresenceTransport>,
    val capabilities: Set<PresenceCapability>,
    val availability: PresenceAvailability,
    val level: PresenceAdapterLevel = PresenceAdapterLevel.GENERIC_SHARE
)

object PresenceChannelRegistry {
    val adapters: List<PresenceChannelAdapter> = listOf(
        PresenceChannelAdapter(
            channel = PresenceChannel.TIYO,
            displayName = "tiyo",
            packageNames = setOf("com.koyo.screenwarden"),
            transports = setOf(PresenceTransport.IN_APP),
            capabilities = PresenceCapability.entries.toSet(),
            availability = PresenceAvailability.AVAILABLE,
            level = PresenceAdapterLevel.NATIVE_ACCOUNT
        ),
        PresenceChannelAdapter(
            channel = PresenceChannel.SYSTEM_SHARE,
            displayName = "Android 分享",
            packageNames = emptySet(),
            transports = setOf(PresenceTransport.ANDROID_SHARE),
            capabilities = setOf(
                PresenceCapability.RECEIVE_TEXT,
                PresenceCapability.RECEIVE_MEDIA,
                PresenceCapability.OPEN_NATIVE_APP
            ),
            availability = PresenceAvailability.AVAILABLE,
            level = PresenceAdapterLevel.GENERIC_SHARE
        ),
        PresenceChannelAdapter(
            channel = PresenceChannel.SYSTEM_ASSISTANT,
            displayName = "系统助理",
            packageNames = emptySet(),
            transports = setOf(PresenceTransport.ANDROID_ASSISTANT_ROLE),
            capabilities = setOf(
                PresenceCapability.RECEIVE_TEXT,
                PresenceCapability.SEND_TEXT,
                PresenceCapability.LIVE_VOICE,
                PresenceCapability.OPEN_NATIVE_APP
            ),
            availability = PresenceAvailability.AVAILABLE,
            level = PresenceAdapterLevel.SYSTEM_CAPABILITY
        ),
        PresenceChannelAdapter(
            channel = PresenceChannel.WECHAT,
            displayName = "微信",
            packageNames = setOf("com.tencent.mm"),
            transports = setOf(
                PresenceTransport.OFFICIAL_PLATFORM_API,
                PresenceTransport.ANDROID_SHARE,
                PresenceTransport.NOTIFICATION_LISTENER,
                PresenceTransport.ACCESSIBILITY_HANDOFF
            ),
            capabilities = setOf(
                PresenceCapability.RECEIVE_TEXT,
                PresenceCapability.RECEIVE_MEDIA,
                PresenceCapability.OBSERVE_CONTEXT,
                PresenceCapability.DRAFT_REPLY,
                PresenceCapability.OPEN_NATIVE_APP,
                PresenceCapability.SEND_TEXT,
                PresenceCapability.NATIVE_IDENTITY
            ),
            availability = PresenceAvailability.AVAILABLE,
            level = PresenceAdapterLevel.NATIVE_ACCOUNT
        ),
        PresenceChannelAdapter(
            channel = PresenceChannel.WECOM,
            displayName = "企业微信机器人",
            packageNames = setOf("com.tencent.wework"),
            transports = setOf(PresenceTransport.OFFICIAL_PLATFORM_API, PresenceTransport.IN_APP),
            capabilities = setOf(
                PresenceCapability.RECEIVE_TEXT,
                PresenceCapability.RECEIVE_MEDIA,
                PresenceCapability.SEND_TEXT,
                PresenceCapability.SEND_MEDIA,
                PresenceCapability.NATIVE_IDENTITY
            ),
            availability = PresenceAvailability.AVAILABLE,
            level = PresenceAdapterLevel.OFFICIAL_BOT
        ),
        PresenceChannelAdapter(
            channel = PresenceChannel.FEISHU,
            displayName = "飞书机器人",
            packageNames = setOf("com.ss.android.lark"),
            transports = setOf(PresenceTransport.OFFICIAL_PLATFORM_API, PresenceTransport.IN_APP),
            capabilities = setOf(
                PresenceCapability.RECEIVE_TEXT,
                PresenceCapability.RECEIVE_MEDIA,
                PresenceCapability.SEND_TEXT,
                PresenceCapability.SEND_MEDIA,
                PresenceCapability.NATIVE_IDENTITY
            ),
            availability = PresenceAvailability.AVAILABLE,
            level = PresenceAdapterLevel.OFFICIAL_BOT
        ),
        PresenceChannelAdapter(
            channel = PresenceChannel.QQ,
            displayName = "QQ 机器人",
            packageNames = setOf("com.tencent.mobileqq"),
            transports = setOf(PresenceTransport.OFFICIAL_PLATFORM_API, PresenceTransport.IN_APP),
            capabilities = setOf(
                PresenceCapability.RECEIVE_TEXT,
                PresenceCapability.RECEIVE_MEDIA,
                PresenceCapability.SEND_TEXT,
                PresenceCapability.SEND_MEDIA,
                PresenceCapability.NATIVE_IDENTITY
            ),
            availability = PresenceAvailability.AVAILABLE,
            level = PresenceAdapterLevel.OFFICIAL_BOT
        ),
        PresenceChannelAdapter(
            channel = PresenceChannel.DOUYIN,
            displayName = "抖音",
            packageNames = setOf("com.ss.android.ugc.aweme"),
            transports = setOf(
                PresenceTransport.ANDROID_SHARE,
                PresenceTransport.NOTIFICATION_LISTENER,
                PresenceTransport.DESKTOP_BRIDGE
            ),
            capabilities = setOf(
                PresenceCapability.RECEIVE_TEXT,
                PresenceCapability.RECEIVE_MEDIA,
                PresenceCapability.SEND_TEXT,
                PresenceCapability.OBSERVE_CONTEXT,
                PresenceCapability.OPEN_NATIVE_APP,
                PresenceCapability.NATIVE_IDENTITY
            ),
            availability = PresenceAvailability.BRIDGE_READY,
            level = PresenceAdapterLevel.NATIVE_ACCOUNT
        ),
        PresenceChannelAdapter(
            channel = PresenceChannel.GAME,
            displayName = "游戏",
            packageNames = emptySet(),
            transports = setOf(
                PresenceTransport.ANDROID_SHARE,
                PresenceTransport.NOTIFICATION_LISTENER,
                PresenceTransport.GAME_SDK
            ),
            capabilities = setOf(
                PresenceCapability.RECEIVE_TEXT,
                PresenceCapability.OBSERVE_CONTEXT,
                PresenceCapability.LIVE_VOICE,
                PresenceCapability.REALTIME_CO_PRESENCE
            ),
            availability = PresenceAvailability.PLANNED,
            level = PresenceAdapterLevel.SYSTEM_CAPABILITY
        )
    )

    fun forPackage(packageName: String?): PresenceChannelAdapter? {
        val normalized = packageName?.lowercase()?.trim().orEmpty()
        if (normalized.isBlank()) return null
        return adapters.firstOrNull { normalized in it.packageNames }
    }

    fun forChannel(channel: PresenceChannel): PresenceChannelAdapter? =
        adapters.firstOrNull { it.channel == channel }
}
