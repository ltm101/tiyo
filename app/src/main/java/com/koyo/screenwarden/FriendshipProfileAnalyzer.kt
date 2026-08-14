package com.koyo.screenwarden

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 好友相处档案的低频反射分析
 *
 * 只发送当前这一条来信和已经蒸馏过的档案摘要，不发送历史原文
 * 模型只能提取对方明确表达的内容，结果仍要经过本地敏感字段过滤
 */
object FriendshipProfileAnalyzer {
    private const val TAG = "FriendshipAnalyzer"

    fun analyze(context: Context, identity: FriendshipIdentity, message: String) {
        if (!FriendshipProfileStore.isEnabled(context) || identity.groupLike) return
        if (!TiyoAgentConfig.isConfigured(context)) return
        val config = TiyoAgentConfig.load(context)
        val apiKey = TiyoAgentConfig.providerKey(context)
        if (apiKey.isBlank()) return

        val system = """
            你是本地聊天代拟功能的关系记忆提炼器
            只从对方这一条消息里提取明确说出的相处信息，不做心理诊断，不猜关系，不评价人格
            禁止提取政治、宗教、性取向、健康病历、财务、证件、密码、住址、手机号等敏感属性
            临时状态必须给较短有效期，拿不准就留空
            只输出 JSON：
            {"topics":["至多3个话题"],"traits":["至多2个可观察表达习惯"],"facts":[{"text":"明确事实","ttl_days":90}],"recent_state":"近期明确状态或空字符串","recent_state_ttl_days":7}
        """.trimIndent()
        val profile = FriendshipProfileStore.summaryForPrompt(context, identity.key)
        val user = JSONObject()
            .put("conversation", "${identity.platform}/${identity.displayName}")
            .put("existing_profile", profile)
            .put("incoming_message", message.replace(Regex("[\r\n]+"), " ").trim().take(500))

        val payload = JSONObject()
            .put("model", config.model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user.toString())))
            .put("temperature", 0.1)
            .put("max_tokens", 260)

        try {
            val connection = URL("${config.baseUrl.trimEnd('/')}/chat/completions")
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 5_000
                connection.readTimeout = 20_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                val body = payload.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                if (connection.responseCode !in 200..299) {
                    Log.w(TAG, "profile analysis http ${connection.responseCode}")
                    return
                }
                val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val content = JSONObject(response).optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                FriendshipProfileDeltaParser.parse(content)?.let {
                    FriendshipProfileStore.applyDelta(context, identity.key, it)
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "profile analysis failed: ${e.javaClass.simpleName}")
        }
    }
}
