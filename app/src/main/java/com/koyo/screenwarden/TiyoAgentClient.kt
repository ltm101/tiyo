package com.koyo.screenwarden

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class TiyoAgentClient(
    private val listener: Listener
) {
    interface Listener {
        fun onAgentState(connected: Boolean, label: String)
        fun onAgentEvent(event: JSONObject)
        fun onAgentError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var runtime: TiyoAgentRuntimeInfo? = null
    private var provider: TiyoProviderConfig? = null
    private var sessionId = UUID.randomUUID().toString()

    @Volatile private var open = false

    fun isOpen(): Boolean = open

    fun connect(
        info: TiyoAgentRuntimeInfo,
        config: TiyoProviderConfig,
        persistedSessionId: String?
    ) {
        if (open && runtime?.port == info.port) return
        close()
        runtime = info
        provider = config
        sessionId = persistedSessionId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        mainHandler.post { listener.onAgentState(false, "正在唤醒 Tiyo Agent") }

        Thread {
            try {
                // 新版 agent 用 token 认证（Bearer / ?token=），不再用旧的 __tiyo/auth cookie
                val request = Request.Builder()
                    .url("ws://127.0.0.1:${info.port}/ws/session/$sessionId?token=${info.authToken}")
                    .header("Origin", "http://127.0.0.1:${info.port}")
                    .build()
                webSocket = http.newWebSocket(request, socketListener)
            } catch (error: Exception) {
                mainHandler.post {
                    listener.onAgentError(
                        error.message?.takeIf { it.isNotBlank() } ?: "无法连接 Tiyo Agent"
                    )
                }
            }
        }.start()
    }

    fun currentSessionId(): String = sessionId

    fun sendMessage(text: String): Boolean =
        send(JSONObject().put("command", "send_message").put("text", text))

    /** 带图发送：原图与用户问题一起交给 Agent 的统一视觉路由 */
    fun sendMessageWithImages(text: String, images: List<String>): Boolean =
        send(
            JSONObject()
                .put("command", "send_message")
                .put("text", text)
                .put("images", org.json.JSONArray(images))
        )

    fun jumpIn(text: String): Boolean =
        send(JSONObject().put("command", "jump_in").put("text", text))

    fun setPlanMode(enabled: Boolean): Boolean =
        send(JSONObject().put("command", if (enabled) "enter_plan_mode" else "exit_plan_mode"))

    fun cancel() {
        send(JSONObject().put("command", "cancel"))
    }

    fun approve(callId: String, decision: String) {
        send(
            JSONObject()
                .put("command", "approve_tool")
                .put("call_id", callId)
                .put("decision", decision)
        )
    }

    fun answer(callId: String, answer: String) {
        send(
            JSONObject()
                .put("command", "answer_question")
                .put("call_id", callId)
                .put("answer", answer)
        )
    }

    fun completeFileTransfer(requestId: String, paths: List<String>) {
        val array = org.json.JSONArray()
        paths.forEach(array::put)
        send(
            JSONObject()
                .put("command", "file_transfer_result")
                .put("request_id", requestId)
                .put("paths", array)
        )
    }

    fun completePhoneTool(requestId: String, outcome: PhoneToolExecutor.Outcome): Boolean {
        val payload = JSONObject()
            .put("command", "phone_tool_result")
            .put("request_id", requestId)
            .put("success", outcome.success)
        if (outcome.success) {
            payload.put("result", outcome.result ?: JSONObject())
        } else {
            payload.put("error", outcome.error ?: "手机工具执行失败")
        }
        return send(payload)
    }

    fun close() {
        open = false
        webSocket?.close(1000, "Tiyo chat closed")
        webSocket = null
    }

    private fun bootstrapCookie(info: TiyoAgentRuntimeInfo): String {
        val bootstrapClient = http.newBuilder().followRedirects(false).build()
        val request = Request.Builder()
            .url("http://127.0.0.1:${info.port}/__tiyo/auth?token=${info.authToken}")
            .build()
        bootstrapClient.newCall(request).execute().use { response ->
            if (response.code !in 200..399) {
                throw IllegalStateException("Tiyo Agent 本地认证失败")
            }
            return response.header("Set-Cookie")
                ?.substringBefore(';')
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Tiyo Agent 没有返回本地认证凭证")
        }
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            open = true
            val config = provider ?: return
            send(
                JSONObject()
                    .put("command", "set_permission_mode")
                    .put("mode", config.permissionMode)
            )
            send(
                JSONObject()
                    .put("command", "select_model")
                    .put("provider_id", TiyoAgentConfig.PROVIDER_ID)
                    .put("model", config.model)
            )
            mainHandler.post { listener.onAgentState(true, "Tiyo Agent · 就绪") }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val envelope = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (envelope.optString("type")) {
                "event" -> envelope.optJSONObject("payload")?.let { event ->
                    mainHandler.post { listener.onAgentEvent(event) }
                }
                "error" -> {
                    val message = envelope.optJSONObject("payload")
                        ?.optString("message")
                        .orEmpty()
                    mainHandler.post {
                        listener.onAgentError(message.ifBlank { "Tiyo Agent 返回了一个错误" })
                    }
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            open = false
            mainHandler.post { listener.onAgentState(false, "Tiyo Agent · 已暂停") }
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            open = false
            mainHandler.post {
                listener.onAgentError(
                    error.message?.takeIf { it.isNotBlank() } ?: "Tiyo Agent 连接中断"
                )
            }
        }
    }

    private fun send(payload: JSONObject): Boolean {
        if (!open) return false
        val envelope = JSONObject()
            .put("v", 1)
            .put("type", "command")
            .put("id", UUID.randomUUID().toString())
            .put("ts", System.currentTimeMillis() / 1000.0)
            .put("payload", payload)
        return webSocket?.send(envelope.toString()) == true
    }
}
