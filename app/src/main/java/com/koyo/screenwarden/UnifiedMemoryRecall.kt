package com.koyo.screenwarden

import android.content.Context
import com.koyo.screenwarden.enuman.experience.ExperienceLedger
import com.koyo.screenwarden.enuman.experience.ExperiencePrivacy
import com.koyo.screenwarden.enuman.experience.ExperienceRecord

/**
 * 统一记忆召回入口。
 *
 * 目标：微信、抖音、tiyo 等所有身体在回复前都通过同一套召回读取记忆，
 * 不按平台各自维护一套记忆。
 *
 * 当前合并三套已有系统：
 * - Experience Ledger：不可变事实层
 * - MemoryTimelineLoader：Agent markdown 记忆
 * - MemoryShelfStore：每日记忆架
 */
object UnifiedMemoryRecall {

    data class Item(
        val source: String,
        val id: String,
        val title: String? = null,
        val summary: String? = null,
        val occurredAt: Long? = null,
        val channel: String? = null,
        val privacy: String? = null,
        val priority: Int? = null,
        val content: String? = null
    )

    data class Result(
        val companionId: String,
        val items: List<Item>,
        val capturedAt: Long
    )

    fun recall(
        context: Context,
        query: String,
        limit: Int = 8,
        scope: CompanionScope = CompanionScope.capture(context)
    ): Result {
        val q = query.trim()
        val ledger = ExperienceLedger.records(context.applicationContext, scope)
            .asReversed()
            .filter { record -> q.isBlank() || record.matches(q) }
            .map(::experienceItem)
        val memory = MemoryTimelineLoader.recall(
            context.applicationContext,
            q.ifBlank { " " },
            (limit * 2).coerceAtLeast(4),
            scope
        ).map(::memoryItem)
        val shelf = MemoryShelfStore.entries(context.applicationContext, scope)
            .asReversed()
            .filter { entry -> q.isBlank() || entry.matches(q) }
            .map(::shelfItem)
        val merged = merge(ledger, memory, shelf, limit.coerceIn(1, 32))
        return Result(scope.companionId, merged, System.currentTimeMillis())
    }

    fun recent(
        context: Context,
        limit: Int = 12,
        scope: CompanionScope = CompanionScope.capture(context)
    ): Result {
        val ledger = ExperienceLedger.records(context.applicationContext, scope)
            .asReversed()
            .take(limit * 2)
            .map(::experienceItem)
        val memory = MemoryTimelineLoader.scan(context.applicationContext, scope)
            .sortedByDescending { it.updatedMillis }
            .take(limit)
            .map(::memoryItem)
        val shelf = MemoryShelfStore.entries(context.applicationContext, scope)
            .asReversed()
            .take(limit)
            .map(::shelfItem)
        val merged = merge(ledger, memory, shelf, limit.coerceIn(1, 32))
        return Result(scope.companionId, merged, System.currentTimeMillis())
    }

    /** 纯合并逻辑，便于单测。 */
    internal fun merge(
        experience: List<Item>,
        memory: List<Item>,
        shelf: List<Item>,
        limit: Int
    ): List<Item> {
        val hasQuery = memory.any { it.title != null || it.summary != null }
        return if (hasQuery) {
            (memory + shelf + experience)
                .distinctBy { "${it.source}:${it.id}" }
                .take(limit)
        } else {
            (experience + shelf + memory)
                .distinctBy { "${it.source}:${it.id}" }
                .sortedByDescending { it.occurredAt ?: 0L }
                .take(limit)
        }
    }

    private fun experienceItem(record: ExperienceRecord): Item {
        val explicit = record.privacyClass == ExperiencePrivacy.EXPLICIT
        return Item(
            source = "experience",
            id = record.id,
            title = "${record.kind.name.lowercase()} · ${record.sourceChannel.lowercase()}",
            summary = if (explicit) record.summary else null,
            occurredAt = record.occurredAt,
            channel = record.sourceChannel,
            privacy = record.privacyClass.name,
            content = if (explicit) record.summary?.take(400) else null
        )
    }

    private fun memoryItem(item: MemoryTimelineLoader.MemoryItem): Item = Item(
        source = "memory",
        id = item.name,
        title = item.name,
        summary = item.description,
        occurredAt = item.updatedMillis,
        channel = "memory",
        priority = item.atomPriority,
        content = MemoryTimelineLoader.readContent(item)?.take(600)
    )

    private fun shelfItem(entry: MemoryShelfStore.Entry): Item = Item(
        source = "shelf",
        id = "${entry.date}-${entry.objectId}",
        title = entry.objectName.ifBlank { entry.objectId },
        summary = entry.summary,
        occurredAt = entry.settledAt.takeIf { it > 0L },
        channel = "memory_shelf",
        content = entry.summary
    )

    private fun ExperienceRecord.matches(q: String): Boolean {
        val hay = buildString {
            append(kind.name).append(' ').append(sourceChannel).append(' ').append(modality)
            if (privacyClass == ExperiencePrivacy.EXPLICIT) append(' ').append(summary.orEmpty())
        }.lowercase()
        return q.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.all { it in hay }
    }

    private fun MemoryShelfStore.Entry.matches(q: String): Boolean {
        val hay = "$date $objectId $objectName $summary $mood".lowercase()
        return q.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.all { it in hay }
    }
}
