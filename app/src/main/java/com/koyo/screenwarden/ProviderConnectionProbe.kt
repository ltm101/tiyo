package com.koyo.screenwarden

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.measureTimeMillis

internal data class ProviderProbeResult(
    val ok: Boolean,
    val models: List<String>,
    val latencyMs: Long,
    val message: String
)

internal object ProviderConnectionProbe {
    private const val MAX_RESPONSE_CHARS = 512 * 1024

    fun modelsEndpoint(baseUrl: String): String? {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank() || (!base.startsWith("https://") && !base.startsWith("http://"))) return null
        return "$base/models"
    }

    fun parseModelIds(text: String): List<String> = runCatching {
        val root = JSONObject(text)
        val array = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
        buildList {
            for (index in 0 until minOf(array.length(), 500)) {
                val item = array.opt(index)
                val id = when (item) {
                    is JSONObject -> item.optString("id")
                    is String -> item
                    else -> ""
                }.trim().take(180)
                if (id.isNotBlank() && id !in this) add(id)
            }
        }.sorted()
    }.getOrDefault(emptyList())

    fun discover(baseUrl: String, apiKey: String): ProviderProbeResult {
        val endpoint = modelsEndpoint(baseUrl)
            ?: return ProviderProbeResult(false, emptyList(), 0L, "API 地址格式不正确")
        var code = 0
        var body = ""
        val elapsed = try {
            measureTimeMillis {
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 6_000
                    connection.readTimeout = 8_000
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("Accept", "application/json")
                    if (apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
                    code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(MAX_RESPONSE_CHARS) }.orEmpty()
                } finally {
                    connection.disconnect()
                }
            }
        } catch (error: Exception) {
            return ProviderProbeResult(false, emptyList(), 0L, "连接失败：${error.javaClass.simpleName}")
        }
        if (code !in 200..299) return ProviderProbeResult(false, emptyList(), elapsed, "服务返回 HTTP $code")
        val models = parseModelIds(body)
        return ProviderProbeResult(
            ok = true,
            models = models,
            latencyMs = elapsed,
            message = if (models.isEmpty()) "连接成功，但服务没有返回模型列表" else "连接成功，发现 ${models.size} 个模型"
        )
    }
}
