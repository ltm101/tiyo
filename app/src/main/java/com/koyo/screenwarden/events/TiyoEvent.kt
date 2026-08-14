package com.koyo.screenwarden.events

import org.json.JSONObject
import java.util.UUID

enum class TiyoEventType {
    SCREEN_ON,
    SCREEN_OFF,
    SCREEN_SESSION,
    POWER_CONNECTED,
    POWER_DISCONNECTED,
    NOTIFICATION,
    NOTIFICATION_BURST,
    STEP_MILESTONE,
    APP_LIMIT_APPROACHING,
    COMPANION_CONTEXT,
    TIME_ANCHOR,
    DEFERRED
}

/**
 * 决策事件。sensitiveContext 只存在于当前进程内存，序列化时永远丢弃。
 */
data class TiyoEvent(
    val type: TiyoEventType,
    val summary: String,
    val occurredAt: Long = System.currentTimeMillis(),
    val notBefore: Long = occurredAt,
    val attempt: Int = 0,
    val topicKey: String = type.name.lowercase(),
    val expiresAt: Long = Long.MAX_VALUE,
    val id: String = UUID.randomUUID().toString(),
    val sensitiveContext: String? = null
) {
    fun persistedCopy(): TiyoEvent = copy(
        summary = summary.replace(Regex("[\\r\\n]+"), " ").take(MAX_SUMMARY_CHARS),
        sensitiveContext = null
    )

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.name)
        .put("summary", persistedCopy().summary)
        .put("occurred_at", occurredAt)
        .put("not_before", notBefore)
        .put("attempt", attempt)
        .put("topic_key", topicKey.replace(Regex("[^a-zA-Z0-9:_-]"), "_").take(MAX_TOPIC_KEY_CHARS))
        .put("expires_at", expiresAt)

    companion object {
        private const val MAX_SUMMARY_CHARS = 240

        fun fromJson(json: JSONObject): TiyoEvent? {
            val type = runCatching {
                TiyoEventType.valueOf(json.optString("type"))
            }.getOrNull() ?: return null
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            return TiyoEvent(
                type = type,
                summary = json.optString("summary").take(MAX_SUMMARY_CHARS),
                occurredAt = json.optLong("occurred_at").takeIf { it > 0 }
                    ?: System.currentTimeMillis(),
                notBefore = json.optLong("not_before").takeIf { it > 0 }
                    ?: System.currentTimeMillis(),
                attempt = json.optInt("attempt").coerceIn(0, 2),
                topicKey = json.optString("topic_key")
                    .replace(Regex("[^a-zA-Z0-9:_-]"), "_")
                    .take(MAX_TOPIC_KEY_CHARS)
                    .ifBlank { type.name.lowercase() },
                expiresAt = json.optLong("expires_at").takeIf { it > 0 } ?: Long.MAX_VALUE,
                id = id,
                sensitiveContext = null
            )
        }

        private const val MAX_TOPIC_KEY_CHARS = 80
    }
}
