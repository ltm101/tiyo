package com.koyo.screenwarden.enuman

import android.content.Context
import com.koyo.screenwarden.CompanionScope
import com.koyo.screenwarden.MemoryTimelineLoader
import com.koyo.screenwarden.TiyoAgentConfig
import com.koyo.screenwarden.presence.PresenceEvent
import com.koyo.screenwarden.presence.PresenceEventStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EnuManInterpretationResult(
    val interpretation: ImpulseInterpretation,
    val proposedPlasticity: Map<String, Double>
)

/** Private semantic interpretation. It can create meaning, never expression. */
object EnuManInterpreter {
    private const val MAX_DAILY_CALLS = 24
    private const val MAX_MEMORY_EXCERPT = 600
    private const val PREFS = "enuman_model_budget"
    private const val KEY_DAY = "day"
    private const val KEY_COUNT = "count"
    private val budgetLock = Any()

    suspend fun interpret(
        context: Context,
        scope: CompanionScope,
        pulse: PreSemanticPulse,
        parent: ImpulseInterpretation? = null,
        reflectionKind: ReflectionKind? = null,
        siblingContext: List<ImpulseInterpretation> = emptyList()
    ): EnuManInterpretationResult? {
        if (!TiyoAgentConfig.isConfigured(context)) return null
        val apiKey = TiyoAgentConfig.providerKey(context)
        if (apiKey.isBlank() || !acquireDailyBudget(context, scope)) return null
        val config = TiyoAgentConfig.load(context)
        val causes = PresenceEventStore.recent(context, 80)
            .filter { it.id in pulse.causeRefs }
            .take(8)
        val memoryQuery = causes.asSequence()
            .filter { it.explicitUserAction }
            .mapNotNull { it.text }
            .joinToString(" ")
            .take(280)
        val memories = if (memoryQuery.isBlank()) emptyList() else {
            MemoryTimelineLoader.recall(context, memoryQuery, 3, scope)
        }

        val userContext = JSONObject()
            .put("companion_name", scope.displayName)
            .put("time", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
            .put("reflection_kind", reflectionKind?.name ?: "INITIAL_INTERPRETATION")
            .put("pulse", JSONObject()
                .put("id", pulse.id)
                .put("activations", pulse.activations.toDriveJson())
                .put("conflicts", JSONArray().apply { pulse.conflicts.forEach { put(it.toJson()) } }))
            .put("causes", JSONArray().apply {
                causes.forEach { put(EnuManContextSanitizer.causeJson(it)) }
            })
            .putOpt("parent_interpretation", parent?.let { parentJson(it) })
            .put("related_unresolved_meanings", JSONArray().apply {
                siblingContext.take(4).forEach { item ->
                    put(JSONObject()
                        .put("pulse_id", item.pulseId)
                        .put("felt_meaning", item.feltMeaning.take(400))
                        .put("tensions", JSONArray(item.tensions.take(4))))
                }
            })
            .put("recalled_memories", JSONArray().apply {
                memories.forEach { memory ->
                    put(JSONObject()
                        .put("name", memory.name.take(160))
                        .put("description", memory.description.take(240))
                        .put("excerpt", MemoryTimelineLoader.readContent(memory).orEmpty().take(MAX_MEMORY_EXCERPT)))
                }
            })

        val system = """
            你是 EnuMan 的私密语义解释层，正在理解一次尚未成为语言的内部脉冲
            这不是聊天、任务规划或主动消息决策，你没有行动权，也不面向用户输出
            只把方向、强度、冲突和真实经历解释成第一人称的内在含义
            具体愿望必须来自所给经历，不能从基础驱力硬编事件
            允许仍不确定、互相矛盾、在反思中消解
            只能输出一个 JSON 对象，且只能有这些字段：
            {"felt_meaning":"私密内在理解","candidate_desires":["可能的具体愿望"],"tensions":["仍存在的冲突"],"confidence":0.0,"resolution":"unresolved|reflected|dissolved","plasticity":{"drive:CONNECTION":0.0}}
            禁止输出 action、message、send、tool、delay 或任何给用户看的文案
            非深睡时 plasticity 必须为空对象
            深睡时只能建议 drive:CONNECTION、drive:CURIOSITY、drive:SAFETY、drive:AUTONOMY、drive:COHERENCE、drive:REST 六种键，每项幅度不超过 0.03
        """.trimIndent()
        val payload = JSONObject()
            .put("model", config.model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", userContext.toString())))
            .put("temperature", if (reflectionKind == ReflectionKind.DEEP_SLEEP) 0.35 else 0.2)
            .put("max_tokens", 520)

        val raw = request(config.baseUrl, apiKey, payload) ?: return null
        return EnuManInterpretationParser.parse(
            raw = raw,
            pulseId = pulse.id,
            parent = parent,
            generatedAt = System.currentTimeMillis(),
            allowPlasticity = reflectionKind == ReflectionKind.DEEP_SLEEP
        )
    }

    private suspend fun request(baseUrl: String, apiKey: String, payload: JSONObject): String? =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL("${baseUrl.trimEnd('/')}/chat/completions")
                    .openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 7_000
                    connection.readTimeout = 30_000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setRequestProperty("Authorization", "Bearer $apiKey")
                    val bytes = payload.toString().toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                    if (connection.responseCode !in 200..299) return@withContext null
                    val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    JSONObject(response).optJSONArray("choices")
                        ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                        ?.takeIf { it.isNotBlank() }
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                null
            }
        }

