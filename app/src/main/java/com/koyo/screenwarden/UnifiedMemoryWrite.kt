package com.koyo.screenwarden

import android.content.Context
import android.util.Log
import com.koyo.screenwarden.enuman.experience.ExperienceRecorder
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Rust MemoryManager 是长期记忆文件的单一事实源
 *
 * Android 只在 memory_write 成功完成后登记跨设备同步候选和 Experience Ledger
 * 来源，绝不在 tool_start 阶段抢先落盘
 */
object UnifiedMemoryWrite {
    fun onCommitted(
        context: Context,
        scope: CompanionScope,
        callId: String,
        arguments: JSONObject,
        conversationKey: String?
    ): Boolean {
        if (callId.isBlank() || arguments.length() == 0) return false
        val stableEventId = stableEventId(scope.companionId, callId)
        runCatching {
            TiyoMemoryBridge.enqueueMemoryWrite(
                context.applicationContext,
                scope,
                arguments,
                stableEventId
            )
        }.onFailure { error ->
            Log.w("UnifiedMemoryWrite", "memory sync candidate write failed", error)
        }
        runCatching {
            ExperienceRecorder.memoryWrite(
                context.applicationContext,
                scope,
                callId,
                conversationKey,
                arguments
            )
        }.onFailure { error ->
            Log.w("UnifiedMemoryWrite", "memory ledger trace write failed", error)
        }
        return true
    }

    /**
     * App 自身产生的明确经历（如完成计划、倒计时结束）没有 Rust tool call
     * 仍写进与 Rust MemoryManager 相同的 home/memory 目录和兼容 schema
     */
    fun recordAppMemory(
        context: Context,
        scope: CompanionScope,
        sourceId: String,
        description: String,
        content: String,
        scene: String,
        conversationKey: String? = null,
        priority: Int = TiyoAtomicMemory.DEFAULT_PRIORITY,
        at: Long = System.currentTimeMillis()
    ): Boolean {
        if (sourceId.isBlank() || content.isBlank()) return false
        val occurredAt = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), ZoneOffset.UTC)
        val write = TiyoAtomicMemory.upsert(
            context.applicationContext,
            scope,
            TiyoAtomicMemory.AtomicMemory(
                type = TiyoAtomicMemory.TYPE_EPISODIC,
                priority = priority,
                content = content,
                scene = scene,
                occurredAt = occurredAt,
                key = "app.event.$sourceId"
            )
        ) ?: return false
        val arguments = JSONObject()
            .put("name", write.filename.removeSuffix(".md"))
            .put("description", description.take(240))
            .put("type", TiyoAtomicMemory.TYPE_EPISODIC)
            .put("scope", "global")
            .put("content", content)
        val callId = "app_${sourceId}".take(120)
        TiyoMemoryBridge.enqueueMemoryWrite(
            context.applicationContext,
            scope,
            arguments,
            stableEventId(scope.companionId, callId)
        )
        ExperienceRecorder.memoryWrite(
            context.applicationContext,
            scope,
            callId,
            conversationKey,
            arguments,
            at
        )
        return true
    }

    internal fun stableEventId(companionId: String, callId: String): String =
        "memory_tool_${companionId}_${callId}".take(120)
}
