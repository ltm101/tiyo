package com.koyo.screenwarden

import android.content.Context
import android.app.usage.UsageStatsManager
import com.koyo.screenwarden.events.ProactiveOpportunityPlanner
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 主动消息状态机（care-chain 手机版）。
 *
 * 由 UsageReportWorker 每小时调用 evaluateAndSend()：满足门控 + 命中触发条件时，
 * 用 Provider LLM 生成一句可又语气的关心文案，持久化进活跃会话（聊天界面气泡），
 * 并做频率控制（24h≤3 条、连续未回复退避、静默时段不打扰）。
 */
object ProactiveMessenger {

    private const val PREFS = "tiyo_proactive"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SENT_HISTORY = "sent_history"
    private const val KEY_CONSECUTIVE = "consecutive_no_reply"
    private const val KEY_LAST_USER_MSG = "last_user_msg_ts"
    private const val KEY_VERSION = "version"
    private const val KEY_ACTIVE_PER_DAY = "active_per_day"
    private const val KEY_TOPIC_HISTORY = "topic_history"
    private const val KEY_PENDING_TOPIC = "pending_topic"
    private const val KEY_PENDING_SLOT = "pending_slot"
    private const val KEY_PENDING_SENT_AT = "pending_sent_at"
    private const val KEY_SLOT_FEEDBACK = "slot_feedback"
    private const val KEY_TOPIC_FEEDBACK = "topic_feedback"

    const val MAX_PER_24H = 3
    const val MAX_CONSECUTIVE_NO_REPLY = 2
    private const val MIN_GAP_SINCE_USER_MS = 60 * 60 * 1000L
    private const val BASE_PROACTIVE_GAP_MS = 2 * 60 * 60 * 1000L
    private const val QUIET_START_MINUTES = 90    // 01:30
    private const val QUIET_END_MINUTES = 510     // 08:30
    private const val LONG_ABSENCE_HOURS = 26.0
    private const val MAX_HISTORY_ENTRIES = 200

    data class Trigger(
        val id: String,
        val contextLine: String,
        val fallbackText: String,
        val intent: String = "companionship"
    )
    data class GateSnapshot(val allowed: Boolean, val blockReason: String = "")

    // ---- 对外接口 ----

    /** Worker 调用：检查并可能发送一条主动消息。true = 已发送 */
    fun evaluateAndSend(context: Context): Boolean {
        if (!gateSnapshot(context).allowed) return false
        val trigger = pickTrigger(context) ?: return false
        return deliver(context, trigger.contextLine, trigger.fallbackText, trigger.id, trigger.intent)
    }

    /** DecisionEngine 与旧规则共用同一套投递链和二次门控。 */
    fun deliver(
        context: Context,
        contextLine: String,
        fallbackText: String,
        topicKey: String = "general",
        intent: String = "companionship"
    ): Boolean {
        if (!gateSnapshot(context).allowed) return false
        val scope = CompanionScope.capture(context)
        val now = System.currentTimeMillis()
        val topicSentAt = topicLastSentAt(context, topicKey)
        if (topicSentAt > 0L && now - topicSentAt < ProactiveOpportunityPlanner.topicCooldownMs(topicKey)) {
            return false
        }
        val cal = Calendar.getInstance()
        val text = ProactiveTextGenerator.generate(
            context,
            scope,
            "现在是 ${cal.get(Calendar.HOUR_OF_DAY)}:${cal.get(Calendar.MINUTE)}，$contextLine。请直接输出你想说的话。",
            fallbackText
        )
        if (text.isBlank()) return false

        // 提取表情包标记 {sticker:名字}
        val (clean, sticker) = StickerStore.extractSticker(text)
        val activeId = TiyoSessionStore.activeId(context, scope)
        if (!TiyoSessionStore.appendAssistantMessage(context, scope, activeId, clean, sticker)) return false

        recordSend(context, scope, topicKey, intent)
        if (ProactivePresence.shouldNotify()) {
            ActionExecutor.notify(context, clean, openChat = true)
        }
        return true
    }

