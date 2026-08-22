package com.koyo.screenwarden.presence

import android.content.Context

/**
 * 平台接入能力等级。
 *
 * 每个 App 都尽量使用它能安全达到的最高一级，但 Adapter 永远只是“身体”，
 * 不能拥有模型、记忆、决策或表达策略。
 */
enum class PresenceAdapterLevel(val level: Int) {
    NATIVE_ACCOUNT(1),
    OFFICIAL_BOT(2),
    SYSTEM_CAPABILITY(3),
    GENERIC_SHARE(4)
}

/** 适配器健康状态。只描述连接是否可用，不承载任何私人内容。 */
data class AdapterHealth(
    val healthy: Boolean,
    val detail: String = "",
    val lastError: String? = null,
    val lastStartedAt: Long? = null,
    val lastMessageAt: Long? = null
)

/** tiyo 决定要对外发送的回复。Adapter 只负责发送，不负责生成内容。 */
data class AdapterOutboundMessage(
    val channel: PresenceChannel,
    val conversationKey: String?,
    val text: String,
    val replyToMessageId: String? = null,
    val attachments: List<PresenceAttachment> = emptyList()
)

/**
 * Presence Adapter —— 可又在一个外部平台里的“身体”。
 *
 * 铁律：
 * - 不调模型
 * - 不写记忆
 * - 不自行决定是否回复
 * - 不直接依赖 ProactiveMessenger / ActionExecutor
 * - 入站消息统一交给 [PresenceRouter]
 * - 出站消息只发送 tiyo 已经决定的回复
 */
interface PresenceAdapter {
    val channel: PresenceChannel
    val level: PresenceAdapterLevel
    val capabilities: Set<PresenceCapability>
    val availability: PresenceAvailability

    fun start(context: Context)
    fun stop(context: Context)
    fun health(): AdapterHealth

    /**
     * 平台消息进入统一人格事件流。
     *
     * 默认实现已经把消息交给 [PresenceRouter.publish]，普通 Adapter 不应覆写；
     * 只有需要额外脱敏/转换的 Adapter 才先转换再调用 super 或直接 publish。
     */
    fun onInboundMessage(context: Context, message: PresenceEvent) {
        PresenceRouter.publish(context.applicationContext, message)
    }

    /** 发送 tiyo 已决定的回复。返回是否发送成功。 */
    suspend fun send(reply: AdapterOutboundMessage): Boolean
}
