package com.koyo.screenwarden

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Durable, companion-scoped conversation memory extraction. */
object TiyoMemoryExtractor {
    private const val TAG = "TiyoMemoryExtractor"
    private const val MAX_TURNS = 8
    private const val MAX_MSG_CHARS = 1200
    private const val MIN_INTERVAL_MS = 60_000L

    enum class UpdateState { IDLE, QUEUED, RUNNING, RETRYING, SUCCEEDED, FAILED }

    data class UpdateStatus(
        val state: UpdateState,
        val message: String,
        val updatedAt: Long,
        val created: Int,
        val updated: Int,
        val unchanged: Int
    )

    data class ExtractionResult(
        val succeeded: Boolean,
        val created: Int = 0,
        val updated: Int = 0,
        val unchanged: Int = 0,
        val message: String = "",
        val retryable: Boolean = true
    )

    data class Turn(
        val text: String,
        val isUser: Boolean,
        val timestamp: Long = 0L
    )

    private data class CallResult(
        val body: String? = null,
        val error: String = "",
        val retryable: Boolean = true
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val extractSystemPrompt = """
        你是对话长期记忆维护器。你不仅新增记忆，也负责修正已经变化的记忆

        只输出合法 JSON 数组，不要 Markdown 或解释：
        [{"key":"稳定语义键","content":"完整独立陈述","type":"persona|episodic|instruction","priority":80}]

        规则：
        1. 宁缺毋滥，过滤琐碎闲聊、一次性操作和 AI 自己的普通输出
        2. key 必须稳定、简短、与措辞无关，例如 user.preference.food、project.tiyo.status、ai.response.style
        3. 同一事实发生变化时必须复用已有 key，用新 content 覆盖旧事实
        4. persona 是稳定属性、偏好、技能、习惯或禁忌
        5. episodic 是已经发生的事件、决定、计划或结果
        6. instruction 是用户对当前陪伴者提出的长期行为规则
        7. 不确定、矛盾或没有长期价值时不要写，返回 []
    """.trimIndent()

    /** Compatibility entry point for callers that do not yet own a session id. */
    fun triggerIfDue(context: Context, recentTurns: List<Turn>): Boolean =
        triggerIfDue(context, CompanionScope.capture(context), "legacy", recentTurns)

    /** Persist a job before scheduling it, so process death cannot lose the turn. */
    fun triggerIfDue(
        context: Context,
        scope: CompanionScope,
        sessionId: String,
        recentTurns: List<Turn>
    ): Boolean {
        if (recentTurns.isEmpty() || sessionId.isBlank()) return false
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        if (now - TiyoMemoryJobStore.lastEnqueuedAt(appContext, scope, sessionId) < MIN_INTERVAL_MS) {
            return false
        }
        val normalized = recentTurns.mapIndexed { index, turn ->
            turn.copy(
                text = turn.text.trim().take(MAX_MSG_CHARS),
                timestamp = turn.timestamp.takeIf { it > 0L } ?: now + index
            )
        }.filter { it.text.isNotBlank() }
        val boundary = maxOf(
            TiyoMemoryJobStore.successfulCursor(appContext, scope, sessionId),
            TiyoMemoryJobStore.enqueuedCursor(appContext, scope, sessionId)
        )
        val turns = normalized.filter { it.timestamp > boundary }.takeLast(MAX_TURNS)
        if (turns.isEmpty()) return false

        val job = TiyoMemoryExtractionJob(
            id = UUID.randomUUID().toString(),
            scope = scope,
            sessionId = sessionId,
            turns = turns,
            createdAt = now
        )
        if (!TiyoMemoryJobStore.save(appContext, job)) return false
        if (!TiyoMemoryJobStore.markQueued(appContext, job)) {
            TiyoMemoryJobStore.delete(appContext, scope, job.id)
            return false
        }

        val input = Data.Builder()
            .putString(TiyoMemoryExtractionWorker.KEY_JOB_ID, job.id)
            .putString(TiyoMemoryExtractionWorker.KEY_COMPANION_ID, scope.companionId)
            .putString(TiyoMemoryExtractionWorker.KEY_COMPANION_NAME, scope.displayName)
            .build()
        val request = OneTimeWorkRequestBuilder<TiyoMemoryExtractionWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        val chain = "tiyo_memory_${scope.companionId}_${sessionId.hashCode().toUInt().toString(16)}"
        val scheduled = runCatching {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                chain,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }.isSuccess
        if (!scheduled) {
            TiyoMemoryJobStore.markFailed(appContext, job, "记忆任务调度失败")
            TiyoMemoryJobStore.delete(appContext, scope, job.id)
            return false
        }
        return true
    }

    fun latestStatus(
        context: Context,
        scope: CompanionScope = CompanionScope.capture(context)
    ): UpdateStatus = TiyoMemoryJobStore.latestStatus(context, scope)

    /** Worker entry point. No active-profile lookup is allowed below this line. */
    internal fun extractAndWrite(
        context: Context,
        scope: CompanionScope,
        turns: List<Turn>
    ): ExtractionResult {
        val transcript = buildTranscript(turns)
        if (transcript.isBlank()) return ExtractionResult(true, message = "没有需要提炼的内容")
        if (!TiyoAgentConfig.isConfigured(context)) {
            return ExtractionResult(false, message = "记忆模型尚未配置", retryable = false)
        }
        val config = TiyoAgentConfig.load(context)
        val key = TiyoAgentConfig.providerKey(context)
        if (key.isBlank()) return ExtractionResult(false, message = "记忆模型密钥为空", retryable = false)

        val prompt = buildString {
            append("当前陪伴者：").append(scope.displayName).append("\n\n")
            append("已有记忆索引：\n")
            append(existingMemoryIndex(context, scope).ifBlank { "（暂无）" })
            append("\n\n本次新增对话：\n").append(transcript)
        }
        val call = callExtract(config.baseUrl, key, config.model, prompt)
        val raw = call.body ?: return ExtractionResult(
            succeeded = false,
            message = call.error.ifBlank { "记忆提炼请求失败" },
            retryable = call.retryable
        )
        val memories = parseOrNull(raw) ?: return ExtractionResult(
            succeeded = false,
            message = "记忆模型返回格式异常",
            retryable = true
        )
        var created = 0
        var updated = 0
        var unchanged = 0
        for (memory in memories) {
            when (TiyoAtomicMemory.upsert(context, scope, memory)?.disposition) {
                TiyoAtomicMemory.WriteDisposition.CREATED -> created++
                TiyoAtomicMemory.WriteDisposition.UPDATED -> updated++
                TiyoAtomicMemory.WriteDisposition.UNCHANGED -> unchanged++
                null -> return ExtractionResult(false, message = "记忆文件写入失败", retryable = true)
            }
        }
        return ExtractionResult(
            succeeded = true,
            created = created,
            updated = updated,
            unchanged = unchanged,
            message = when {
                created + updated > 0 -> "新增 $created 条，更新 $updated 条"
                unchanged > 0 -> "记忆内容没有变化"
                else -> "这段对话没有需要长期保存的内容"
            }
        )
    }

    /** Legacy synchronous helper retained for tests and tools. */
    fun extractAndWrite(context: Context, turns: List<Turn>): Int {
        val result = extractAndWrite(context, CompanionScope.capture(context), turns)
        return if (result.succeeded) result.created + result.updated else 0
    }

    fun parse(raw: String): List<TiyoAtomicMemory.AtomicMemory> = parseOrNull(raw).orEmpty()

    internal fun parseOrNull(raw: String): List<TiyoAtomicMemory.AtomicMemory>? {
        var cleaned = raw.trim()
        if (cleaned.startsWith("```")) {
            val parts = cleaned.split("```")
            cleaned = parts.getOrNull(1)?.trim() ?: return null
            if (cleaned.startsWith("json")) cleaned = cleaned.substring(4).trim()
        }
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end < start) return null
        val array = runCatching { JSONArray(cleaned.substring(start, end + 1)) }.getOrNull() ?: return null
        val result = mutableListOf<TiyoAtomicMemory.AtomicMemory>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val content = item.optString("content").trim()
            val type = item.optString("type").trim().takeIf {
                it in setOf(
                    TiyoAtomicMemory.TYPE_PERSONA,
                    TiyoAtomicMemory.TYPE_EPISODIC,
                    TiyoAtomicMemory.TYPE_INSTRUCTION
                )
            } ?: continue
            if (content.isBlank()) continue
            val semanticKey = TiyoAtomicMemory.normalizeKey(item.optString("key"))
                .ifBlank { "$type.${content.hashCode().toUInt().toString(16)}" }
            result += TiyoAtomicMemory.AtomicMemory(
                type = type,
                priority = item.optInt("priority", TiyoAtomicMemory.DEFAULT_PRIORITY).coerceIn(0, 100),
                content = content,
                scene = "手机对话",
                key = semanticKey
            )
        }
        return result
    }

    private fun buildTranscript(turns: List<Turn>): String = turns.joinToString("\n") { turn ->
        "${if (turn.isUser) "用户" else "陪伴者"}: ${turn.text.trim().take(MAX_MSG_CHARS)}"
    }

    private fun existingMemoryIndex(context: Context, scope: CompanionScope): String {
        val dir = File(CompanionWorkspace.agentHome(context, scope.companionId), "memory")
        return dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.equals("md", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.take(40)
            ?.mapNotNull { file ->
                runCatching {
                    val text = file.readText(Charsets.UTF_8)
                    val key = Regex("(?m)^key:\\s*\\\"?([^\\\"\\r\\n]+)")
                        .find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                    if (key.isBlank()) return@runCatching null
                    val body = text.substringAfter("---", "")
                        .substringAfter("---", "")
                        .trim().take(240)
                    "$key = $body"
                }.getOrNull()
            }
            ?.joinToString("\n")
            ?.take(6000)
            .orEmpty()
    }

    private fun callExtract(baseUrl: String, key: String, model: String, prompt: String): CallResult {
        return try {
            val base = baseUrl.trimEnd('/')
            val url = if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"
            val payload = JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", extractSystemPrompt))
                        .put(JSONObject().put("role", "user").put("content", prompt))
                )
                .put("temperature", 0.1)
                .put("max_tokens", 1500)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $key")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "extract http ${response.code}: ${text.take(200)}")
                    return CallResult(
                        error = "记忆提炼服务返回 ${response.code}",
                        retryable = response.code == 408 || response.code == 429 || response.code >= 500
                    )
                }
                val content = runCatching { JSONObject(text) }.getOrNull()
                    ?.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.takeIf { it.isNotBlank() }
                if (content == null) CallResult(error = "记忆模型没有返回内容")
                else CallResult(body = content)
            }
        } catch (error: Exception) {
            Log.w(TAG, "extract failed", error)
            CallResult(error = error.message?.take(160) ?: "网络连接失败")
        }
    }
}