    /** 24h 频率、连续未回复、静默时段和用户刚说过话四道门，一条不放松。 */
    fun gateSnapshot(context: Context, now: Long = System.currentTimeMillis()): GateSnapshot {
        if (!isEnabled(context)) return GateSnapshot(false, "disabled")
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (minutes >= QUIET_START_MINUTES && minutes < QUIET_END_MINUTES) {
            return GateSnapshot(false, "quiet_hours")
        }
        if (sentCountLast24h(context) >= activePerDay(context)) {
            return GateSnapshot(false, "daily_frequency")
        }
        if (consecutive(context) >= MAX_CONSECUTIVE_NO_REPLY) {
            return GateSnapshot(false, "no_reply_backoff")
        }
        val lastSent = lastSentAt(context)
        val noReplyLevel = consecutive(context).coerceIn(0, MAX_CONSECUTIVE_NO_REPLY)
        val proactiveGap = BASE_PROACTIVE_GAP_MS * (1L shl noReplyLevel)
        if (lastSent > 0L && now - lastSent < proactiveGap) {
            return GateSnapshot(false, "adaptive_gap")
        }
        val lastUser = lastUserMsgTs(context)
        if (lastUser > 0 && now - lastUser < MIN_GAP_SINCE_USER_MS) {
            return GateSnapshot(false, "recent_user_message")
        }
        return GateSnapshot(true)
    }

    /** ChatFragment 用户发消息时调用：重置连续未回复计数 + 记录用户消息时间 */
    fun onUserMessage(context: Context) {
        recordPositiveFeedback(context, System.currentTimeMillis(), 3 * 60 * 60_000L, 2)
        prefs(context).edit()
            .putLong(KEY_LAST_USER_MSG, System.currentTimeMillis())
            .putInt(KEY_CONSECUTIVE, 0)
            .apply()
    }

    /** ChatFragment 可见时调用：打开即视为已读，重置连续未回复计数 */
    fun onChatOpened(context: Context) {
        recordPositiveFeedback(context, System.currentTimeMillis(), 6 * 60 * 60_000L, 1)
        prefs(context).edit().putInt(KEY_CONSECUTIVE, 0).apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    /** 主动消息每天上限（用户可在设置里自定义，默认 MAX_PER_24H） */
    fun activePerDay(context: Context): Int =
        prefs(context).getInt(KEY_ACTIVE_PER_DAY, MAX_PER_24H)

    fun setActivePerDay(context: Context, n: Int) {
        prefs(context).edit().putInt(KEY_ACTIVE_PER_DAY, n.coerceIn(1, 30)).apply()
    }

    /** 每次落气泡 +1，ChatFragment 据此重载历史 */
    fun version(context: Context): Int =
        prefs(context).getInt(KEY_VERSION, 0)

    fun sentCountLast24h(context: Context): Int {
        val now = System.currentTimeMillis()
        val windowStart = now - 24 * 60 * 60 * 1000L
        return sentHistory(context).count { it > windowStart }
    }

    fun lastSentAt(context: Context): Long = sentHistory(context).lastOrNull() ?: 0L

    fun lastUserMessageAt(context: Context): Long = lastUserMsgTs(context)

    fun consecutiveNoReply(context: Context): Int = consecutive(context)

    fun topicLastSentAt(context: Context, topicKey: String): Long =
        longMap(context, KEY_TOPIC_HISTORY)[topicKey] ?: 0L

    /** 用户在某类时段和话题上更常回应时，小幅提高机会分；不突破硬门控。 */
    fun opportunityFeedbackAdjustment(
        context: Context,
        topicKey: String,
        now: Long = System.currentTimeMillis()
    ): Int {
        expirePendingFeedback(context, now)
        val slotScore = intMap(context, KEY_SLOT_FEEDBACK)[timeSlot(now)] ?: 0
        val topicScore = intMap(context, KEY_TOPIC_FEEDBACK)[topicKey] ?: 0
        return (slotScore * 3 + topicScore * 3).coerceIn(-12, 10)
    }

    // ---- 触发条件 ----

    private fun pickTrigger(context: Context): Trigger? {
        val ctx = context
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)

        // 数据采集（静默降级）
        val screenMinutes = queryScreenMinutes(ctx)
        val steps = StepCounterCollector.getTodaySteps(ctx)
        val lastUser = lastUserMsgTs(ctx)
        val lastUserAgeHours = if (lastUser > 0) (now - lastUser) / 3_600_000.0 else 0.0

        // 1) 久未联系（≥26h 没说话）
        if (lastUserAgeHours >= LONG_ABSENCE_HOURS) {
            return Trigger(
                "companionship_window",
                "有一阵没联系了，只表达当下想念，不计算消失时间，也不要求回复",
                "刚刚想起你了，来看看你在干嘛"
            )
        }

        // 2) 深夜陪伴（22-01 点）
        if (hour in 22..23 || hour in 0..1) {
            if (screenMinutes >= 420) {
                return Trigger(
                    "screen_wellbeing:late_night",
                    "夜深且今天屏幕使用较久，温柔让他短暂休息，不报数据",
                    "眼睛先借我两分钟，歇一下再继续",
                    "wellbeing"
                )
            }
        }

        // 3) 屏幕过久（≥7h）
        if (screenMinutes >= 420) {
            return Trigger(
                "screen_wellbeing:daily",
                "今天看屏幕较久了，关心眼睛，不报数据",
                "先把屏幕放下两分钟，我陪你缓缓眼睛",
                "wellbeing"
            )
        }

        // 4) 步数过少（15-20 点、<3000 步）
        if (steps in 0..2999 && hour in 15..20) {
            return Trigger(
                "movement_today",
                "今天还没怎么活动，轻轻提议走动，不报步数",
                "坐久了就陪我走两步，回来再继续",
                "wellbeing"
            )
        }

        // 5) 兜底自然联系：至少相隔 8 小时，只在白天和晚上合适窗口出现
        if (lastUserAgeHours >= 8.0 && lastUserAgeHours < LONG_ABSENCE_HOURS && hour in 10..22) {
            return Trigger(
                "companionship_window",
                "有一阵没说话了，像熟悉的人一样自然靠近；不复述旧话题，不总结进度，也不要求回复",
                casualFallback(hour)
            )
        }

        return null
    }

