package com.koyo.screenwarden

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** MiniMax TTS 手机直连客户端（不经过电脑网关）。 */
object TiyoMiniMaxTts {

    private const val API_URL = "https://api.minimaxi.com/v1/t2a_v2"
    private const val VOICE_LIST_URL = "https://api.minimaxi.com/v1/get_voice"
    private const val MODEL = "speech-2.6-hd"

    /** 一个可用的音色：id 就是合成时填的 voice_id，name 用于界面展示。 */
    data class MiniMaxVoice(val id: String, val name: String)

    /**
     * 列出当前 key 下可用的克隆/生成音色（voice_cloning + voice_generation）。
     * 系统音色太多不拉，默认的"芙宁娜/春日野穹"由客户端内置。
     * 失败返回空列表，调用方回退默认音色。
     */
    fun listVoices(apiKey: String): List<MiniMaxVoice> {
        if (apiKey.isBlank()) return emptyList()
        val body = JSONObject().put("voice_type", "all")
        val connection = (URL(VOICE_LIST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 15_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) return emptyList()
            val text = connection.inputStream
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
            val out = mutableListOf<MiniMaxVoice>()
            for (arrName in listOf("voice_cloning", "voice_generation")) {
                val arr = json.optJSONArray(arrName) ?: continue
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("voice_id").orEmpty()
                    if (id.isBlank()) continue
                    val name = obj.optString("voice_name")
                        .ifBlank { obj.optString("description") }
                        .ifBlank { id }
                    out.add(MiniMaxVoice(id, name))
                }
            }
            out
        } finally {
            connection.disconnect()
        }
    }

    /** 合成一句文本，返回 MP3 字节。失败抛异常。 */
    fun synthesize(text: String, voiceId: String, apiKey: String): ByteArray {
        if (apiKey.isBlank()) throw IllegalStateException("未配置 MiniMax 语音 Key")
        val body = JSONObject()
            .put("model", MODEL)
            .put("text", text)
            .put("stream", false)
            .put("language_boost", "Chinese")
            .put(
                "voice_setting", JSONObject()
                    .put("voice_id", voiceId)
                    .put("speed", 1.0)
                    .put("vol", 1.0)
                    .put("pitch", 0)
                    .put("text_normalization", true)
            )
            .put(
                "audio_setting", JSONObject()
                    .put("sample_rate", 32000)
                    .put("bitrate", 128000)
                    .put("format", "mp3")
                    .put("channel", 1)
            )
            .put("output_format", "hex")

        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 60_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("MiniMax 语音失败 $code: ${response.take(120)}")
            }
            val result = JSONObject(response)
            val data = result.optJSONObject("data")
            val audioHex = data?.optString("audio")
                ?: result.optString("audio")
            if (audioHex.isNullOrBlank()) {
                throw IllegalStateException("MiniMax 语音返回空音频")
            }
            return hexToBytes(audioHex)
        } finally {
            connection.disconnect()
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.filter { !it.isWhitespace() }
        if (clean.length % 2 != 0) throw IllegalStateException("MiniMax 音频数据长度异常")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
