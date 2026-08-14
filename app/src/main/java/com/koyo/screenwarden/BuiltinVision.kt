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
 * Uses the vision-capable provider configured by the user.
 * Credentials are read from [TiyoSecureStore] and are never bundled in the APK.
 */
object BuiltinVision {

    private const val TAG = "BuiltinVision"

    fun recognizeUri(context: Context, uri: Uri): String {
        val dataUrl = loadImageDataUrl(context, uri) ?: return "图片读取失败"
        return recognize(context, dataUrl)
    }

    /** Reads and compresses an image without writing it to disk. */
    fun uriToBase64(context: Context, uri: Uri): String? {
        val dataUrl = loadImageDataUrl(context, uri) ?: return null
        return dataUrl.substringAfter("base64,")
    }

    fun recognize(context: Context, dataUrl: String): String = recognize(
        context,
        dataUrl,
        "请用中文简短描述这张图片的内容，两三句话即可，别太长"
    )

    fun recognize(context: Context, dataUrl: String, prompt: String): String {
        val config = TiyoAgentConfig.load(context)
        val key = TiyoAgentConfig.providerKey(context)
        if (key.isBlank()) return "请先配置支持图片的模型和 API Key"

        return runCatching {
            callVision(
                chatCompletionsUrl(config.baseUrl),
                config.model,
                key,
                dataUrl,
                prompt
            )
        }.onFailure { error ->
            Log.w(TAG, "vision request failed: ${error.javaClass.simpleName}")
        }.getOrNull().orEmpty().trim().ifBlank { "识别失败，请检查视觉模型配置" }
    }

    internal fun chatCompletionsUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        return if (normalized.endsWith("/chat/completions")) normalized
        else "$normalized/chat/completions"
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

    fun bitmapToDataUrl(bitmap: Bitmap, maxDim: Int = 960, quality: Int = 72): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return null
            val scale = Math.min(1f, maxDim.toFloat() / Math.max(width, height))
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else bitmap
            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 90), output)
            if (scaled !== bitmap) scaled.recycle()
            "data:image/jpeg;base64," +
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } catch (error: Exception) {
            Log.w(TAG, "bitmap conversion failed: ${error.javaClass.simpleName}")
            null
        }
    }

    private fun loadImageDataUrl(context: Context, uri: Uri): String? {
        return try {
            val bitmap = context.contentResolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it) } ?: return null
            val maxDimension = 1280
            val width = bitmap.width
            val height = bitmap.height
            val scale = Math.min(1f, maxDimension.toFloat() / Math.max(width, height))
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (width * scale).toInt(),
                    (height * scale).toInt(),
                    true
                )
            } else bitmap
            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, output)
            if (scaled !== bitmap) scaled.recycle()
            "data:image/jpeg;base64," +
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } catch (error: Exception) {
            Log.w(TAG, "load image failed", error)
            null
        }
    }
}
