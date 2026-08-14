package com.koyo.screenwarden.events

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.koyo.screenwarden.ProactiveMessenger
import com.koyo.screenwarden.StepCounterCollector
import com.koyo.screenwarden.TiyoAgentConfig
import com.koyo.screenwarden.TiyoSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class DecisionAction { SEND, SILENT, DELAY }

data class DecisionResult(
    val action: DecisionAction,
    val delayMinutes: Int = 0,
    val messageContext: String = ""
)

object DecisionResponseParser {
    fun parse(raw: String): DecisionResult? {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { JSONObject(clean.substring(start, end + 1)) }.getOrNull() ?: return null
        val action = when (json.optString("action").lowercase(Locale.ROOT)) {
            "send" -> DecisionAction.SEND
            "silent" -> DecisionAction.SILENT
            "delay" -> DecisionAction.DELAY
            else -> return null
        }
        return DecisionResult(
            action = action,
            delayMinutes = json.optInt("delay_minutes", 0).coerceIn(5, 360),
            messageContext = json.optString("message_context").trim().take(300)
        )
    }
}

/** 行为片段 -> 本地机会层 -> 反射模型 -> 复用现有投递链。 */
object DecisionEngine {
    private const val TAG = "TiyoDecision"
    private const val PREFS = "tiyo_decision_engine"
    private const val MAX_CALLS_PER_DAY = 48
    private val mutex = Mutex()

    suspend fun evaluate(context: Context, events: List<TiyoEvent>) = mutex.withLock {
        if (events.isEmpty()) return@withLock
        val ctx = context.applicationContext
        val now = System.currentTimeMillis()
        val gate = ProactiveMessenger.gateSnapshot(ctx, now)
        if (!gate.allowed) {
            Log.i(TAG, "decision skipped by gate=${gate.blockReason}")
            return@withLock
        }

        val conversation = conversationSnapshot(ctx, now)
        val episode = BehaviorEpisodeStore.snapshot(ctx)
        val environment = OpportunityEnvironment(
            now = now,
            hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY),
            lastUserMessageAt = ProactiveMessenger.lastUserMessageAt(ctx),
            focusProtected = conversation.focusProtected,
            consecutiveNoReply = ProactiveMessenger.consecutiveNoReply(ctx),
            recentScreenMinutes = episode.recentScreenMinutes,
            recentScreenEndedAt = episode.recentScreenEndedAt
        )
        val opportunity = chooseOpportunity(ctx, events, environment) ?: run {
            Log.i(TAG, "decision skipped by local opportunity layer events=${eventTypes(events)}")
            return@withLock
        }

        val decision = requestDecision(ctx, events, opportunity, conversation)
        if (decision == null) {
            val delivered = if (opportunity.score >= 70) {
                deliverOpportunity(ctx, opportunity, opportunity.contextLine)
            } else {
                ProactiveMessenger.evaluateAndSend(ctx)
            }
            Log.i(TAG, "reflection unavailable; local/legacy fallback delivered=$delivered")
            return@withLock
        }

