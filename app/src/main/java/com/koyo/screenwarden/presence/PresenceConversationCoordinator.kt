package com.koyo.screenwarden.presence

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.koyo.screenwarden.CompanionScope
import com.koyo.screenwarden.PhoneToolExecutor
import com.koyo.screenwarden.TiyoAgentClient
import com.koyo.screenwarden.TiyoAgentConfig
import com.koyo.screenwarden.TiyoAgentRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** Runs a shared-brain turn for inbound native-account messages, then uses the outbound gate. */
object PresenceConversationCoordinator {
    private const val TURN_TIMEOUT_MS = 120_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap<String, ConcurrentLinkedQueue<PresenceEvent>>()

    fun accept(context: Context, event: PresenceEvent) {
        if (event.direction != PresenceDirection.TO_COMPANION || !event.explicitUserAction) return
        val adapter = PresenceAdapterRegistry.get(event.channel) ?: return
        if (PresenceCapability.SEND_TEXT !in adapter.capabilities) return
        val conversationKey = event.conversationKey?.takeIf(String::isNotBlank) ?: return
        val turnKey = "${event.channel.name}:$conversationKey"
        pending.computeIfAbsent(turnKey) { ConcurrentLinkedQueue() }.add(event)
        if (!inFlight.add(turnKey)) return

        startNext(context.applicationContext, turnKey)
    }

    private fun startNext(app: Context, turnKey: String) {
        val event = pending[turnKey]?.poll()
        if (event == null) {
            pending.remove(turnKey)
            inFlight.remove(turnKey)
            // An event may have arrived between poll/remove. Re-acquire the turn if needed.
            if (pending[turnKey]?.isNotEmpty() == true && inFlight.add(turnKey)) startNext(app, turnKey)
            return
        }
        val companionScope = CompanionScope.capture(app)
        TiyoAgentRuntime.ensureStarted(
            app,
            companionScope,
            onReady = { info ->
                val turn = ExternalTurn(app, event, turnKey)
                turn.start(info)
            },
            onError = { completeTurn(app, turnKey) }
        )
    }

    private fun completeTurn(app: Context, turnKey: String) {
        if (pending[turnKey]?.isNotEmpty() == true) {
            startNext(app, turnKey)
        } else {
            pending.remove(turnKey)
            inFlight.remove(turnKey)
        }
    }

    private class ExternalTurn(
        private val context: Context,
        private val event: PresenceEvent,
        private val turnKey: String
    ) : TiyoAgentClient.Listener {
        private val reply = StringBuilder()
        private var messageSent = false
        private var finished = false
        private val client = TiyoAgentClient(this)
        private val timeout = Runnable { finish(null) }

        fun start(info: com.koyo.screenwarden.TiyoAgentRuntimeInfo) {
            mainHandler.postDelayed(timeout, TURN_TIMEOUT_MS)
            client.connect(info, TiyoAgentConfig.load(context), stableSessionId(event))
        }

        override fun onAgentState(connected: Boolean, label: String) {
            if (!connected || messageSent || finished) return
            messageSent = true
            val images = event.attachments.take(4).mapNotNull { attachment ->
                if (!attachment.mimeType.startsWith("image/")) return@mapNotNull null
                val file = java.io.File(attachment.privatePath)
                if (!file.isFile || file.length() !in 1..(5L * 1024L * 1024L)) return@mapNotNull null
                runCatching {
                    "data:${attachment.mimeType};base64," +
                        Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                }.getOrNull()
            }
            if (!client.sendMessage(inboundPrompt(event), images, null)) finish(null)
        }

        override fun onAgentEvent(eventJson: JSONObject) {
            when (eventJson.optString("event_type")) {
                "text_chunk" -> reply.append(eventJson.optString("content"))
                "tool_approval_request" -> client.approve(eventJson.optString("call_id"), "deny")
                "user_question_request" -> client.answer(eventJson.optString("call_id"), "请直接根据现有信息回复，不要追问")
                "phone_tool_request" -> client.completePhoneTool(
                    eventJson.optString("request_id"),
                    PhoneToolExecutor.Outcome(false, error = "外部应用会话不允许执行手机工具")
                )
                "turn_end" -> finish(reply.toString())
                "agent_error", "agent_cancelled" -> finish(null)
            }
        }

        override fun onAgentError(message: String) = finish(null)

        private fun finish(text: String?) {
            if (finished) return
            finished = true
            mainHandler.removeCallbacks(timeout)
            client.close()
            val clean = text?.trim()?.take(4_000).orEmpty()
            if (clean.isBlank()) {
                completeTurn(context, turnKey)
                return
            }
            scope.launch {
                try {
                    PresenceOutboundGate.dispatch(
                        context,
                        PresenceOutboundRequest(
                            message = AdapterOutboundMessage(
                                channel = event.channel,
                                conversationKey = event.conversationKey,
                                text = clean,
                                replyToMessageId = event.id
                            ),
                            authorization = OutboundAuthorization.DIRECT_REPLY,
                            sourceEventId = event.id
                        )
                    )
                } finally {
                    completeTurn(context, turnKey)
                }
            }
        }
    }

    private fun stableSessionId(event: PresenceEvent): String = UUID.nameUUIDFromBytes(
        "presence:${event.channel.name}:${event.conversationKey}".toByteArray(StandardCharsets.UTF_8)
    ).toString()

    private fun inboundPrompt(event: PresenceEvent): String {
        val content = event.text?.trim().orEmpty().ifBlank {
            when (event.modality) {
                PresenceModality.VIDEO -> "用户分享了一个视频，请结合已收到的上下文自然回应"
                else -> "用户发来了一条${event.modality.name.lowercase()}消息，请自然回应"
            }
        }
        return "这是来自${event.sourceLabel ?: event.channel.name}的直接消息，请只回复给用户看的内容：\n$content"
    }
}
