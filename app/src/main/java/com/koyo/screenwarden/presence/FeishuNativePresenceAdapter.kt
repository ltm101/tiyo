package com.koyo.screenwarden.presence

import android.content.Context
import com.lark.oapi.channel.ChannelEventHandler
import com.lark.oapi.channel.LarkChannel
import com.lark.oapi.channel.LarkChannelFactory
import com.lark.oapi.channel.config.LarkChannelOptions
import com.lark.oapi.channel.model.NormalizedMessage
import com.lark.oapi.channel.model.SendInput
import com.lark.oapi.channel.model.SendOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class FeishuNativePresenceAdapter : PresenceAdapter {
    override val channel = PresenceChannel.FEISHU
    override val level = PresenceAdapterLevel.OFFICIAL_BOT
    override val capabilities = setOf(
        PresenceCapability.RECEIVE_TEXT,
        PresenceCapability.RECEIVE_MEDIA,
        PresenceCapability.SEND_TEXT,
        PresenceCapability.SEND_MEDIA,
        PresenceCapability.NATIVE_IDENTITY
    )
    override val availability = PresenceAvailability.AVAILABLE

    @Volatile private var lark: LarkChannel? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var currentHealth = AdapterHealth(false, "disabled")
    private val originalMessageIds = ConcurrentHashMap<String, String>()

    override fun start(context: Context) {
        val app = context.applicationContext
        if (!MobilePresenceConfig.isReady(app, channel) || lark != null) return
        val credentials = MobilePresenceConfig.credentials(app, channel)
        appContext = app
        currentHealth = AdapterHealth(false, "connecting")
        runCatching {
            val instance = LarkChannelFactory.createLarkChannel(
                LarkChannelOptions.newBuilder(credentials.primaryId, credentials.secret)
                    .transport("websocket")
                    .includeRawEvent(false)
                    .source("tiyo-android")
                    .build()
            )
            instance.on(
                "message",
                ChannelEventHandler<NormalizedMessage> { message -> onMessage(app, credentials, instance, message) }
            )
            lark = instance
            instance.connect().whenComplete { _, error ->
                currentHealth = if (error == null) {
                    AdapterHealth(true, "phone connected", lastStartedAt = System.currentTimeMillis())
                } else {
                    lark = null
                    AdapterHealth(false, "connection failed", error.javaClass.simpleName)
                }
            }
        }.onFailure { error ->
            lark = null
            currentHealth = AdapterHealth(false, "connection failed", error.javaClass.simpleName)
        }
    }

    override fun stop(context: Context) {
        val instance = lark
        lark = null
        runCatching { instance?.disconnect() }
        currentHealth = AdapterHealth(false, "stopped")
    }

    override fun health(): AdapterHealth = currentHealth

    override suspend fun send(reply: AdapterOutboundMessage): Boolean {
        val instance = lark ?: return false
        val messageId = reply.replyToMessageId?.let(originalMessageIds::get)
        val options = SendOptions.newBuilder().apply {
            if (!messageId.isNullOrBlank()) replyTo(messageId)
        }.build()
        return runCatching {
            instance.send(
                reply.conversationKey ?: return false,
                SendInput.text(reply.text.take(4_000)),
                options
            ).get(25, TimeUnit.SECONDS).messageId.isNotBlank()
        }.getOrDefault(false)
    }

    private fun onMessage(
        context: Context,
        credentials: MobileChannelCredentials,
        instance: LarkChannel,
        message: NormalizedMessage
    ) {
        if (!MobilePresenceConfig.allowsOrBindFirst(context, channel, message.senderId.orEmpty())) return
        val eventId = stablePresenceId("feishu", message.messageId.orEmpty())
        originalMessageIds[eventId] = message.messageId
        val attachments = message.resources.take(4).mapIndexedNotNull { index, resource ->
            if (resource.type != "image" || resource.fileKey.isNullOrBlank()) return@mapIndexedNotNull null
            runCatching {
                val bytes = instance.downloadResource(resource.fileKey, "image").get(20, TimeUnit.SECONDS)
                if (bytes.isEmpty() || bytes.size > 5 * 1024 * 1024) return@runCatching null
                val file = PresencePrivateMediaStore.write(context, eventId, index, "jpg", bytes)
                PresenceAttachment(file.absolutePath, resource.fileName ?: "飞书图片", "image/jpeg", bytes.size.toLong())
            }.getOrNull()
        }
        PresenceRouter.publish(
            context,
            PresenceEvent(
                id = eventId,
                channel = channel,
                direction = PresenceDirection.TO_COMPANION,
                modality = when {
                    attachments.isEmpty() -> PresenceModality.TEXT
                    message.content.isNullOrBlank() -> PresenceModality.IMAGE
                    else -> PresenceModality.COMPOSITE
                },
                sourceLabel = "飞书机器人",
                text = message.content?.trim()?.take(4_000),
                attachments = attachments,
                conversationKey = message.chatId,
                explicitUserAction = true,
                occurredAt = message.createTime.takeIf { it > 0 } ?: System.currentTimeMillis()
            )
        )
        currentHealth = currentHealth.copy(healthy = true, detail = "phone connected", lastMessageAt = System.currentTimeMillis())
    }
}
