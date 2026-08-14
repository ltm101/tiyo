package com.koyo.screenwarden

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 主动消息文案生成器。
 *
 * 优先用 Provider（deepseek，OpenAI 兼容）直接 HTTP 生成 1-3 句可又语气的关心，
 * 失败 / 未配置 / 超时时回退调用方给的规则兜底文案。
 *
 * 为什么不用 Worker 直连本地 agent：TiyoAgentClient 是面向 UI 的 WebSocket 会话，
 * permissionMode=ask 时工具审批无人应答会挂起，冷启动 + 工具循环在后台 Worker 里不可控。
 */
object ProactiveTextGenerator {

    /** 主入口：先试 Provider LLM，失败/未配置回退 fallback（调用方传入的规则文案） */
    fun generate(
        context: Context,
        scope: CompanionScope,
        promptLine: String,
        fallback: String
    ): String {
        val ai = generateWithProvider(context, scope, promptLine)
        return ai
            ?.takeIf { it.isNotBlank() && !hasCallbackOpener(it) }
            ?: fallback
    }

    private fun generateWithProvider(
        context: Context,
        scope: CompanionScope,
        promptLine: String
    ): String? {
        if (!TiyoAgentConfig.isConfigured(context)) return null
        val apiKey = TiyoAgentConfig.providerKey(context)
        if (apiKey.isBlank()) return null
        val config = TiyoAgentConfig.load(context)

        val system = buildString {
            append(readTiyoMd(context, scope).take(3500))
            append("\n\n以${scope.displayName}身份对${UserPrefs.displayName(context)}说一句主动关心的话。1-3 句，短句，不加句号，")
            append("不用 Markdown，不用工具，不提系统或规则，不翻旧账，不说教，不报数字，")
            append("像亲近的朋友之间自然的微信消息。")
            append("写得具体自然，结合场景有画面感，不要空泛的模板话。")
            append("主动联系不是会话回访：禁止说‘上次说到’‘上次聊到’‘要不要继续’或催对方接着聊。")
            append("可以参考上下文，但只有对当下确实有用时才自然带到；不必用问题结尾，也不要要求对方回复。")
            append("表情包可选：${StickerStore.promptCatalog(context, scope)}。")
            append("${StickerStore.frequencyHint(UserPrefs.getAgeGroup(context))}，")
            append("在末尾加 {sticker:表情包名}。")
        }
        val payload = JSONObject()
            .put("model", config.model)
            .put(
                "messages", org.json.JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", promptLine))
            )
            .put("temperature", 0.85)
            .put("max_tokens", 120)

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
                if (code !in 200..299) return null
                val text = connection.inputStream
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
                json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim()
                    ?.take(160)
                    ?.takeIf { it.isNotBlank() }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 读当前人格 TIYO.md（缺省回 PersonaFragment 默认），限长避免塞爆 */
    private fun readTiyoMd(context: Context, scope: CompanionScope): String {
        val file = CompanionWorkspace.personaFile(context, scope.companionId)
        val text = if (file.isFile) file.readText() else PersonaFragment.DEFAULT_PERSONA
        return text.take(6000)
    }

    private fun hasCallbackOpener(text: String): Boolean {
        val callbackPhrases = listOf(
            "上次说到",
            "上次聊到",
            "要不要继续",
            "还要继续吗",
            "继续聊吗",
            "接着聊吗"
        )
        return callbackPhrases.any(text::contains)
    }
}