    private fun queryScreenMinutes(context: Context): Int {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, cal.timeInMillis, System.currentTimeMillis()
            ).filter { it.totalTimeInForeground > 0 }
            (stats.take(15).sumOf { it.totalTimeInForeground } / 60_000L).toInt()
        } catch (_: Exception) {
            -1
        }
    }

    private fun recordSend(
        context: Context,
        scope: CompanionScope,
        topicKey: String,
        intent: String
    ) {
        val now = System.currentTimeMillis()
        val windowStart = now - 24 * 60 * 60 * 1000L
        val preferences = prefs(context, scope)
        val history = (sentHistory(preferences) + now).filter { it > windowStart }.takeLast(MAX_HISTORY_ENTRIES)
        val topics = longMap(preferences, KEY_TOPIC_HISTORY).toMutableMap()
        topics[topicKey] = now
        topics.entries.removeAll { now - it.value > 7 * 24 * 60 * 60_000L }
        preferences.edit()
            .putString(KEY_SENT_HISTORY, JSONArray(history).toString())
            .putString(KEY_TOPIC_HISTORY, JSONObject(topics).toString())
            .putString(KEY_PENDING_TOPIC, topicKey)
            .putString(KEY_PENDING_SLOT, timeSlot(now))
            .putLong(KEY_PENDING_SENT_AT, now)
            .putString("pending_intent", intent)
            .putInt(KEY_CONSECUTIVE, preferences.getInt(KEY_CONSECUTIVE, 0) + 1)
            .putInt(KEY_VERSION, preferences.getInt(KEY_VERSION, 0) + 1)
            .apply()
    }

    private fun consecutive(context: Context): Int =
        prefs(context).getInt(KEY_CONSECUTIVE, 0)

    private fun lastUserMsgTs(context: Context): Long =
        prefs(context).getLong(KEY_LAST_USER_MSG, 0L)

    private fun sentHistory(context: Context): List<Long> {
        return sentHistory(prefs(context))
    }

    private fun sentHistory(preferences: android.content.SharedPreferences): List<Long> {
        val raw = preferences.getString(KEY_SENT_HISTORY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optLong(it).takeIf { v -> v > 0 } }
        }.getOrDefault(emptyList())
    }

    private fun recordPositiveFeedback(
        context: Context,
        now: Long,
        responseWindowMs: Long,
        amount: Int
    ) {
        val preferences = prefs(context)
        val sentAt = preferences.getLong(KEY_PENDING_SENT_AT, 0L)
        if (sentAt <= 0L || now - sentAt !in 0..responseWindowMs) return
        val topic = preferences.getString(KEY_PENDING_TOPIC, "").orEmpty()
        val slot = preferences.getString(KEY_PENDING_SLOT, "").orEmpty()
        val topics = intMap(context, KEY_TOPIC_FEEDBACK).toMutableMap()
        val slots = intMap(context, KEY_SLOT_FEEDBACK).toMutableMap()
        if (topic.isNotBlank()) topics[topic] = ((topics[topic] ?: 0) + amount).coerceIn(-2, 2)
        if (slot.isNotBlank()) slots[slot] = ((slots[slot] ?: 0) + amount).coerceIn(-3, 3)
        preferences.edit()
            .putString(KEY_TOPIC_FEEDBACK, JSONObject(topics).toString())
            .putString(KEY_SLOT_FEEDBACK, JSONObject(slots).toString())
            .remove(KEY_PENDING_TOPIC)
            .remove(KEY_PENDING_SLOT)
            .remove(KEY_PENDING_SENT_AT)
            .remove("pending_intent")
            .apply()
    }

    private fun expirePendingFeedback(context: Context, now: Long) {
        val preferences = prefs(context)
        val sentAt = preferences.getLong(KEY_PENDING_SENT_AT, 0L)
        if (sentAt <= 0L || now - sentAt < 6 * 60 * 60_000L) return
        val topic = preferences.getString(KEY_PENDING_TOPIC, "").orEmpty()
        val slot = preferences.getString(KEY_PENDING_SLOT, "").orEmpty()
        val topics = intMap(context, KEY_TOPIC_FEEDBACK).toMutableMap()
        val slots = intMap(context, KEY_SLOT_FEEDBACK).toMutableMap()
        if (topic.isNotBlank()) topics[topic] = ((topics[topic] ?: 0) - 1).coerceIn(-2, 2)
        if (slot.isNotBlank()) slots[slot] = ((slots[slot] ?: 0) - 1).coerceIn(-3, 3)
        preferences.edit()
            .putString(KEY_TOPIC_FEEDBACK, JSONObject(topics).toString())
            .putString(KEY_SLOT_FEEDBACK, JSONObject(slots).toString())
            .remove(KEY_PENDING_TOPIC)
            .remove(KEY_PENDING_SLOT)
            .remove(KEY_PENDING_SENT_AT)
            .remove("pending_intent")
            .apply()
    }

    private fun longMap(context: Context, key: String): Map<String, Long> {
        return longMap(prefs(context), key)
    }

    private fun longMap(
        preferences: android.content.SharedPreferences,
        key: String
    ): Map<String, Long> {
        val raw = preferences.getString(key, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.optLong(it) }.filterValues { it > 0L }
        }.getOrDefault(emptyMap())
    }

    private fun intMap(context: Context, key: String): Map<String, Int> {
        val raw = prefs(context).getString(key, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.optInt(it) }
        }.getOrDefault(emptyMap())
    }

    private fun timeSlot(now: Long): String {
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 8..10 -> "morning"
            in 11..13 -> "midday"
            in 14..17 -> "afternoon"
            in 18..21 -> "evening"
            else -> "late"
        }
    }

    private fun casualFallback(hour: Int): String = when (hour) {
        in 8..10 -> "早，我刚好想来找你说句话"
        in 11..13 -> "我来陪你歇一会儿，忙完再回来也行"
        in 14..17 -> "我来找你待一会儿，不说正事也行"
        in 18..21 -> "晚上了，我来陪你一会儿"
        else -> "我还在这儿，来陪你待一会儿"
    }

    private fun prefs(context: Context) =
        prefs(context, CompanionScope.capture(context))

    private fun prefs(context: Context, scope: CompanionScope) =
        context.getSharedPreferences(scope.namespaced(PREFS), Context.MODE_PRIVATE)
}