        when (decision.action) {
            DecisionAction.SILENT -> Log.i(TAG, "decision=silent topic=${opportunity.topicKey}")
            DecisionAction.SEND -> {
                val lead = decision.messageContext.ifBlank { opportunity.contextLine }
                val sent = deliverOpportunity(ctx, opportunity, lead)
                Log.i(TAG, "decision=send delivered=$sent topic=${opportunity.topicKey}")
            }
            DecisionAction.DELAY -> {
                // 陪伴画面摘要不落盘，延后会失去可靠上下文，因此直接静默
                if (opportunity.topicKey.startsWith("companion:")) {
                    Log.i(TAG, "companion opportunity not persisted for delayed replay")
                } else {
                    delayOpportunity(ctx, events, opportunity, decision, now)
                }
            }
        }
    }

    private fun chooseOpportunity(
        context: Context,
        events: List<TiyoEvent>,
        environment: OpportunityEnvironment
    ): ProactiveOpportunity? = ProactiveOpportunityPlanner.candidates(events, environment)
        .map { candidate ->
            ProactiveOpportunityPlanner.assess(
                candidate = candidate,
                now = environment.now,
                topicLastSentAt = ProactiveMessenger.topicLastSentAt(context, candidate.topicKey),
                feedbackAdjustment = ProactiveMessenger.opportunityFeedbackAdjustment(
                    context,
                    candidate.topicKey,
                    environment.now
                )
            )
        }
        .filter { it.opportunity != null }
        .maxByOrNull { it.score }
        ?.opportunity

    private fun deliverOpportunity(
        context: Context,
        opportunity: ProactiveOpportunity,
        contextLine: String
    ): Boolean = ProactiveMessenger.deliver(
        context = context,
        contextLine = contextLine,
        fallbackText = opportunity.fallbackText,
        topicKey = opportunity.topicKey,
        intent = opportunity.intent.name.lowercase(Locale.ROOT)
    )

    private fun delayOpportunity(
        context: Context,
        events: List<TiyoEvent>,
        opportunity: ProactiveOpportunity,
        decision: DecisionResult,
        now: Long
    ) {
        val attempt = events.maxOfOrNull { it.attempt } ?: 0
        if (attempt >= 2) {
            Log.i(TAG, "decision delay limit reached")
            return
        }
        val delay = decision.delayMinutes.coerceIn(5, 360)
        val nextCheckAt = now + delay * 60_000L
        if (nextCheckAt >= opportunity.expiresAt) {
            Log.i(TAG, "decision delay would outlive opportunity")
            return
        }
        EventBus.publish(
            context,
            TiyoEvent(
                type = TiyoEventType.DEFERRED,
                summary = opportunity.contextLine.take(200),
                occurredAt = now,
                notBefore = nextCheckAt,
                attempt = attempt + 1,
                topicKey = opportunity.topicKey,
                expiresAt = opportunity.expiresAt
            )
        )
        Log.i(TAG, "decision=delay minutes=$delay topic=${opportunity.topicKey}")
    }

    private suspend fun requestDecision(
        context: Context,
        events: List<TiyoEvent>,
        opportunity: ProactiveOpportunity,
        conversation: ConversationSnapshot
    ): DecisionResult? {
        if (!TiyoAgentConfig.isConfigured(context)) return null
        if (!acquireDailyBudget(context)) return null
        val config = TiyoAgentConfig.load(context)
        val apiKey = TiyoAgentConfig.providerKey(context)
        if (apiKey.isBlank()) return null

        val system = """
            你是 tiyo 的日常反射层。只有本地机会层确认“可能值得开口”后你才会被调用
            你只负责最后判断现在是否联系用户，不负责直接聊天
            必须只输出一个 JSON 对象：
            {"action":"send|silent|delay","delay_minutes":0,"message_context":"供文案生成器参考的一句场景说明"}
            原则：默认静默；只有机会理由可靠、当下不打扰、内容有新意时才发送
            不把主动联系写成会话回访，禁止“上次说到”“要不要继续”一类措辞
            不输出“检测到”“根据数据”“你已经多久没回复”等监控或施压措辞，也不必总以问题结尾
            单独充电、单条通知和普通亮屏都不构成说话理由
            delay 只用于稍后再判断，范围 5 到 360 分钟；拿不准就 silent
            通知正文仅供本次判断，禁止复述、保存、总结或要求写入记忆
        """.trimIndent()

        val user = JSONObject()
            .put("now", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
            .put("events", JSONArray().also { array ->
                events.forEach { event ->
                    array.put(
                        JSONObject()
                            .put("type", event.type.name)
                            .put("summary", event.summary)
                            .put("private_context", event.sensitiveContext ?: "")
                    )
                }
            })
            .put("opportunity", JSONObject()
                .put("topic_key", opportunity.topicKey)
                .put("intent", opportunity.intent.name.lowercase(Locale.ROOT))
                .put("why_now", opportunity.whyNow)
                .put("local_score", opportunity.score)
                .put("confidence", opportunity.confidence)
                .put("expires_at", opportunity.expiresAt))
            .put("conversation_state", conversation.json)
            .put("today_sent_count", ProactiveMessenger.sentCountLast24h(context))
            .put("daily_send_limit", ProactiveMessenger.activePerDay(context))
            .put("routine_profile", routineProfile(context))
            .put("local_state", localState(context))

        val payload = JSONObject()
            .put("model", config.model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user.toString())))
            .put("temperature", 0.1)
            .put("max_tokens", 180)

        return withContext(Dispatchers.IO) {
            try {
                val connection = URL("${config.baseUrl.trimEnd('/')}/chat/completions")
                    .openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 20_000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setRequestProperty("Authorization", "Bearer $apiKey")
                    val body = payload.toString().toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                    if (connection.responseCode !in 200..299) return@withContext null
                    val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val content = JSONObject(response).optJSONArray("choices")
                        ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                    DecisionResponseParser.parse(content)
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun acquireDailyBudget(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        synchronized(this) {
            val count = if (prefs.getString("budget_day", "") == today) {
                prefs.getInt("budget_count", 0)
            } else 0
            if (count >= MAX_CALLS_PER_DAY) return false
            prefs.edit().putString("budget_day", today).putInt("budget_count", count + 1).apply()
            return true
        }
    }

    private data class ConversationSnapshot(
        val focusProtected: Boolean,
        val json: JSONObject
    )

    /** 只提供是否适合打扰的状态，不把旧对话原文当成主动开场素材。 */
    private fun conversationSnapshot(context: Context, now: Long): ConversationSnapshot {
        val raw = TiyoSessionStore.history(context, TiyoSessionStore.activeId(context))
        var latestUserText = ""
        var latestUserAt = ProactiveMessenger.lastUserMessageAt(context)
        if (!raw.isNullOrBlank()) runCatching {
            val array = JSONArray(raw)
            for (index in array.length() - 1 downTo 0) {
                val item = array.optJSONObject(index)
                if (item != null && item.optString("role") == "user") {
                    latestUserText = item.optString("text").replace(Regex("[\r\n]+"), " ").take(200)
                    latestUserAt = maxOf(latestUserAt, item.optLong("timestamp"))
                    break
                }
            }
        }
        val focusWords = listOf(
            "别打扰", "先别找我", "我要开会", "在开会", "要考试",
            "在考试", "专注一会", "我先忙", "去睡了", "睡觉了"
        )
        val focusProtected = latestUserAt > 0L &&
            now - latestUserAt in 0..4 * 60 * 60_000L &&
            focusWords.any { latestUserText.contains(it) }
        val ageMinutes = if (latestUserAt > 0L && now >= latestUserAt) {
            (now - latestUserAt) / 60_000L
        } else -1L
        return ConversationSnapshot(
            focusProtected = focusProtected,
            json = JSONObject()
                .put("minutes_since_user_message", ageMinutes)
                .put("focus_protected", focusProtected)
                .put("consecutive_proactive_without_reply", ProactiveMessenger.consecutiveNoReply(context))
                .put("old_conversation_may_be_used_as_opener", false)
        )
    }

    private fun routineProfile(context: Context): String {
        val raw = TiyoSessionStore.history(context, TiyoSessionStore.activeId(context)) ?: return "作息样本不足"
        val hours = runCatching {
            val array = JSONArray(raw)
            buildList {
                val start = (array.length() - 30).coerceAtLeast(0)
                for (index in start until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    if (item.optString("role") != "user") continue
                    val ts = item.optLong("timestamp")
                    if (ts > 0) add(Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.HOUR_OF_DAY))
                }
            }
        }.getOrDefault(emptyList())
        if (hours.size < 3) return "作息样本不足；固定静默时段 01:30-08:30"
        val period = hours.groupingBy {
            when (it) {
                in 6..11 -> "上午"
                in 12..17 -> "下午"
                in 18..23 -> "晚上"
                else -> "深夜"
            }
        }.eachCount().maxByOrNull { it.value }?.key ?: "未知"
        return "近期对话较常出现在$period；固定静默时段 01:30-08:30"
    }

    private fun localState(context: Context): JSONObject {
        val episode = BehaviorEpisodeStore.snapshot(context)
        return JSONObject()
            .put("screen_minutes_today", queryScreenMinutes(context))
            .put("steps_today", StepCounterCollector.getTodaySteps(context))
            .put("battery_percent", batteryPercent(context))
            .put("recent_continuous_screen_minutes", episode.recentScreenMinutes)
    }

    private fun queryScreenMinutes(context: Context): Int = runCatching {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        (manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, System.currentTimeMillis())
            .sumOf { it.totalTimeInForeground } / 60_000L).toInt()
    }.getOrDefault(-1)

    private fun batteryPercent(context: Context): Int = runCatching {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return@runCatching -1
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) level * 100 / scale else -1
    }.getOrDefault(-1)

    private fun eventTypes(events: List<TiyoEvent>): String =
        events.joinToString(",") { it.type.name }.take(160)
}
