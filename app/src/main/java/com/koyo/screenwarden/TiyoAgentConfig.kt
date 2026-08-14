package com.koyo.screenwarden

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

data class TiyoProviderConfig(
    val id: String = TiyoAgentConfig.PROVIDER_ID,
    val baseUrl: String,
    val model: String,
    val permissionMode: String
)

/** 多 provider 配置：SharedPreferences 存 provider 列表 + active id，API Key 按 provider 走加密存储。 */
object TiyoAgentConfig {

    private const val PREFS_NAME = "tiyo_agent_config"
    private const val KEY_PROVIDERS = "providers_json"
    private const val KEY_ACTIVE = "active_provider_id"
    // 旧版单值字段（仅迁移用）
    private const val KEY_BASE_URL = "provider_base_url"
    private const val KEY_MODEL = "provider_model"
    private const val KEY_PERMISSION = "permission_mode"
    private const val SECRET_PROVIDER_KEY = "provider_api_key"
    private const val SECRET_RUNTIME_TOKEN = "runtime_auth_token"
    private const val SECRET_MINIMAX_TTS_KEY = "minimax_tts_api_key"
    private const val SECRET_IMAGE_GEN_KEY = "image_gen_api_key"
    private const val KEY_IMAGE_GEN_PROVIDER = "image_gen_provider"
    private const val KEY_IMAGE_GEN_BASE_URL = "image_gen_base_url"
    private const val KEY_IMAGE_GEN_MODEL = "image_gen_model"

    const val PROVIDER_ID = "tiyo"
    const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
    const val DEFAULT_MODEL = "deepseek-v4-flash"

