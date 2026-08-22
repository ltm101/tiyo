package com.koyo.screenwarden.enuman.experience

import android.content.Context
import com.koyo.screenwarden.CompanionScope
import com.koyo.screenwarden.enuman.SleepCycle
import com.koyo.screenwarden.presence.PresenceEvent
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Small adapter that turns real events into idempotent, privacy-safe ledger rows.
 *
 * This object must stay free of ProactiveMessenger/ActionExecutor/notification/
 * accessibility dependencies. It only records facts; it never acts.
 */
object ExperienceRecorder {

    fun presence(context: Context, event: PresenceEvent): Boolean {
        val scope = CompanionScope.capture(context)
        val summary = if (event.explicitUserAction) event.text?.take(MAX_SUMMARY) else null
        val digest = if (event.explicitUserAction && event.text != null) {
            sha256(event.text)
        } else {
            null
        }
        return ExperienceLedger.append(
            context,
            scope,
            ExperienceRecord(
                id = "exp_presence_${event.id}".take(120),
                companionId = scope.companionId,
                kind = ExperienceKind.PRESENCE,
                occurredAt = event.occurredAt,
                recordedAt = System.currentTimeMillis(),
                sourceChannel = event.channel.name,
                modality = event.modality.name,
                explicitUserAction = event.explicitUserAction,
                causalRefs = listOf(event.id),
                privacyClass = if (event.explicitUserAction) {
                    ExperiencePrivacy.EXPLICIT
                } else {
                    ExperiencePrivacy.PASSIVE
                },
                payloadDigest = digest,
                summary = summary,
                conversationKey = event.conversationKey,
                sourceRef = event.id
            )
        )
    }

    /**
     * 外部平台（抖音、微信等）消息进入统一 Ledger。
     *
     * 与 [presence] 相同隐私规则：被动消息不存正文；明确分享只存受限摘要/引用。
     */
    fun platformMessage(
        context: Context,
        scope: CompanionScope,
        event: PresenceEvent
    ): Boolean = presence(context, event)

    fun chatMessage(
        context: Context,
        scope: CompanionScope,
        sessionId: String,
        messageTimestamp: Long,
        userText: String
    ): Boolean {
        val sessionRef = if (sessionId.isBlank()) "chat" else sessionId
        val messageRef = "${sessionRef}_${messageTimestamp}"
        return ExperienceLedger.append(
            context,
            scope,
            ExperienceRecord(
                id = "exp_chat_${messageRef}".take(120),
                companionId = scope.companionId,
                kind = ExperienceKind.USER_MESSAGE,
                occurredAt = messageTimestamp,
                recordedAt = System.currentTimeMillis(),
                sourceChannel = "chat",
                modality = "text",
                explicitUserAction = true,
                causalRefs = listOf("session:$sessionRef", "message:$messageTimestamp"),
                privacyClass = ExperiencePrivacy.EXPLICIT,
                payloadDigest = sha256(userText),
                summary = null
            )
        )
    }

    fun expression(
        context: Context,
        scope: CompanionScope,
        at: Long,
        topicKey: String,
        intent: String
    ): Boolean {
        val ref = "$topicKey|$intent|$at"
        return ExperienceLedger.append(
            context,
            scope,
            ExperienceRecord(
                id = "exp_expression_${sha256(ref).take(24)}".take(120),
                companionId = scope.companionId,
                kind = ExperienceKind.EXPRESSION,
                occurredAt = at,
                recordedAt = at,
                sourceChannel = "proactive",
                modality = "expression",
                explicitUserAction = false,
                causalRefs = listOf("expression:$topicKey", "intent:$intent"),
                privacyClass = ExperiencePrivacy.PASSIVE,
                payloadDigest = null,
                summary = null
            )
        )
    }

    fun deepSleep(
        context: Context,
        scope: CompanionScope,
        cycle: SleepCycle
    ): Boolean {
        return ExperienceLedger.append(
            context,
            scope,
            ExperienceRecord(
                id = "exp_sleep_${cycle.id}".take(120),
                companionId = scope.companionId,
                kind = ExperienceKind.DEEP_SLEEP,
                occurredAt = cycle.completedAt,
                recordedAt = cycle.completedAt,
                sourceChannel = "enuman",
                modality = if (cycle.kind.name == "DEEP_SLEEP") "deep_sleep" else "reflection",
                explicitUserAction = false,
                causalRefs = cycle.replayedPulseIds.take(ExperienceRecord.MAX_CAUSAL_REFS),
                privacyClass = ExperiencePrivacy.PASSIVE,
                payloadDigest = null,
                summary = null
            )
        )
    }

    /**
     * Rust MemoryManager 已成功提交一条长期记忆后的事实留痕
     *
     * Ledger 只保存稳定摘要哈希和调用引用，不复制记忆正文；真正的记忆文件仍由
     * Rust MemoryManager 作为单一事实源维护
     */
    fun memoryWrite(
        context: Context,
        scope: CompanionScope,
        callId: String,
        conversationKey: String?,
        arguments: JSONObject,
        at: Long = System.currentTimeMillis()
    ): Boolean {
        val fingerprint = buildString {
            append(arguments.optString("name")).append('|')
            append(arguments.optString("type")).append('|')
            append(arguments.optString("scope")).append('|')
            append(arguments.optString("description")).append('|')
            append(arguments.optString("content"))
        }
        return ExperienceLedger.append(
            context,
            scope,
            ExperienceRecord(
                id = "exp_memory_${sha256("${scope.companionId}|$callId").take(24)}",
                companionId = scope.companionId,
                kind = ExperienceKind.MEMORY_WRITE,
                occurredAt = at,
                recordedAt = at,
                sourceChannel = "agent",
                modality = "memory",
                explicitUserAction = false,
                causalRefs = listOf("tool_call:${callId.take(96)}"),
                privacyClass = ExperiencePrivacy.PASSIVE,
                payloadDigest = sha256(fingerprint),
                summary = null,
                conversationKey = conversationKey?.take(ExperienceRecord.MAX_CONVERSATION_KEY_CHARS),
                sourceRef = callId.take(ExperienceRecord.MAX_SOURCE_REF_CHARS)
            )
        )
    }

    fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private const val MAX_SUMMARY = 240
}
