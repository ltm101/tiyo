package com.koyo.screenwarden.enuman.experience

import android.content.Context
import com.koyo.screenwarden.CompanionScope
import com.koyo.screenwarden.CompanionWorkspace
import com.koyo.screenwarden.enuman.SAFE_ID_REGEX
import com.koyo.screenwarden.enuman.enumValue
import com.koyo.screenwarden.enuman.safeId
import com.koyo.screenwarden.enuman.toStringList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Experience Ledger —— “发生过什么”的事实层。
 *
 * 它不是记忆摘要、模型理解、感受或愿望，只是不可变、可追溯、按 companion 隔离的事实留痕。
 * 对应 EnuMan 记忆“呼吸”里的“吸”：先可靠地接住发生过的事，之后才谈提炼与召回。
 */

enum class ExperienceKind {
    USER_MESSAGE,
    PRESENCE,
    EXPRESSION,
    DEEP_SLEEP,
    MEMORY_WRITE,
    CORRECTION,
    FORGET
}

enum class ExperiencePrivacy {
    /** 被动感知：只允许存 channel/modality/时间/脱敏类别，不存正文。 */
    PASSIVE,

    /** 明确分享：允许存受限摘要或对已有私有附件的引用。 */
    EXPLICIT
}

data class ExperienceRecord(
    val id: String = UUID.randomUUID().toString(),
    val companionId: String,
    val kind: ExperienceKind,
    val occurredAt: Long,
    val recordedAt: Long,
    val sourceChannel: String,
    val modality: String,
    val explicitUserAction: Boolean,
    val causalRefs: List<String> = emptyList(),
    val privacyClass: ExperiencePrivacy,
    val payloadDigest: String? = null,
    val summary: String? = null,
    val correctionOf: String? = null,
    val conversationKey: String? = null,
    val sourceRef: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("companion_id", companionId)
        .put("kind", kind.name)
        .put("occurred_at", occurredAt)
        .put("recorded_at", recordedAt)
        .put("source_channel", sourceChannel.take(MAX_CHANNEL_CHARS))
        .put("modality", modality.take(MAX_MODALITY_CHARS))
        .put("explicit_user_action", explicitUserAction)
        .put("causal_refs", JSONArray(causalRefs.take(MAX_CAUSAL_REFS)))
        .put("privacy_class", privacyClass.name)
        .putOpt("payload_digest", payloadDigest?.take(MAX_DIGEST_CHARS))
        .putOpt("summary", summary?.take(MAX_SUMMARY_CHARS))
        .putOpt("correction_of", correctionOf)
        .putOpt("conversation_key", conversationKey?.take(MAX_CONVERSATION_KEY_CHARS))
        .putOpt("source_ref", sourceRef?.take(MAX_SOURCE_REF_CHARS))

    companion object {
        const val MAX_CHANNEL_CHARS = 64
        const val MAX_MODALITY_CHARS = 32
        const val MAX_SUMMARY_CHARS = 1_200
        const val MAX_DIGEST_CHARS = 64
        const val MAX_CAUSAL_REFS = 16
        const val MAX_CONVERSATION_KEY_CHARS = 160
        const val MAX_SOURCE_REF_CHARS = 120

        fun fromJson(json: JSONObject): ExperienceRecord? {
            val id = json.safeId("id") ?: return null
            val companionId = json.safeId("companion_id") ?: return null
            val kind = json.enumValue<ExperienceKind>("kind") ?: return null
            val occurredAt = json.optLong("occurred_at", 0L).takeIf { it > 0L } ?: return null
            val recordedAt = json.optLong("recorded_at", 0L).takeIf { it > 0L } ?: return null
            val privacyClass = json.enumValue<ExperiencePrivacy>("privacy_class") ?: return null
            return ExperienceRecord(
                id = id,
                companionId = companionId,
                kind = kind,
                occurredAt = occurredAt,
                recordedAt = recordedAt,
                sourceChannel = json.optString("source_channel").take(MAX_CHANNEL_CHARS),
                modality = json.optString("modality").take(MAX_MODALITY_CHARS),
                explicitUserAction = json.optBoolean("explicit_user_action"),
                causalRefs = json.optJSONArray("causal_refs").toStringList(MAX_CAUSAL_REFS),
                privacyClass = privacyClass,
                payloadDigest = json.optString("payload_digest")
                    .takeIf { it.matches(SAFE_ID_REGEX) },
                summary = json.optString("summary").take(MAX_SUMMARY_CHARS)
                    .takeIf { it.isNotBlank() },
                correctionOf = json.optString("correction_of").takeIf { it.matches(SAFE_ID_REGEX) },
                conversationKey = json.optString("conversation_key").take(MAX_CONVERSATION_KEY_CHARS)
                    .takeIf { it.isNotBlank() },
                sourceRef = json.optString("source_ref").take(MAX_SOURCE_REF_CHARS)
                    .takeIf { it.isNotBlank() }
            )
        }
    }
}

/**
 * append-only 的事实账本：只追加，不原地更新。更正与遗忘都以新记录形式追加，不伪造“从未发生”。
 */
object ExperienceLedger {
    const val MAX_RECORDS = 512
    private val lock = Any()

    /** 幂等追加：同一 id 重复到达只留一条。返回是否真正写入。 */
    fun append(context: Context, scope: CompanionScope, record: ExperienceRecord): Boolean =
        synchronized(lock) { store(context, scope).append(record) }

    fun records(context: Context, scope: CompanionScope): List<ExperienceRecord> =
        synchronized(lock) { store(context, scope).records() }

    fun count(context: Context, scope: CompanionScope): Int =
        synchronized(lock) { store(context, scope).count() }

    fun recentByChannel(
        context: Context,
        scope: CompanionScope,
        channel: String,
        limit: Int = 20
    ): List<ExperienceRecord> = synchronized(lock) {
        store(context, scope).records()
            .asReversed()
            .filter { it.sourceChannel.equals(channel, ignoreCase = true) }
            .take(limit.coerceIn(0, MAX_RECORDS))
    }

    fun schemaVersion(context: Context, scope: CompanionScope): Int =
        synchronized(lock) { store(context, scope).schemaVersion() }

    /** 追加一条更正记录，指向原记录，不覆盖历史。 */
    fun correct(
        context: Context,
        scope: CompanionScope,
        record: ExperienceRecord
    ): Boolean = append(
        context,
        scope,
        record.copy(
            id = UUID.randomUUID().toString(),
            kind = ExperienceKind.CORRECTION,
            correctionOf = record.id
        )
    )

    /** 追加一条遗忘记录（tombstone），表示某条事实不再参与后续召回。 */
    fun forget(
        context: Context,
        scope: CompanionScope,
        targetId: String,
        at: Long
    ): Boolean = append(
        context,
        scope,
        ExperienceRecord(
            companionId = scope.companionId,
            kind = ExperienceKind.FORGET,
            occurredAt = at,
            recordedAt = at,
            sourceChannel = "ledger",
            modality = "control",
            explicitUserAction = true,
            privacyClass = ExperiencePrivacy.EXPLICIT,
            correctionOf = targetId
        )
    )

    private fun store(context: Context, scope: CompanionScope): ExperienceLedgerStore {
        val dir = File(CompanionWorkspace.privateRoot(context, scope.companionId), "enuman/experience")
            .apply { mkdirs() }
        return ExperienceLedgerStore(dir)
    }
}