    /** 所有已配置 provider；没有则返回默认 tiyo 一个。 */
    fun loadAll(context: Context): List<TiyoProviderConfig> {
        migrateLegacy(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROVIDERS, null)
        if (raw.isNullOrBlank()) return listOf(defaultProvider())
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        TiyoProviderConfig(
                            id = item.optString("id", PROVIDER_ID),
                            baseUrl = item.optString("baseUrl", DEFAULT_BASE_URL),
                            model = item.optString("model", DEFAULT_MODEL),
                            permissionMode = item.optString("permissionMode", "ask")
                        )
                    )
                }
            }.ifEmpty { listOf(defaultProvider()) }
        } catch (_: Exception) {
            listOf(defaultProvider())
        }
    }

    /** 当前 active provider（无匹配则第一个）。 */
    fun load(context: Context): TiyoProviderConfig {
        val providers = loadAll(context)
        val activeId = activeId(context)
        return providers.firstOrNull { it.id == activeId } ?: providers.first()
    }

    fun activeId(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE, PROVIDER_ID).orEmpty().ifBlank { PROVIDER_ID }
    }

    fun setActive(context: Context, id: String): Boolean {
        if (loadAll(context).none { it.id == id }) return false
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE, id).apply()
        return true
    }

    /** 保存到 active provider（兼容旧调用：更新 active 的 baseUrl/model）。 */
    fun save(
        context: Context,
        baseUrl: String,
        model: String,
        apiKey: String?,
        permissionMode: String
    ) {
        saveAs(context, activeId(context), baseUrl, model, apiKey, permissionMode)
    }

    /** 新增/更新指定 id 的 provider，并设为 active。 */
    fun saveAs(
        context: Context,
        id: String,
        baseUrl: String,
        model: String,
        apiKey: String?,
        permissionMode: String
    ) {
        val providers = loadAll(context).toMutableList()
        val safeId = id.trim().ifBlank { PROVIDER_ID }
        val updated = TiyoProviderConfig(
            id = safeId,
            baseUrl = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL },
            model = model.trim().ifBlank { DEFAULT_MODEL },
            permissionMode = permissionMode.takeIf { it in setOf("ask", "auto", "full") } ?: "ask"
        )
        val existingIndex = providers.indexOfFirst { it.id == safeId }
        if (existingIndex >= 0) {
            providers[existingIndex] = updated
        } else {
            providers.add(updated)
        }
        writeProviders(context, providers)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE, safeId).apply()
        apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let {
            TiyoSecureStore.put(context, secretKey(safeId), it)
        }
    }

    fun providerKey(context: Context): String =
        providerKey(context, activeId(context))

    /** MiniMax TTS 语音 Key（手机直连，不依赖网关）。 */
    fun ttsApiKey(context: Context): String =
        TiyoSecureStore.get(context, SECRET_MINIMAX_TTS_KEY)

    fun saveTtsApiKey(context: Context, key: String) {
        if (key.isNotBlank()) {
            TiyoSecureStore.put(context, SECRET_MINIMAX_TTS_KEY, key.trim())
        }
    }

    /** 生图 API Key（豆包 seedream / gpt-image，手机直连）。 */
    fun imageGenKey(context: Context): String =
        TiyoSecureStore.get(context, SECRET_IMAGE_GEN_KEY)

    fun saveImageGenKey(context: Context, key: String) {
        if (key.isNotBlank()) {
            TiyoSecureStore.put(context, SECRET_IMAGE_GEN_KEY, key.trim())
        }
    }

    /** 生图 provider：glm（默认，cogview 免费国内直连）/ seedream / gpt-image / gemini。 */
    fun imageGenProvider(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_IMAGE_GEN_PROVIDER, "glm").orEmpty()
            .ifBlank { "glm" }

    fun saveImageGenProvider(context: Context, provider: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_IMAGE_GEN_PROVIDER, provider.trim()).apply()
    }

    /** 生图 API 地址（仅 gpt-image 用，留空时使用 OpenAI 官方地址）。 */
    fun imageGenBaseUrl(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_IMAGE_GEN_BASE_URL, "").orEmpty().trim()

    fun saveImageGenBaseUrl(context: Context, baseUrl: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_IMAGE_GEN_BASE_URL, baseUrl.trim()).apply()
    }

    /** 生图模型（仅 gpt-image 用，默认 gpt-image-1）。 */
    fun imageGenModel(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_IMAGE_GEN_MODEL, "").orEmpty().trim()

    fun saveImageGenModel(context: Context, model: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_IMAGE_GEN_MODEL, model.trim()).apply()
    }

    fun providerKey(context: Context, id: String): String =
        TiyoSecureStore.get(context, secretKey(id))

    fun isConfigured(context: Context): Boolean {
        val config = load(context)
        return config.baseUrl.isNotBlank() && config.model.isNotBlank() &&
            providerKey(context).isNotBlank()
    }

    fun runtimeToken(context: Context): String {
        TiyoSecureStore.get(context, SECRET_RUNTIME_TOKEN).takeIf { it.isNotBlank() }
            ?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        TiyoSecureStore.put(context, SECRET_RUNTIME_TOKEN, token)
        return token
    }

    private fun defaultProvider() = TiyoProviderConfig(
        id = PROVIDER_ID,
        baseUrl = DEFAULT_BASE_URL,
        model = DEFAULT_MODEL,
        permissionMode = "ask"
    )

    private fun writeProviders(context: Context, providers: List<TiyoProviderConfig>) {
        val array = JSONArray()
        providers.forEach { p ->
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("baseUrl", p.baseUrl)
                    .put("model", p.model)
                    .put("permissionMode", p.permissionMode)
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROVIDERS, array.toString()).apply()
    }

    /** 旧版单值配置 + 单 key 迁移到多 provider 结构（只跑一次）。 */
    private fun migrateLegacy(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_PROVIDERS)) return
        val legacyBase = prefs.getString(KEY_BASE_URL, null)
        if (legacyBase.isNullOrBlank()) return
        val legacyModel = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty()
        val legacyPerm = prefs.getString(KEY_PERMISSION, "ask").orEmpty()
        writeProviders(
            context,
            listOf(
                TiyoProviderConfig(
                    PROVIDER_ID,
                    legacyBase,
                    legacyModel,
                    legacyPerm.takeIf { it in setOf("ask", "auto", "full") } ?: "ask"
                )
            )
        )
        prefs.edit().putString(KEY_ACTIVE, PROVIDER_ID).apply()
        val legacyKey = TiyoSecureStore.get(context, SECRET_PROVIDER_KEY)
        if (legacyKey.isNotBlank()) {
            TiyoSecureStore.put(context, secretKey(PROVIDER_ID), legacyKey)
        }
    }

    private fun secretKey(id: String) = "provider_api_key_$id"
}
