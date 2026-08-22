package com.koyo.screenwarden

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内置识图：不用用户填 key，开箱即用。
 *
 * 优先智谱 GLM-4.6V-Flash（免费，偶尔限流 1305），失败自动回退 Agnes 2.0-flash（免费）。
 * 两个 key 都是项目内置的免费额度，不做任何配置 UI。
 */
object BuiltinVision {

    private const val TAG = "BuiltinVision"

    // 智谱（优先）
    private const val GLM_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
    private const val GLM_MODEL = "glm-4.6v-flash"
    private val GLM_KEY: String
        get() = BuildConfig.TIYO_GLM_API_KEY

    // Agnes（兜底）
    private const val AGNES_URL = "https://apihub.agnes-ai.com/v1/chat/completions"
    private const val AGNES_MODEL = "agnes-2.0-flash"
    private val AGNES_KEY: String
        get() = BuildConfig.TIYO_AGNES_API_KEY

    /** 从相册 URI 读取图片，压缩转 base64 后识别，返回中文描述 */
    fun recognizeUri(context: Context, uri: Uri): String {
        val dataUrl = loadImageDataUrl(context, uri)
            ?: return "图片读取失败"
        return recognize(dataUrl)
    }

    /** 读相册 URI → 压缩 → 纯 base64（不含 data: 前缀），供改图/生图用 */
    fun uriToBase64(context: Context, uri: Uri): String? {
        val dataUrl = loadImageDataUrl(context, uri) ?: return null
        return dataUrl.substringAfter("base64,")
    }

    /** 识别 base64 图片：优先智谱，失败回退 Agnes */
    fun recognize(dataUrl: String): String {
        return recognize(dataUrl, "请用中文简短描述这张图片的内容，两三句话即可，别太长")
    }

    /** Backward-compatible entry used by the public chat UI. */
    fun recognize(context: Context, dataUrl: String): String = recognize(dataUrl)

    /** Backward-compatible custom vision task entry used by screen companion capture. */
    fun recognize(context: Context, dataUrl: String, prompt: String): String = recognize(dataUrl, prompt)

    /** 自定义视觉任务，供知情式单帧感知使用 */
    fun recognize(dataUrl: String, prompt: String): String {
        val glm = runCatching {
            callVision(GLM_URL, GLM_MODEL, GLM_KEY, dataUrl, prompt)
        }.getOrNull().orEmpty().trim()
        if (glm.isNotBlank()) return glm
        Log.w(TAG, "glm vision failed, fallback to agnes")
        val agnes = runCatching {
            callVision(AGNES_URL, AGNES_MODEL, AGNES_KEY, dataUrl, prompt)
        }.getOrNull().orEmpty().trim()
        return agnes.ifBlank { "识别失败，稍后再试" }
    }

    private fun callVision(
        url: String,
        model: String,
        key: String,
        dataUrl: String,
        prompt: String
    ): String {
        val payload = JSONObject()
            .put("model", model)
            .put(
                "messages", JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content", JSONArray()
                            .put(
                                JSONObject().put("type", "image_url")
                                    .put("image_url", JSONObject().put("url", dataUrl))
                            )
                            .put(
                                JSONObject().put("type", "text")
                                    .put("text", prompt.take(2_000))
                            )
                    )
                )
            )
            .put("max_tokens", 200)

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 25_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $key")
            val body = payload.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "$model http $code")
                return ""
            }
            val text = conn.inputStream
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return JSONObject(text).optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim().orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    /** Bitmap → 最长边和字节双重压缩 → JPEG data URL，不落盘 */
    fun bitmapToDataUrl(
        bitmap: Bitmap,
        maxDim: Int = 1024,
        quality: Int = 72,
        maxBytes: Int = 256 * 1024
    ): String? {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return null
            val scale = Math.min(1f, maxDim.toFloat() / Math.max(w, h))
            var working = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (w * scale).toInt().coerceAtLeast(1),
                    (h * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else bitmap
            var jpeg = ByteArray(0)
            var currentQuality = quality.coerceIn(50, 85)
            for (attempt in 0 until 6) {
                val bos = ByteArrayOutputStream()
                working.compress(Bitmap.CompressFormat.JPEG, currentQuality, bos)
                jpeg = bos.toByteArray()
                if (jpeg.size <= maxBytes) break
                if (currentQuality > 52) {
                    currentQuality = (currentQuality - 6).coerceAtLeast(52)
                } else if (working.width > 640 || working.height > 640) {
                    val resized = Bitmap.createScaledBitmap(
                        working,
                        (working.width * 0.8f).toInt().coerceAtLeast(1),
                        (working.height * 0.8f).toInt().coerceAtLeast(1),
                        true
                    )
                    if (working !== bitmap) working.recycle()
                    working = resized
                }
            }
            if (working !== bitmap) working.recycle()
            if (jpeg.size > maxBytes) return null
            "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "bitmap conversion failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /** 读 URI → 最长边与字节双重压缩，避免网关 413。 */
    private fun loadImageDataUrl(context: Context, uri: Uri): String? {
        return try {
            val bmp = context.contentResolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it) } ?: return null
            try {
                bitmapToDataUrl(bmp, maxDim = 1024, quality = 72, maxBytes = 256 * 1024)
            } finally {
                bmp.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "load image failed", e)
            null
        }
    }
}
