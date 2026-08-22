package com.koyo.screenwarden.presence

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class QqNativePresenceAdapter : PresenceAdapter {
    override val channel = PresenceChannel.QQ
    override val level = PresenceAdapterLevel.OFFICIAL_BOT
    override val capabilities = setOf(
        PresenceCapability.RECEIVE_TEXT,
        PresenceCapability.RECEIVE_MEDIA,
        PresenceCapability.SEND_TEXT,
        PresenceCapability.NATIVE_IDENTITY
    )
    override val availability = PresenceAvailability.AVAILABLE

    private data class ReplyContext(
        val type: String,
        val targetId: String,
        val eventMessageId: String
    )

    private val running = AtomicBoolean(false)
    private val sequence = AtomicLong()
    private val replyContexts = ConcurrentHashMap<String, ReplyContext>()
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "tiyo-qq-native").apply { isDaemon = true }
    }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var heartbeatTask: ScheduledFuture<*>? = null
    @Volatile private var heartbeatIntervalMs = 41_250L
    @Volatile private var lastSequence: Long? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var credentials: MobileChannelCredentials? = null
    @Volatile private var accessToken = ""
    @Volatile private var tokenExpiresAt = 0L
    @Volatile private var currentHealth = AdapterHealth(false, "disabled")
    @Volatile private var reconnectDelaySeconds = 1L

    override fun start(context: Context) {
        val app = context.applicationContext
        if (!MobilePresenceConfig.isReady(app, channel) || !running.compareAndSet(false, true)) return
        appContext = app
        credentials = MobilePresenceConfig.credentials(app, channel)
        reconnectDelaySeconds = 1L
        executor.execute(::connect)
    }

    override fun stop(context: Context) {
        running.set(false)
        heartbeatTask?.cancel(false)
        socket?.close(1000, "stopped")
        socket = null
        currentHealth = AdapterHealth(false, "stopped")
    }

    override fun health(): AdapterHealth = currentHealth

    override suspend fun send(reply: AdapterOutboundMessage): Boolean {
        val auth = credentials ?: return false
        val replyContext = reply.replyToMessageId?.let(replyContexts::get) ?: return false
        val token = ensureToken(auth) ?: return false
        val base = if (auth.secondaryId.equals("sandbox", true)) SANDBOX_API else PRODUCTION_API
        val path = if (replyContext.type == "group") {
            "/v2/groups/${replyContext.targetId}/messages"
        } else {
            "/v2/users/${replyContext.targetId}/messages"
        }
        val body = JSONObject()
            .put("content", reply.text.take(2_000))
            .put("msg_type", 0)
            .put("msg_id", replyContext.eventMessageId)
            .put("msg_seq", 1)
        return requestJson("POST", "$base$path", token, body) != null
    }

    private fun connect() {
        if (!running.get()) return
        val auth = credentials ?: return
        currentHealth = AdapterHealth(false, "connecting")
        val token = ensureToken(auth) ?: run {
            scheduleReconnect()
            return
        }
        val base = if (auth.secondaryId.equals("sandbox", true)) SANDBOX_API else PRODUCTION_API
        val gateway = requestJson("GET", "$base/gateway/bot", token, null)
            ?.optString("url")?.takeIf(String::isNotBlank) ?: run {
            scheduleReconnect()
            return
        }
        socket = http.newWebSocket(Request.Builder().url(gateway).build(), listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val payload = runCatching { JSONObject(text) }.getOrNull() ?: return
            if (payload.has("s")) lastSequence = payload.optLong("s")
            when (payload.optInt("op", -1)) {
                10 -> {
                    heartbeatIntervalMs = payload.optJSONObject("d")
                        ?.optLong("heartbeat_interval", 41_250L)?.coerceAtLeast(5_000L) ?: 41_250L
                    startHeartbeat(webSocket)
                    val token = accessToken.takeIf(String::isNotBlank) ?: return
                    webSocket.send(
                        JSONObject()
                            .put("op", 2)
                            .put(
                                "d",
                                JSONObject()
                                    .put("token", "QQBot $token")
                                    .put("intents", (1 shl 25) or (1 shl 26))
                                    .put("shard", JSONArray().put(0).put(1))
                            ).toString()
                    )
                }
                0 -> when (payload.optString("t")) {
                    "READY", "RESUMED" -> {
                        reconnectDelaySeconds = 1L
                        currentHealth = AdapterHealth(true, "phone connected", lastStartedAt = System.currentTimeMillis())
                    }
                    "GROUP_AT_MESSAGE_CREATE" -> handleMessage(payload.optJSONObject("d"), true)
                    "C2C_MESSAGE_CREATE" -> handleMessage(payload.optJSONObject("d"), false)
                }
                1 -> sendHeartbeat(webSocket)
                7, 9 -> webSocket.close(1012, "reconnect")
                11 -> Unit
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            heartbeatTask?.cancel(false)
            currentHealth = AdapterHealth(false, "connection failed", t.javaClass.simpleName)
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            heartbeatTask?.cancel(false)
            if (running.get()) scheduleReconnect()
        }
    }

    private fun handleMessage(data: JSONObject?, group: Boolean) {
        val app = appContext ?: return
        val auth = credentials ?: return
        val rawId = data?.optString("id")?.takeIf(String::isNotBlank) ?: return
        val author = data.optJSONObject("author") ?: return
        val userId = author.optString(if (group) "member_openid" else "user_openid")
        if (userId.isBlank() || !MobilePresenceConfig.allowsOrBindFirst(app, channel, userId)) return
        val targetId = if (group) data.optString("group_openid") else userId
        if (targetId.isBlank()) return
        val text = data.optString("content")
            .replace(Regex("^<@!?[^>]+>\\s*"), "")
            .trim().take(4_000)
        val eventId = stablePresenceId("qq", rawId)
        val attachments = saveImageAttachments(app, eventId, data.optJSONArray("attachments"))
        if (text.isBlank() && attachments.isEmpty()) return
        replyContexts[eventId] = ReplyContext(if (group) "group" else "c2c", targetId, rawId)
        PresenceRouter.publish(
            app,
            PresenceEvent(
                id = eventId,
                channel = channel,
                direction = PresenceDirection.TO_COMPANION,
                modality = when {
                    attachments.isEmpty() -> PresenceModality.TEXT
                    text.isBlank() -> PresenceModality.IMAGE
                    else -> PresenceModality.COMPOSITE
                },
                sourceLabel = "QQ 官方机器人",
                text = text.takeIf(String::isNotBlank),
                attachments = attachments,
                conversationKey = (if (group) "g:$targetId:$userId" else userId).take(160),
                explicitUserAction = true,
                occurredAt = System.currentTimeMillis()
            )
        )
        currentHealth = currentHealth.copy(healthy = true, detail = "phone connected", lastMessageAt = System.currentTimeMillis())
    }

    private fun saveImageAttachments(context: Context, eventId: String, raw: JSONArray?): List<PresenceAttachment> {
        if (raw == null) return emptyList()
        return buildList {
            for (index in 0 until minOf(raw.length(), 4)) {
                val item = raw.optJSONObject(index) ?: continue
                val url = item.optString("url").takeIf { it.startsWith("https://") } ?: continue
                val contentType = item.optString("content_type").lowercase()
                val looksLikeImage = contentType.startsWith("image/") ||
                    Regex("\\.(png|jpe?g|webp|gif)$").containsMatchIn(
                        url.substringBefore('?').lowercase()
                    )
                if (!looksLikeImage) continue
                val bytes = runCatching {
                    http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        response.body?.bytes()?.takeIf { it.size in 1..(5 * 1024 * 1024) }
                    }
                }.getOrNull() ?: continue
                val target = PresencePrivateMediaStore.write(context, eventId, index, "jpg", bytes)
                add(PresenceAttachment(target.absolutePath, "QQ 图片", "image/jpeg", bytes.size.toLong()))
            }
        }
    }

    private fun ensureToken(auth: MobileChannelCredentials): String? = synchronized(this) {
        if (accessToken.isNotBlank() && System.currentTimeMillis() < tokenExpiresAt - 300_000L) return accessToken
        val response = runCatching {
            val body = JSONObject().put("appId", auth.primaryId).put("clientSecret", auth.secret)
            val request = Request.Builder()
                .url(TOKEN_URL)
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(request).execute().use { httpResponse ->
                if (!httpResponse.isSuccessful) return@use null
                httpResponse.body?.string()?.let(::JSONObject)
            }
        }.getOrNull() ?: return null
        accessToken = response.optString("access_token")
        tokenExpiresAt = System.currentTimeMillis() + response.optString("expires_in").toLongOrNull().orEmptySeconds() * 1_000L
        accessToken.takeIf(String::isNotBlank)
    }

    private fun Long?.orEmptySeconds(): Long = this?.takeIf { it > 0 } ?: 7_200L

    private fun requestJson(method: String, url: String, token: String, body: JSONObject?): JSONObject? = runCatching {
        val builder = Request.Builder().url(url).header("Authorization", "QQBot $token")
        if (method == "POST") {
            builder.post((body ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        } else builder.get()
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()?.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
        }
    }.getOrNull()

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeatTask?.cancel(false)
        heartbeatTask = executor.scheduleWithFixedDelay(
            { if (running.get() && socket === webSocket) sendHeartbeat(webSocket) },
            heartbeatIntervalMs,
            heartbeatIntervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun sendHeartbeat(webSocket: WebSocket) {
        webSocket.send(JSONObject().put("op", 1).put("d", lastSequence ?: JSONObject.NULL).toString())
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        val delay = reconnectDelaySeconds.coerceAtMost(60L)
        reconnectDelaySeconds = (delay * 2).coerceAtMost(60L)
        executor.schedule({ if (running.get() && socket == null) connect() }, delay, TimeUnit.SECONDS)
    }

    companion object {
        private const val TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken"
        private const val PRODUCTION_API = "https://api.sgroup.qq.com"
        private const val SANDBOX_API = "https://sandbox.api.sgroup.qq.com"
    }
}
