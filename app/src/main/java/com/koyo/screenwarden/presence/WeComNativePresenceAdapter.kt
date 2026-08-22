package com.koyo.screenwarden.presence

import android.content.Context
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class WeComNativePresenceAdapter : PresenceAdapter {
    override val channel = PresenceChannel.WECOM
    override val level = PresenceAdapterLevel.OFFICIAL_BOT
    override val capabilities = setOf(
        PresenceCapability.RECEIVE_TEXT,
        PresenceCapability.RECEIVE_MEDIA,
        PresenceCapability.SEND_TEXT,
        PresenceCapability.NATIVE_IDENTITY
    )
    override val availability = PresenceAvailability.AVAILABLE

    private data class ReplyContext(val reqId: String, val chatId: String)

    private val running = AtomicBoolean(false)
    private val requestSequence = AtomicLong()
    private val replyContexts = ConcurrentHashMap<String, ReplyContext>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "tiyo-wecom-native").apply { isDaemon = true }
    }
    private val http = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var credentials: MobileChannelCredentials? = null
    @Volatile private var currentHealth = AdapterHealth(false, "disabled")
    @Volatile private var reconnectDelaySeconds = 1L

    override fun start(context: Context) {
        val app = context.applicationContext
        if (!MobilePresenceConfig.isReady(app, channel) || !running.compareAndSet(false, true)) return
        appContext = app
        credentials = MobilePresenceConfig.credentials(app, channel)
        reconnectDelaySeconds = 1L
        connect()
        scheduler.scheduleWithFixedDelay(::heartbeat, 30, 30, TimeUnit.SECONDS)
    }

    override fun stop(context: Context) {
        running.set(false)
        socket?.close(1000, "stopped")
        socket = null
        currentHealth = AdapterHealth(false, "stopped")
    }

    override fun health(): AdapterHealth = currentHealth

    override suspend fun send(reply: AdapterOutboundMessage): Boolean {
        val ws = socket ?: return false
        val original = reply.replyToMessageId?.let(replyContexts::get)
        val reqId = original?.reqId ?: nextId("aibot_send_msg")
        val frame = if (original != null) {
            JSONObject()
                .put("cmd", "aibot_respond_msg")
                .put("headers", JSONObject().put("req_id", reqId))
                .put(
                    "body",
                    JSONObject().put("msgtype", "stream").put(
                        "stream",
                        JSONObject()
                            .put("id", nextId("stream"))
                            .put("finish", true)
                            .put("content", reply.text.take(4_000))
                    )
                )
        } else {
            val chatId = reply.conversationKey?.takeIf(String::isNotBlank) ?: return false
            JSONObject()
                .put("cmd", "aibot_send_msg")
                .put("headers", JSONObject().put("req_id", reqId))
                .put(
                    "body",
                    JSONObject()
                        .put("chatid", chatId)
                        .put("msgtype", "markdown")
                        .put("markdown", JSONObject().put("content", reply.text.take(4_000)))
                )
        }
        return ws.send(frame.toString())
    }

    private fun connect() {
        if (!running.get()) return
        currentHealth = AdapterHealth(false, "connecting")
        val request = Request.Builder().url("wss://openws.work.weixin.qq.com").build()
        socket = http.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            val auth = credentials ?: return
            webSocket.send(
                JSONObject()
                    .put("cmd", "aibot_subscribe")
                    .put("headers", JSONObject().put("req_id", nextId("aibot_subscribe")))
                    .put("body", JSONObject().put("bot_id", auth.primaryId).put("secret", auth.secret))
                    .toString()
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (frame.optString("cmd")) {
                "aibot_msg_callback" -> handleInbound(frame)
                "aibot_event_callback" -> Unit
                "" -> {
                    val reqId = frame.optJSONObject("headers")?.optString("req_id").orEmpty()
                    if (reqId.startsWith("aibot_subscribe") && frame.optInt("errcode", -1) == 0) {
                        reconnectDelaySeconds = 1L
                        currentHealth = AdapterHealth(true, "phone connected", lastStartedAt = System.currentTimeMillis())
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            currentHealth = AdapterHealth(false, "connection failed", t.javaClass.simpleName)
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            if (running.get()) scheduleReconnect()
        }
    }

    private fun handleInbound(frame: JSONObject) {
        val app = appContext ?: return
        val auth = credentials ?: return
        val body = frame.optJSONObject("body") ?: return
        val from = body.optJSONObject("from")?.optString("userid").orEmpty()
        if (from.isBlank() || !MobilePresenceConfig.allowsOrBindFirst(app, channel, from)) return
        val rawMessageId = body.optString("msgid").takeIf(String::isNotBlank) ?: return
        val eventId = stablePresenceId("wecom", rawMessageId)
        val reqId = frame.optJSONObject("headers")?.optString("req_id").orEmpty()
        val chatId = body.optString("chatid").ifBlank { from }
        val msgType = body.optString("msgtype")
        val text = when (msgType) {
            "text" -> body.optJSONObject("text")?.optString("content")
            "voice" -> body.optJSONObject("voice")?.let { it.optString("content").ifBlank { it.optString("text") } }
            else -> null
        }?.trim()?.take(4_000)?.takeIf(String::isNotBlank)
        val attachments = if (msgType == "image") {
            body.optJSONObject("image")?.let { image ->
                downloadImage(app, eventId, image.optString("url"), image.optString("aeskey"))
            }?.let(::listOf).orEmpty()
        } else emptyList()
        if (text == null && attachments.isEmpty()) return
        replyContexts[eventId] = ReplyContext(reqId, chatId)
        PresenceRouter.publish(
            app,
            PresenceEvent(
                id = eventId,
                channel = channel,
                direction = PresenceDirection.TO_COMPANION,
                modality = when {
                    attachments.isEmpty() -> PresenceModality.TEXT
                    text == null -> PresenceModality.IMAGE
                    else -> PresenceModality.COMPOSITE
                },
                sourceLabel = "企业微信机器人",
                text = text,
                attachments = attachments,
                conversationKey = chatId.take(160),
                explicitUserAction = true,
                occurredAt = body.optLong("create_time").let { if (it in 1..9_999_999_999L) it * 1000 else it }
                    .takeIf { it > 0 } ?: System.currentTimeMillis()
            )
        )
        currentHealth = currentHealth.copy(healthy = true, detail = "phone connected", lastMessageAt = System.currentTimeMillis())
    }

    private fun heartbeat() {
        if (!running.get()) return
        socket?.send(
            JSONObject()
                .put("cmd", "ping")
                .put("headers", JSONObject().put("req_id", nextId("ping")))
                .toString()
        )
    }

    private fun downloadImage(context: Context, eventId: String, rawUrl: String, rawKey: String): PresenceAttachment? {
        if (!rawUrl.startsWith("https://") || rawKey.isBlank()) return null
        return runCatching {
            val encrypted = http.newCall(Request.Builder().url(rawUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.bytes()?.takeIf { it.size in 16..(6 * 1024 * 1024) }
            } ?: return@runCatching null
            val key = decodeAesKey(rawKey) ?: return@runCatching null
            if (encrypted.size % 16 != 0) return@runCatching null
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key.copyOfRange(0, 16)))
            val padded = cipher.doFinal(encrypted)
            val pad = padded.last().toInt() and 0xff
            if (pad !in 1..32 || pad > padded.size || padded.takeLast(pad).any { (it.toInt() and 0xff) != pad }) {
                return@runCatching null
            }
            val bytes = padded.copyOf(padded.size - pad)
            if (bytes.size !in 1..(5 * 1024 * 1024)) return@runCatching null
            val target = PresencePrivateMediaStore.write(context, eventId, 0, "jpg", bytes)
            PresenceAttachment(target.absolutePath, "企业微信图片", "image/jpeg", bytes.size.toLong())
        }.getOrNull()
    }

    private fun decodeAesKey(raw: String): ByteArray? = runCatching {
        val compact = raw.filterNot(Char::isWhitespace)
        if (compact.length == 64 && compact.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return@runCatching ByteArray(32) { index -> compact.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        }
        val normalized = compact.replace('-', '+').replace('_', '/').let { value ->
            value + "=".repeat((4 - value.length % 4) % 4)
        }
        Base64.decode(normalized, Base64.DEFAULT).copyOfRange(0, 32)
    }.getOrNull()?.takeIf { it.size == 32 }

    private fun scheduleReconnect() {
        if (!running.get()) return
        val delay = reconnectDelaySeconds.coerceAtMost(30L)
        reconnectDelaySeconds = (delay * 2).coerceAtMost(30L)
        scheduler.schedule({ if (running.get() && socket == null) connect() }, delay, TimeUnit.SECONDS)
    }

    private fun nextId(prefix: String): String = "$prefix-${requestSequence.incrementAndGet()}"
}
