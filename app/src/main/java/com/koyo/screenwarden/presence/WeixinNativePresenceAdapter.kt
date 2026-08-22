package com.koyo.screenwarden.presence

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WeixinNativePresenceAdapter : PresenceAdapter {
    override val channel = PresenceChannel.WECHAT
    override val level = PresenceAdapterLevel.NATIVE_ACCOUNT
    override val capabilities = setOf(
        PresenceCapability.RECEIVE_TEXT,
        PresenceCapability.RECEIVE_MEDIA,
        PresenceCapability.SEND_TEXT,
        PresenceCapability.NATIVE_IDENTITY
    )
    override val availability = PresenceAvailability.AVAILABLE

    private data class ReplyContext(val userId: String, val contextToken: String)

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tiyo-weixin-native").apply { isDaemon = true }
    }
    private val replyContexts = ConcurrentHashMap<String, ReplyContext>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    @Volatile private var appContext: Context? = null
    @Volatile private var credentials: MobileChannelCredentials? = null
    @Volatile private var currentHealth = AdapterHealth(false, "disabled")

    override fun start(context: Context) {
        val app = context.applicationContext
        if (!MobilePresenceConfig.isReady(app, channel) || !running.compareAndSet(false, true)) return
        appContext = app
        credentials = MobilePresenceConfig.credentials(app, channel)
        executor.execute(::pollLoop)
    }

    override fun stop(context: Context) {
        running.set(false)
        currentHealth = AdapterHealth(false, "stopped")
    }

    override fun health(): AdapterHealth = currentHealth

    override suspend fun send(reply: AdapterOutboundMessage): Boolean {
        val app = appContext ?: return false
        val auth = credentials ?: return false
        val context = reply.replyToMessageId?.let(replyContexts::get) ?: return false
        val item = JSONObject()
            .put("type", 1)
            .put("text_item", JSONObject().put("text", reply.text.take(4_000)))
        val body = JSONObject()
            .put(
                "msg",
                JSONObject()
                    .put("from_user_id", "")
                    .put("to_user_id", context.userId)
                    .put("client_id", UUID.randomUUID().toString())
                    .put("message_type", 2)
                    .put("message_state", 2)
                    .put("item_list", JSONArray().put(item))
                    .put("context_token", context.contextToken)
            )
            .put("base_info", JSONObject().put("channel_version", "tiyo-android/1.0"))
        return post(app, auth, "ilink/bot/sendmessage", body, 20_000)
            ?.optInt("ret", 0) == 0
    }

    private fun pollLoop() {
        val app = appContext ?: return
        val auth = credentials ?: return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var cursor = prefs.getString(KEY_CURSOR, "").orEmpty()
        var backoff = 1_000L
        while (running.get()) {
            val response = post(
                app,
                auth,
                "ilink/bot/getupdates",
                JSONObject()
                    .put("get_updates_buf", cursor)
                    .put("base_info", JSONObject().put("channel_version", "tiyo-android/1.0")),
                42_000
            )
            if (response == null || response.optInt("ret", -1) != 0) {
                currentHealth = AdapterHealth(false, "connection failed")
                Thread.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
                continue
            }
            backoff = 1_000L
            currentHealth = currentHealth.copy(healthy = true, detail = "phone connected")
            val messages = response.optJSONArray("msgs") ?: JSONArray()
            for (index in 0 until messages.length()) {
                messages.optJSONObject(index)?.let { handleInbound(app, auth, it) }
            }
            response.optString("get_updates_buf").takeIf(String::isNotBlank)?.let {
                cursor = it
                prefs.edit().putString(KEY_CURSOR, it).apply()
            }
        }
    }

    private fun handleInbound(context: Context, auth: MobileChannelCredentials, raw: JSONObject) {
        if (raw.optInt("message_type") !in setOf(0, 1)) return
        val userId = raw.optString("from_user_id").takeIf(String::isNotBlank) ?: return
        if (!MobilePresenceConfig.allowsOrBindFirst(context, channel, userId)) return
        val tokenPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tokenKey = "context_${userId.hashCode().toUInt().toString(16)}"
        val contextToken = raw.optString("context_token").takeIf(String::isNotBlank)?.also {
            tokenPrefs.edit().putString(tokenKey, it).apply()
        } ?: tokenPrefs.getString(tokenKey, "").orEmpty()
        val rawId = raw.optString("message_id").ifBlank { raw.optString("client_id") }
        if (rawId.isBlank()) return
        val items = raw.optJSONArray("item_list") ?: JSONArray()
        val textParts = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                when (item.optInt("type")) {
                    1 -> item.optJSONObject("text_item")?.optString("text")
                    3 -> item.optJSONObject("voice_item")?.optString("text")
                    else -> null
                }?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            }
        }
        val text = textParts.joinToString("\n").take(4_000).takeIf(String::isNotBlank)
        val eventId = stablePresenceId("weixin", rawId)
        val attachments = downloadImages(context, eventId, items)
        if (text == null && attachments.isEmpty()) return
        if (contextToken.isNotBlank()) replyContexts[eventId] = ReplyContext(userId, contextToken)
        PresenceRouter.publish(
            context,
            PresenceEvent(
                id = eventId,
                channel = channel,
                direction = PresenceDirection.TO_COMPANION,
                modality = when {
                    attachments.isEmpty() -> PresenceModality.TEXT
                    text == null -> PresenceModality.IMAGE
                    else -> PresenceModality.COMPOSITE
                },
                sourceLabel = "微信独立联系人",
                text = text,
                attachments = attachments,
                conversationKey = raw.optString("session_id").ifBlank { userId }.take(160),
                explicitUserAction = true,
                occurredAt = raw.optLong("create_time_ms", System.currentTimeMillis())
            )
        )
        currentHealth = currentHealth.copy(healthy = true, detail = "phone connected", lastMessageAt = System.currentTimeMillis())
    }

    private fun post(
        context: Context,
        auth: MobileChannelCredentials,
        endpoint: String,
        body: JSONObject,
        timeoutMs: Int
    ): JSONObject? = runCatching {
        val client = http.newBuilder().readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS).build()
        val uin = ByteArray(4).also(SecureRandom()::nextBytes)
            .fold(0L) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xff).toLong() }
            .toString()
        val request = Request.Builder()
            .url("${auth.secondaryId.takeIf { it.startsWith("https://") }?.trimEnd('/') ?: "https://ilinkai.weixin.qq.com"}/${endpoint.trimStart('/')}")
            .header("AuthorizationType", "ilink_bot_token")
            .header("Authorization", "Bearer ${auth.secret}")
            .header("X-WECHAT-UIN", Base64.encodeToString(uin.toByteArray(), Base64.NO_WRAP))
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()?.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
        }
    }.getOrNull()

    private fun downloadImages(context: Context, eventId: String, items: JSONArray): List<PresenceAttachment> = buildList {
        for (index in 0 until minOf(items.length(), 4)) {
            val item = items.optJSONObject(index) ?: continue
            if (item.optInt("type") != 2) continue
            val image = item.optJSONObject("image_item") ?: continue
            val media = image.optJSONObject("media") ?: continue
            val encryptedParam = media.optString("encrypt_query_param").takeIf(String::isNotBlank) ?: continue
            val rawKey = image.optString("aeskey").takeIf(String::isNotBlank)
                ?: media.optString("aes_key").takeIf(String::isNotBlank)
            val bytes = runCatching {
                val url = "https://novac2c.cdn.weixin.qq.com/c2c/download?encrypted_query_param=" +
                    java.net.URLEncoder.encode(encryptedParam, "UTF-8")
                val encrypted = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.bytes()?.takeIf { it.size in 1..(6 * 1024 * 1024) }
                } ?: return@runCatching null
                if (rawKey.isNullOrBlank()) encrypted else decryptCdn(encrypted, rawKey)
            }.getOrNull() ?: continue
            if (bytes.size !in 1..(5 * 1024 * 1024)) continue
            val mime = detectImageMime(bytes)
            val extension = when (mime) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "jpg"
            }
            val target = PresencePrivateMediaStore.write(context, eventId, index, extension, bytes)
            add(PresenceAttachment(target.absolutePath, "微信图片", mime, bytes.size.toLong()))
        }
    }

    @SuppressLint("GetInstance") // Tencent iLink CDN protocol requires AES-128-ECB for media interoperability.
    private fun decryptCdn(ciphertext: ByteArray, rawKey: String): ByteArray? = runCatching {
        val key = if (rawKey.matches(Regex("[0-9a-fA-F]{32}"))) {
            ByteArray(16) { index -> rawKey.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        } else {
            val decoded = Base64.decode(rawKey, Base64.DEFAULT)
            when {
                decoded.size == 16 -> decoded
                decoded.size == 32 && String(decoded).matches(Regex("[0-9a-fA-F]{32}")) ->
                    ByteArray(16) { index -> String(decoded).substring(index * 2, index * 2 + 2).toInt(16).toByte() }
                else -> return@runCatching null
            }
        }
        if (ciphertext.size % 16 != 0) return@runCatching null
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val padded = cipher.doFinal(ciphertext)
        val pad = padded.last().toInt() and 0xff
        if (pad !in 1..16 || pad > padded.size || padded.takeLast(pad).any { (it.toInt() and 0xff) != pad }) {
            return@runCatching null
        }
        padded.copyOf(padded.size - pad)
    }.getOrNull()

    private fun detectImageMime(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
        bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> "image/jpeg"
    }

    companion object {
        private const val PREFS = "weixin_native_presence"
        private const val KEY_CURSOR = "updates_cursor"
    }
}