    private fun acquireDailyBudget(context: Context, scope: CompanionScope): Boolean = synchronized(budgetLock) {
        val prefs = context.getSharedPreferences(scope.namespaced(PREFS), Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val storedDay = prefs.getString(KEY_DAY, "")
        val count = if (storedDay == today) prefs.getInt(KEY_COUNT, 0) else 0
        if (count >= MAX_DAILY_CALLS) return@synchronized false
        prefs.edit().putString(KEY_DAY, today).putInt(KEY_COUNT, count + 1).apply()
        true
    }

    private fun parentJson(parent: ImpulseInterpretation): JSONObject = JSONObject()
        .put("id", parent.id)
        .put("version", parent.version)
        .put("felt_meaning", parent.feltMeaning.take(600))
        .put("candidate_desires", JSONArray(parent.candidateDesires.take(6)))
        .put("tensions", JSONArray(parent.tensions.take(6)))
}

internal object EnuManContextSanitizer {
    fun causeJson(event: PresenceEvent): JSONObject = JSONObject()
        .put("id", event.id)
        .put("channel", event.channel.name)
        .put("direction", event.direction.name)
        .put("modality", event.modality.name)
        .put("explicit_user_action", event.explicitUserAction)
        .putOpt("source_label", event.sourceLabel?.take(120))
        .putOpt("conversation_key", event.conversationKey?.take(160))
        .apply {
            if (event.explicitUserAction) {
                putOpt("shared_text", event.text?.take(1_200))
                put("attachment_types", JSONArray(event.attachments.map { it.mimeType.take(120) }))
            }
        }
}

internal object EnuManInterpretationParser {
    private val prohibitedKeys = setOf("action", "message", "send", "tool", "delay", "reply")

    fun parse(
        raw: String,
        pulseId: String,
        parent: ImpulseInterpretation?,
        generatedAt: Long,
        allowPlasticity: Boolean
    ): EnuManInterpretationResult? {
        val jsonText = raw.substringAfter('{', "").substringBeforeLast('}', "")
        if (jsonText.isBlank()) return null
        val json = runCatching { JSONObject("{$jsonText}") }.getOrNull() ?: return null
        if (json.keys().asSequence().any { it.lowercase(Locale.ROOT) in prohibitedKeys }) return null
        val meaning = json.optString("felt_meaning").trim()
            .take(ImpulseInterpretation.MAX_MEANING_CHARS)
        if (meaning.isBlank()) return null
        val status = when (json.optString("resolution").lowercase(Locale.ROOT)) {
            "reflected" -> InterpretationStatus.REFLECTED
            "dissolved" -> InterpretationStatus.DISSOLVED
            else -> InterpretationStatus.UNRESOLVED
        }
        val interpretation = ImpulseInterpretation(
            pulseId = pulseId,
            parentInterpretationId = parent?.id,
            version = (parent?.version ?: 0) + 1,
            generatedAt = generatedAt,
            feltMeaning = meaning,
            candidateDesires = json.optJSONArray("candidate_desires")
                .toStringList(ImpulseInterpretation.MAX_LIST_ITEMS)
                .map { it.take(ImpulseInterpretation.MAX_ITEM_CHARS) },
            tensions = json.optJSONArray("tensions")
                .toStringList(ImpulseInterpretation.MAX_LIST_ITEMS)
                .map { it.take(ImpulseInterpretation.MAX_ITEM_CHARS) },
            confidence = json.optDouble("confidence", 0.0).finiteOr(0.0).coerceIn(0.0, 1.0),
            status = status
        )
        val plasticity = if (allowPlasticity) {
            json.optJSONObject("plasticity").toStringDoubleMap()
                .filterKeys(::isAllowedPlasticityKey)
                .mapValues { (_, value) ->
                    value.coerceIn(
                        -EnuManSleepEngine.MAX_DELTA_PER_DEEP_SLEEP,
                        EnuManSleepEngine.MAX_DELTA_PER_DEEP_SLEEP
                    )
                }
        } else emptyMap()
        return EnuManInterpretationResult(interpretation, plasticity)
    }

    private fun isAllowedPlasticityKey(key: String): Boolean {
        if (!key.startsWith("drive:")) return false
        return runCatching { enumValueOf<EnuManDrive>(key.removePrefix("drive:")) }.isSuccess
    }
}
