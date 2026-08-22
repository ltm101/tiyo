package com.koyo.screenwarden.presence

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 一次跨应用的“在场”事件
 *
 * 它只保存已经被用户明确分享、或已经由现有感知系统脱敏过的内容
 * 临时 content:// URI 不会进入持久化层，附件必须先复制到 Tiyo 可读的路径
 */
data class PresenceEvent(
    val id: String = UUID.randomUUID().toString(),
    val channel: PresenceChannel,
    val direction: PresenceDirection,
    val modality: PresenceModality,
    val sourcePackage: String? = null,
    val sourceLabel: String? = null,
    val text: String? = null,
    val attachments: List<PresenceAttachment> = emptyList(),
    val conversationKey: String? = null,
    val explicitUserAction: Boolean = false,
    val occurredAt: Long = System.currentTimeMillis(),
    val consumedAt: Long? = null
) {
    fun persistedCopy(): PresenceEvent = copy(
        sourcePackage = sourcePackage.sanitizedPackage(),
        sourceLabel = sourceLabel.sanitizedSingleLine(MAX_LABEL_CHARS),
        text = text.sanitizedText(MAX_TEXT_CHARS),
        attachments = attachments.take(MAX_ATTACHMENTS).map(PresenceAttachment::persistedCopy),
        conversationKey = conversationKey.sanitizedSingleLine(MAX_CONVERSATION_KEY_CHARS)
    )

    fun toJson(): JSONObject {
        val safe = persistedCopy()
        return JSONObject()
            .put("id", safe.id)
            .put("channel", safe.channel.name)
            .put("direction", safe.direction.name)
            .put("modality", safe.modality.name)
            .putOpt("source_package", safe.sourcePackage)
            .putOpt("source_label", safe.sourceLabel)
            .putOpt("text", safe.text)
            .put("attachments", JSONArray().apply {
                safe.attachments.forEach { put(it.toJson()) }
            })
            .putOpt("conversation_key", safe.conversationKey)
            .put("explicit_user_action", safe.explicitUserAction)
            .put("occurred_at", safe.occurredAt)
            .putOpt("consumed_at", safe.consumedAt)
    }

    companion object {
        const val MAX_ATTACHMENTS = 12
        private const val MAX_TEXT_CHARS = 4_000
        private const val MAX_LABEL_CHARS = 120
        private const val MAX_CONVERSATION_KEY_CHARS = 160

        fun fromJson(json: JSONObject): PresenceEvent? {
            val id = json.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) }
                ?: return null
            val channel = json.enumValue<PresenceChannel>("channel") ?: return null
            val direction = json.enumValue<PresenceDirection>("direction") ?: return null
            val modality = json.enumValue<PresenceModality>("modality") ?: return null
            val attachments = buildList {
                val array = json.optJSONArray("attachments") ?: JSONArray()
                for (index in 0 until minOf(array.length(), MAX_ATTACHMENTS)) {
                    array.optJSONObject(index)?.let(PresenceAttachment::fromJson)?.let(::add)
                }
            }
            return PresenceEvent(
                id = id,
                channel = channel,
                direction = direction,
                modality = modality,
                sourcePackage = json.optNullableString("source_package"),
                sourceLabel = json.optNullableString("source_label"),
                text = json.optNullableString("text"),
                attachments = attachments,
                conversationKey = json.optNullableString("conversation_key"),
                explicitUserAction = json.optBoolean("explicit_user_action", false),
                occurredAt = json.optLong("occurred_at").takeIf { it > 0 }
                    ?: System.currentTimeMillis(),
                consumedAt = json.optLong("consumed_at").takeIf { it > 0 }
            ).persistedCopy()
        }
    }
}

data class PresenceAttachment(
    val privatePath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long
) {
    fun persistedCopy(): PresenceAttachment = copy(
        privatePath = privatePath.replace('\u0000', '_').take(MAX_PATH_CHARS),
        displayName = displayName.sanitizedSingleLine(MAX_NAME_CHARS).orEmpty().ifBlank { "附件" },
        mimeType = mimeType.lowercase()
            .replace(Regex("[^a-z0-9.+_/-]"), "")
            .take(MAX_MIME_CHARS)
            .ifBlank { "application/octet-stream" },
        sizeBytes = sizeBytes.coerceAtLeast(0)
    )

    fun toJson(): JSONObject {
        val safe = persistedCopy()
        return JSONObject()
            .put("private_path", safe.privatePath)
            .put("display_name", safe.displayName)
            .put("mime_type", safe.mimeType)
            .put("size_bytes", safe.sizeBytes)
    }

    companion object {
        private const val MAX_PATH_CHARS = 1_024
        private const val MAX_NAME_CHARS = 180
        private const val MAX_MIME_CHARS = 120

        fun fromJson(json: JSONObject): PresenceAttachment? {
            val path = json.optString("private_path").takeIf { it.isNotBlank() } ?: return null
            return PresenceAttachment(
                privatePath = path,
                displayName = json.optString("display_name", "附件"),
                mimeType = json.optString("mime_type", "application/octet-stream"),
                sizeBytes = json.optLong("size_bytes").coerceAtLeast(0)
            ).persistedCopy()
        }
    }
}

enum class PresenceChannel {
    TIYO,
    SYSTEM_SHARE,
    SYSTEM_ASSISTANT,
    NOTIFICATION,
    SCREEN_COMPANION,
    WECHAT,
    WECOM,
    FEISHU,
    QQ,
    DOUYIN,
    GAME,
    OTHER_APP
}

enum class PresenceDirection {
    TO_COMPANION,
    FROM_COMPANION,
    OBSERVED
}

enum class PresenceModality {
    TEXT,
    LINK,
    IMAGE,
    VIDEO,
    AUDIO,
    FILE,
    VOICE,
    CALL,
    GAME_SESSION,
    APP_CONTEXT,
    COMPOSITE
}

private inline fun <reified T : Enum<T>> JSONObject.enumValue(key: String): T? =
    runCatching { enumValueOf<T>(optString(key)) }.getOrNull()

private fun JSONObject.optNullableString(key: String): String? =
    optString(key).takeIf { it.isNotBlank() }

private fun String?.sanitizedSingleLine(maxChars: Int): String? = this
    ?.replace(Regex("[\\r\\n\\u0000]+"), " ")
    ?.trim()
    ?.take(maxChars)
    ?.takeIf { it.isNotBlank() }

private fun String?.sanitizedText(maxChars: Int): String? = this
    ?.replace("\u0000", "")
    ?.trim()
    ?.take(maxChars)
    ?.takeIf { it.isNotBlank() }

private fun String?.sanitizedPackage(): String? = this
    ?.lowercase()
    ?.replace(Regex("[^a-z0-9._]"), "")
    ?.take(180)
    ?.takeIf { it.isNotBlank() }
