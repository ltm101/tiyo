package com.koyo.screenwarden

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 一键克隆：分析用户提供的聊天记录样本，提炼出可用的"回复风格描述"。 */
object StyleCloner {

    private const val TAG = "StyleCloner"
    private val main = Handler(Looper.getMainLooper())

    fun analyze(
        context: Context,
        sample: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                if (!TiyoAgentConfig.isConfigured(context)) {
                    throw IllegalStateException("还没配置 API Key")
                }
                val apiKey = TiyoAgentConfig.providerKey(context)
                if (apiKey.isBlank()) throw IllegalStateException("还没配置 API Key")
                val config = TiyoAgentConfig.load(context)

                val system = buildString {
                    appendLine("你是语言风格分析师。分析下面聊天记录里\"我\"（回复方）的说话习惯。")
                    appendLine("观察：用词、句子长短、有没有表情、语气（客气/直接/活泼/高冷/幽默等）、口头禅或习惯用语、标点风格。")
                    appendLine("输出一段 50-150 字的\"回复风格描述\"，能直接用来指导代拟回复。不要 Markdown、不要序号、不要解释。")
                }
                val user = "以下是聊天记录样本（包含\"我\"说的话，请重点分析\"我\"）：\n\n" + sample.take(6000)

                val payload = JSONObject()
                    .put("model", config.model)
                    .put(
                        "messages", JSONArray()
                            .put(JSONObject().put("role", "system").put("content", system))
                            .put(JSONObject().put("role", "user").put("content", user))
                    )
                    .put("temperature", 0.6)
                    .put("max_tokens", 300)

                val url = URL("${config.baseUrl.trimEnd('/')}/chat/completions")
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 30_000
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
                        throw IllegalStateException("分析失败($code): ${err.take(120)}")
                    }
                    val text = connection.inputStream
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    val json = runCatching { JSONObject(text) }.getOrNull()
                        ?: throw IllegalStateException("分析失败：响应异常")
                    val style = json.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "")
                        ?.trim()
                        ?.take(400)
                        ?.takeIf { it.isNotEmpty() }
                        ?: throw IllegalStateException("分析失败：结果为空")
                    main.post { onResult(style) }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "analyze failed: ${e.message}")
                main.post { onError(e.message ?: "分析失败") }
            }
        }.start()
    }
}
