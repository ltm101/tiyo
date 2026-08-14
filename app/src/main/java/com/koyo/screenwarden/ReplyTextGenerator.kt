package com.koyo.screenwarden

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 微信回复拟写器：收到新微信消息时，用 Provider（deepseek）以可又口吻
 * 代用户拟一条简短回复。失败 / 未配置 / 超时返回 null，调用方静默处理。
 *
 * 与 ProactiveTextGenerator 的区别：那是"对用户说关心的话"，
 * 这是"代用户回复第三方"，prompt 完全不同，也绝不用 TIYO.md 当人格（会泄露设定）。
 */
object ReplyTextGenerator {

    private const val TAG = "ReplyGen"

    fun generate(
        context: Context,
        contact: String,
        message: String,
        friendshipKey: String = "",
        scope: CompanionScope = CompanionScope.capture(context)
    ): String? {
        Log.i(TAG, "try reply generation")
        if (!TiyoAgentConfig.isConfigured(context)) {
            Log.w(TAG, "not configured")
            return null
        }
        val apiKey = TiyoAgentConfig.providerKey(context)
        if (apiKey.isBlank()) {
            Log.w(TAG, "api key blank")
            return null
        }
        val config = TiyoAgentConfig.load(context)
        Log.i(TAG, "calling ${config.baseUrl} model=${config.model}")

        val friendshipEnabled = friendshipKey.isNotBlank() && FriendshipProfileStore.isEnabled(context)
        val system = buildString {
            appendLine("你是用户设置的聊天代拟助手，帮他把回复拟好。")
            appendLine("回复风格要求：${ReplyStyleManager.load(context, scope)}")
            appendLine("硬性要求：")
            appendLine("1. 1-2 句，简短自然，像真人发微信，别啰嗦")
            appendLine("2. 不暴露自己是 AI，不用 Markdown，不要解释，不要加引号")
            appendLine("3. 对方消息太模糊或没什么好接的，就回一句得体的通用回复")
            appendLine("4. 句末不要加句号（。），短句之间用空格或换行隔开")

            // 手机 agent 的近况背景：让拟写更贴合用户实际，但回复里不主动提这些记忆
            val name = UserPrefs.displayName(context)
            if (name.isNotBlank()) appendLine("你是帮「$name」代拟回复，称呼上贴合他。")
            val recentMemories = MemoryTimelineLoader.scan(context, scope)
                .sortedByDescending { it.updatedMillis }
                .take(5)
                .map { it.description }
                .filter { it.isNotBlank() }
            if (recentMemories.isNotEmpty()) {
                appendLine("背景（用户近况，只用来让回复更贴合，不要在回复里直接提及，除非对方话题正好相关）：")
                recentMemories.forEach { appendLine("- $it") }
            }
            if (friendshipEnabled) {
                val friendship = FriendshipProfileStore.summaryForPrompt(context, friendshipKey)
                if (friendship.isNotBlank()) {
                    appendLine("下面是本地相处档案，只用于调整语气，不要向对方提到档案、分析或置信度：")
                    appendLine(friendship)
                }
                appendLine("只输出一个 JSON 对象，不要代码框：")
                appendLine("{\"reply\":\"给对方的回复\",\"profile_delta\":{\"topics\":[],\"traits\":[],\"facts\":[{\"text\":\"对方明确说出的事实\",\"ttl_days\":90}],\"recent_state\":\"\",\"recent_state_ttl_days\":7}}")
                appendLine("profile_delta 只能记录对方本条消息明确说出的非敏感信息，不猜测人格、关系、健康、政治、宗教、性、财务、证件或住址，拿不准就全部留空")
            }
        }
        val user = "对方（$contact）发来：$message\n帮用户拟一条回复。"

        val payload = JSONObject()
            .put("model", config.model)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
            .put("temperature", 0.85)
            .put("max_tokens", if (friendshipEnabled) 320 else 120)

        return try {
            val url = URL("${config.baseUrl.trimEnd('/')}/chat/completions")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 4_000
                connection.readTimeout = 20_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                val body = payload.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val err = connection.errorStream
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    Log.w(TAG, "http $code: ${err.take(200)}")
                    return null
                }
                val text = connection.inputStream
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
                val generated = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim()
                    .orEmpty()
                if (!friendshipEnabled) {
                    generated.take(200).takeIf { it.isNotBlank() }
                } else {
                    val objectResult = FriendshipProfileDeltaParser.parseObject(generated)
                    val reply = objectResult?.optString("reply").orEmpty().trim().take(200)
                        .ifBlank { generated.takeIf { !it.trim().startsWith("{") }.orEmpty().trim().take(200) }
                    objectResult?.optJSONObject("profile_delta")?.let { deltaJson ->
                        FriendshipProfileStore.applyDelta(
                            context,
                            friendshipKey,
                            FriendshipProfileDeltaParser.parse(deltaJson)
                        )
                    }
                    reply.takeIf { it.isNotBlank() }?.also {
                        FriendshipProfileStore.recordSuggested(context, friendshipKey)
                    }
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "exception: ${e.message}")
            null
        }
    }
}
