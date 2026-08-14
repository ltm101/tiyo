package com.koyo.screenwarden

import android.util.Base64
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * App 层生图客户端：聊天里直接调生图 API，复用 TiyoAgentConfig 的生图配置。
 *
 * - 文字生图 generate()：gpt-image 走 /v1/images/edits（传 1x1 占位图），其他 provider 走 generations
 * - 改图 editImage()：gpt-image edits 传原图 + prompt
 *
 * 返回 base64 图片（不含 data: 前缀）或 null。
 */
object ImageGenClient {

    private const val TAG = "ImageGenClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // High-fidelity multi-reference edits regularly take longer than two minutes.
        .readTimeout(6, TimeUnit.MINUTES)
        .build()

    data class Capability(
        val configured: Boolean,
        val provider: String,
        val model: String,
        val canGenerate: Boolean,
        val canEditReference: Boolean,
        val canUseMultipleReferences: Boolean,
        val canRequestTransparentBackground: Boolean
    )

    /** Safe for settings UI: never exposes the API key or the private base URL. */
    fun capability(): Capability {
        val cfg = loadCfg()
        val provider = cfg?.provider ?: TiyoAgentConfig.imageGenProvider(TiyoApp.appContext)
        val model = cfg?.model ?: TiyoAgentConfig.imageGenModel(TiyoApp.appContext)
        val configured = cfg != null
        val isGptImage = provider == "gpt-image"
        return Capability(
            configured = configured,
            provider = provider,
            model = model,
            canGenerate = configured,
            canEditReference = configured && isGptImage,
            canUseMultipleReferences = configured && isGptImage,
            canRequestTransparentBackground = configured && isGptImage
        )
    }

    /** 纯文字生图 */
    fun generate(prompt: String): String? {
        return try {
            val cfg = loadCfg() ?: return null
            if (cfg.provider == "gpt-image") {
                callEdits(cfg, prompt, emptyList())
            } else {
                callGenerations(cfg, prompt)
            }
        } catch (e: Exception) {
            Log.w(TAG, "generate failed", e)
            null
        }
    }

    /** 改图：传原图 + prompt，返回改好的图 base64 */
    fun editImage(prompt: String, imageBase64: String): String? {
        return editImages(prompt, listOf(imageBase64))
    }

    /**
     * Reference-preserving edit for companion anchor generation. OpenAI-compatible
     * GPT Image endpoints accept repeated image[] parts; if a proxy rejects that
     * form, retry once with the first reference instead of failing the whole flow.
     */
    fun editImages(
        prompt: String,
        imageBase64List: List<String>,
        size: String = "1536x1024",
        transparentBackground: Boolean = false,
        allowSingleReferenceFallback: Boolean = true
    ): String? {
        return try {
            editImagesRequired(
                prompt,
                imageBase64List,
                size,
                transparentBackground,
                allowSingleReferenceFallback
            )
        } catch (e: Exception) {
            Log.w(TAG, "editImages failed", e)
            null
        }
    }

    /** Same operation as [editImages], but preserves the real failure for user-facing flows. */
    fun editImagesRequired(
        prompt: String,
        imageBase64List: List<String>,
        size: String = "1536x1024",
        transparentBackground: Boolean = false,
        allowSingleReferenceFallback: Boolean = true
    ): String {
        val cfg = loadCfg() ?: error("请先配置生图 API Key")
        require(cfg.provider == "gpt-image") { "请切换到支持参考图编辑的 GPT Image" }
        val references = imageBase64List.filter(String::isNotBlank).take(3)
        require(references.isNotEmpty()) { "没有可以上传的参考图" }
        return try {
            callEdits(cfg, prompt, references, size, transparentBackground)
        } catch (error: ImageGenHttpException) {
            val proxyMayRejectImageArray = error.statusCode in setOf(400, 404, 415, 422)
            if (!allowSingleReferenceFallback || references.size <= 1 || !proxyMayRejectImageArray) {
                throw error
            }
            callEdits(cfg, prompt, listOf(references.first()), size, transparentBackground)
        }
    }

    private data class Cfg(
        val provider: String,
        val key: String,
        val baseUrl: String,
        val model: String
    )

    private class ImageGenHttpException(
        val statusCode: Int,
        detail: String
    ) : IOException("生图接口返回 $statusCode${detail.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty()}")

