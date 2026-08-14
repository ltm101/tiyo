package com.koyo.screenwarden

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Public, pairwise relationship metadata between companions.
 *
 * This store deliberately contains no chat history or companion-owned memory.
 * A collaboration receives only the relationship card plus context explicitly
 * placed in that collaboration by the active companion.
 */
data class CompanionRelationship(
    val firstId: String,
    val secondId: String,
    val kind: String = CompanionRelationshipStore.KIND_PEERS,
    val sharedNotes: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val schemaVersion: Int = CompanionRelationshipStore.SCHEMA_VERSION
) {
    val id: String
        get() = CompanionRelationshipStore.pairId(firstId, secondId)

    fun peerId(companionId: String): String? {
        val safeId = CompanionProfileRules.normalizeId(companionId)
        return when (safeId) {
            firstId -> secondId
            secondId -> firstId
            else -> null
        }
    }
}

object CompanionRelationshipStore {
    internal const val SCHEMA_VERSION = 1
    internal const val KIND_PEERS = "trusted_peers"
    private const val PREFS = "tiyo_companion_relationships"
    private const val KEY_RELATIONSHIPS = "relationships_v1"
    private const val MAX_SHARED_NOTES = 600

    fun ensureForProfile(
        context: Context,
        profile: CompanionProfile,
        now: Long = System.currentTimeMillis()
    ): CompanionRelationship? {
        if (profile.isBuiltInCompanion) return null
        val expectedId = pairId(CompanionProfileRules.DEFAULT_COMPANION_ID, profile.id)
        relationships(context).firstOrNull { it.id == expectedId }?.let { return it }
        val relationship = canonical(
            CompanionRelationship(
                firstId = CompanionProfileRules.DEFAULT_COMPANION_ID,
                secondId = profile.id,
                createdAt = now,
                updatedAt = now
            )
        )
        write(context, relationships(context) + relationship)
        return relationship
    }

    fun relationships(context: Context): List<CompanionRelationship> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RELATIONSHIPS, null)
        return parseRelationships(raw)
    }

    fun peersFor(context: Context, companionId: String): List<CompanionRelationship> {
        val safeId = CompanionProfileRules.normalizeId(companionId)
        return relationships(context).filter { it.peerId(safeId) != null }
    }

    fun updateSharedNotes(
        context: Context,
        firstId: String,
        secondId: String,
        notes: String
    ): Boolean {
        val expectedId = pairId(firstId, secondId)
        val current = relationships(context)
        val index = current.indexOfFirst { it.id == expectedId }
        if (index < 0) return false
        val updated = current[index].copy(
            sharedNotes = notes.trim().take(MAX_SHARED_NOTES),
            updatedAt = System.currentTimeMillis()
        )
        write(context, current.toMutableList().apply { set(index, updated) })
        return true
    }

    fun eraseProfile(context: Context, companionId: String): Boolean {
        val safeId = CompanionProfileRules.normalizeId(companionId)
        val remaining = relationships(context).filter { it.peerId(safeId) == null }
        return write(context, remaining)
    }

    /** Runtime prompt contains public identity cards only, never private memory. */
    fun collaborationRules(context: Context, active: CompanionProfile): String {
        val profiles = CompanionProfileStore.profiles(context).associateBy { it.id }
        profiles.values.filterNot(CompanionProfile::isBuiltInCompanion).forEach { profile ->
            ensureForProfile(context, profile)
        }
        val peers = peersFor(context, active.id).mapNotNull { relationship ->
            val peer = profiles[relationship.peerId(active.id)] ?: return@mapNotNull null
            val note = relationship.sharedNotes.takeIf(String::isNotBlank)
                ?.let { "；共同确认过：${it.replace(Regex("\\s+"), " ")}" }
                .orEmpty()
            "- ${peer.displayName}（${peer.id}）：彼此认识的独立协作伙伴$note"
        }
        val roster = peers.ifEmpty { listOf("- 暂无已建立关系的角色") }.joinToString("\n")
        return """
            ## 角色协作与边界
            你当前是「${active.displayName}」，不能冒充其他角色，也不能把其他角色的经历说成自己的。
            已建立关系的角色：
            $roster

            用户要求讨论、比较或共同完成任务时，你是面向用户的协调者。用 spawn_agent 创建有名字的独立参与者，用 wait_agent 等待；需要追问、反驳或修订时，用 send_agent_message 延续同一参与者的上下文；结束后用 close_agent 关闭。
            默认最多三名参与者、三轮讨论。参与者可以不同意彼此，协调者最后说明共识、分歧和建议，不能伪造成大家一致。
            fork_turns 默认用 none。只把当前任务所需材料和用户明确同意共享的上下文放进协作消息；不得读取、复制或概括其他角色的私人记忆、日记、历史会话和亲密关系。共享备注只是双方明确确认过的公开结论，不是任何一方的私有记忆。
        """.trimIndent()
    }

    internal fun pairId(firstId: String, secondId: String): String {
        val values = listOf(
            CompanionProfileRules.normalizeId(firstId),
            CompanionProfileRules.normalizeId(secondId)
        ).sorted()
        return "${values[0]}::${values[1]}"
    }

    internal fun parseRelationships(raw: String?): List<CompanionRelationship> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val first = CompanionProfileRules.normalizeId(item.optString("first_id"))
                    val second = CompanionProfileRules.normalizeId(item.optString("second_id"))
                    if (first == second) continue
                    add(
                        canonical(
                            CompanionRelationship(
                                firstId = first,
                                secondId = second,
                                kind = item.optString("kind").ifBlank { KIND_PEERS },
                                sharedNotes = item.optString("shared_notes").take(MAX_SHARED_NOTES),
                                createdAt = item.optLong("created_at"),
                                updatedAt = item.optLong("updated_at"),
                                schemaVersion = item.optInt("schema_version", SCHEMA_VERSION)
                            )
                        )
                    )
                }
            }.distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    private fun canonical(value: CompanionRelationship): CompanionRelationship {
        val ids = listOf(
            CompanionProfileRules.normalizeId(value.firstId),
            CompanionProfileRules.normalizeId(value.secondId)
        ).sorted()
        return value.copy(firstId = ids[0], secondId = ids[1])
    }

    private fun write(context: Context, values: List<CompanionRelationship>): Boolean {
        val array = JSONArray()
        values.map(::canonical).distinctBy { it.id }.forEach { relationship ->
            array.put(
                JSONObject()
                    .put("first_id", relationship.firstId)
                    .put("second_id", relationship.secondId)
                    .put("kind", relationship.kind)
                    .put("shared_notes", relationship.sharedNotes)
                    .put("created_at", relationship.createdAt)
                    .put("updated_at", relationship.updatedAt)
                    .put("schema_version", relationship.schemaVersion)
            )
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RELATIONSHIPS, array.toString())
            .commit()
    }
}