    private fun loadCfg(): Cfg? {
        val ctx = TiyoApp.appContext
        val key = TiyoAgentConfig.imageGenKey(ctx)
        if (key.isBlank()) return null
        return Cfg(
            provider = TiyoAgentConfig.imageGenProvider(ctx).ifBlank { "gpt-image" },
            key = key,
            baseUrl = TiyoAgentConfig.imageGenBaseUrl(ctx).ifBlank { "https://api.openai.com" },
            model = TiyoAgentConfig.imageGenModel(ctx).ifBlank { "gpt-image-1" }
        )
    }

    /** gpt-image edits：multipart，带 image（无则 1x1 透明占位） */
    private fun callEdits(
        cfg: Cfg,
        prompt: String,
        imageBase64List: List<String>,
        size: String = "1024x1024",
        transparentBackground: Boolean = false
    ): String {
        val base = cfg.baseUrl.trimEnd('/')
        val url = if (base.endsWith("/v1")) "$base/images/edits" else "$base/v1/images/edits"
        val images = imageBase64List.map { Base64.decode(it, Base64.DEFAULT) }.ifEmpty {
            // 1x1 透明 PNG
            listOf(Base64.decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==",
                Base64.DEFAULT
            ))
        }
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", cfg.model)
            .addFormDataPart("prompt", prompt)
            .addFormDataPart("n", "1")
            .addFormDataPart("size", size)
            .addFormDataPart("output_format", "png")
            .addFormDataPart("input_fidelity", "high")
        if (transparentBackground) builder.addFormDataPart("background", "transparent")
        val fieldName = if (images.size > 1) "image[]" else "image"
        images.forEachIndexed { index, bytes ->
            val mediaType = detectImageMediaType(bytes)
            val extension = when (mediaType) {
                "image/jpeg" -> "jpg"
                "image/webp" -> "webp"
                else -> "png"
            }
            builder.addFormDataPart(
                fieldName,
                "reference-${index + 1}.$extension",
                bytes.toRequestBody(mediaType.toMediaType())
            )
        }
        val body = builder.build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.key}")
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "edits http ${resp.code}: ${text.take(200)}")
                throw ImageGenHttpException(resp.code, text.take(160))
            }
            return parseB64(text, cfg) ?: error("生图服务响应里没有可用图片")
        }
    }

    /** 其他 provider 的 generations（JSON body） */
    private fun callGenerations(cfg: Cfg, prompt: String): String? {
        val url = when (cfg.provider) {
            "glm" -> "https://open.bigmodel.cn/api/paas/v4/images/generations"
            "seedream" -> "https://ark.cn-beijing.volces.com/api/v3/images/generations"
            else -> return null
        }
        val payload = JSONObject()
            .put("model", if (cfg.provider == "glm") "cogview-3-flash" else "doubao-seedream-5-0-260128")
            .put("prompt", prompt)
            .put("size", "1024x1024")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.key}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "generations http ${resp.code}: ${text.take(200)}")
                return null
            }
            return parseB64(text, cfg)
        }
    }

    /** 解析响应里的 b64 图（data[0].b64_json 或 url） */
    private fun parseB64(text: String, cfg: Cfg): String? {
        return try {
            val arr = JSONObject(text).optJSONArray("data") ?: return null
            val first = arr.optJSONObject(0) ?: return null
            first.optString("b64_json").takeIf { it.isNotBlank() }?.let { return it }
            val imageUrl = first.optString("url").takeIf { it.startsWith("http") } ?: return null
            downloadAsBase64(imageUrl, cfg)
        } catch (e: Exception) {
            null
        }
    }

    private fun downloadAsBase64(url: String, cfg: Cfg): String? {
        val builder = Request.Builder()
            .url(url)
            .get()
        val target = url.toHttpUrlOrNull()
        val provider = cfg.baseUrl.toHttpUrlOrNull()
        if (target != null && provider != null &&
            target.scheme == provider.scheme && target.host == provider.host &&
            target.port == provider.port
        ) {
            builder.header("Authorization", "Bearer ${cfg.key}")
        }
        val request = builder.build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }

    private fun detectImageMediaType(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
        else -> "image/png"
    }
}
