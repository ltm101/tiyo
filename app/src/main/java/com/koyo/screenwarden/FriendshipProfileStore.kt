package com.koyo.screenwarden

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

data class FriendshipIdentity(
    val key: String,
    val packageName: String,
    val platform: String,
    val displayName: String,
    val groupLike: Boolean = false
)

object FriendshipIdentityResolver {
    private val genericTitles = setOf("微信", "qq", "消息", "新消息", "你收到了一条消息")

    fun resolve(
        packageName: String,
        title: String,
        message: String = "",
        conversationTitle: String = ""
    ): FriendshipIdentity? {
        val preferred = conversationTitle.trim().ifBlank { title.trim() }
        val displayName = preferred
            .replace(Regex("""\s*\(\d+\s*条新消息\)\s*$"""), "")
            .replace(Regex("""\s*\[\d+条\]\s*$"""), "")
            .trim()
            .take(80)
        if (displayName.isBlank() || displayName.lowercase(Locale.ROOT) in genericTitles) return null
        val normalized = displayName.lowercase(Locale.ROOT)
            .replace(Regex("""\s+"""), " ")
            .trim()
        val key = sha256("$packageName|$normalized").take(32)
        val messageLooksGrouped = Regex("""^.{1,20}[:：]\s*.+""").matches(message.trim())
        val groupLike = conversationTitle.isNotBlank() || messageLooksGrouped
        return FriendshipIdentity(
            key = key,
            packageName = packageName,
            platform = when (packageName) {
                "com.tencent.mm" -> "微信"
                "com.tencent.mobileqq" -> "QQ"
                else -> packageName
            },
            displayName = displayName,
            groupLike = groupLike
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

data class FriendshipFact(
    val text: String,
    val confidence: Double,
    val evidenceCount: Int,
    val lastSeenAt: Long,
    val expiresAt: Long
)

data class FriendshipFingerprint(val hash: String, val observedAt: Long)

data class FriendshipProfile(
    val key: String,
    val packageName: String,
    val platform: String,
    val displayName: String,
    val relationship: String = "",
    val groupLike: Boolean = false,
    val paused: Boolean = false,
    val firstSeenAt: Long = 0L,
    val lastSeenAt: Long = 0L,
    val incomingCount: Int = 0,
    val totalChars: Long = 0L,
    val shortMessageCount: Int = 0,
    val questionCount: Int = 0,
    val expressiveCount: Int = 0,
    val topicCounts: Map<String, Int> = emptyMap(),
    val traitCounts: Map<String, Int> = emptyMap(),
    val facts: List<FriendshipFact> = emptyList(),
    val recentState: String = "",
    val recentStateExpiresAt: Long = 0L,
    val suggestedReplyCount: Int = 0,
    val chosenReplyCount: Int = 0,
    val lastAnalyzedAt: Long = 0L,
    val lastAnalyzedMessageCount: Int = 0,
    val recentFingerprints: List<FriendshipFingerprint> = emptyList()
)

data class FriendshipProfileDelta(
    val topics: List<String> = emptyList(),
    val traits: List<String> = emptyList(),
    val facts: List<Pair<String, Int>> = emptyList(),
    val recentState: String = "",
    val recentStateTtlDays: Int = 0
)

data class FriendshipObservation(
    val profile: FriendshipProfile,
    val accepted: Boolean,
    val shouldAnalyze: Boolean
)

object FriendshipProfileReducer {
    private const val DEDUPE_WINDOW_MS = 2 * 60_000L
    private const val ANALYSIS_INTERVAL_MS = 12 * 60 * 60_000L
    private const val MAX_FINGERPRINTS = 16

    private val topicRules = linkedMapOf(
        "学习" to listOf("考试", "作业", "上课", "复习", "老师", "学校", "论文", "学习"),
        "工作" to listOf("上班", "工作", "开会", "项目", "客户", "同事", "加班"),
        "游戏" to listOf("王者", "游戏", "开黑", "上分", "排位", "战绩", "对局"),
        "吃饭" to listOf("吃饭", "早餐", "午饭", "晚饭", "奶茶", "火锅", "外卖"),
        "出行" to listOf("出门", "到家", "车站", "地铁", "旅游", "机票", "酒店"),
        "日常" to listOf("睡觉", "起床", "天气", "下雨", "周末", "今天", "明天"),
        "情绪" to listOf("开心", "难过", "烦", "累", "生气", "害怕", "想你")
    )

    fun observe(
        current: FriendshipProfile?,
        identity: FriendshipIdentity,
        message: String,
        now: Long,
        claimAnalysis: Boolean
    ): FriendshipObservation {
        val base = current ?: FriendshipProfile(
            key = identity.key,
            packageName = identity.packageName,
            platform = identity.platform,
            displayName = identity.displayName,
            groupLike = identity.groupLike,
            firstSeenAt = now
        )
        if (base.paused) return FriendshipObservation(base, false, false)
        val clean = message.replace(Regex("[\r\n]+"), " ").trim().take(500)
        if (clean.isBlank()) return FriendshipObservation(base, false, false)
        val fingerprint = messageHash(identity.key, clean)
        val activeFingerprints = base.recentFingerprints.filter {
            now - it.observedAt in 0..DEDUPE_WINDOW_MS
        }
        if (activeFingerprints.any { it.hash == fingerprint }) {
            return FriendshipObservation(base.copy(recentFingerprints = activeFingerprints), false, false)
        }

        val topics = base.topicCounts.toMutableMap()
        topicRules.forEach { (topic, words) ->
            if (words.any(clean::contains)) topics[topic] = (topics[topic] ?: 0) + 1
        }
        val traits = base.traitCounts.toMutableMap()
        if (!identity.groupLike) {
            if (clean.length <= 12) traits.bump("表达简短")
            if (clean.contains('?') || clean.contains('？')) traits.bump("常用问句")
            if (looksExpressive(clean)) traits.bump("语气活跃")
            if (listOf("哈哈", "hhh", "呀", "啦", "嘛", "诶").any(clean::contains)) {
                traits.bump("偏口语")
            }
        }
        val nextCount = base.incomingCount + 1
        val analysisDue = !identity.groupLike && (
            nextCount == 1 ||
                nextCount - base.lastAnalyzedMessageCount >= 5 ||
                now - base.lastAnalyzedAt >= ANALYSIS_INTERVAL_MS
            )
        val shouldAnalyze = claimAnalysis && analysisDue
        val updated = base.copy(
            displayName = identity.displayName,
            groupLike = base.groupLike || identity.groupLike,
            lastSeenAt = now,
            incomingCount = nextCount,
            totalChars = base.totalChars + clean.length,
            shortMessageCount = base.shortMessageCount + if (clean.length <= 12) 1 else 0,
            questionCount = base.questionCount + if (clean.contains('?') || clean.contains('？')) 1 else 0,
            expressiveCount = base.expressiveCount + if (looksExpressive(clean)) 1 else 0,
            topicCounts = topics,
            traitCounts = traits,
            lastAnalyzedAt = if (shouldAnalyze) now else base.lastAnalyzedAt,
            lastAnalyzedMessageCount = if (shouldAnalyze) nextCount else base.lastAnalyzedMessageCount,
            recentFingerprints = (activeFingerprints + FriendshipFingerprint(fingerprint, now))
                .takeLast(MAX_FINGERPRINTS)
        )
        return FriendshipObservation(updated, true, shouldAnalyze)
    }

    fun applyDelta(profile: FriendshipProfile, delta: FriendshipProfileDelta, now: Long): FriendshipProfile {
        if (profile.paused || profile.groupLike) return profile
        val topics = profile.topicCounts.toMutableMap()
        delta.topics.map(::cleanLabel).filter { it.isNotBlank() && !isSensitive(it) }
            .forEach { topics.bump(it) }
        val traits = profile.traitCounts.toMutableMap()
        delta.traits.map(::cleanLabel).filter { it.isNotBlank() && !isSensitive(it) }
            .forEach { traits.bump(it) }

        val facts = profile.facts.toMutableList()
        delta.facts.forEach { (rawText, ttlDays) ->
            val text = cleanFact(rawText)
            if (text.isBlank() || isSensitive(text)) return@forEach
            val existing = facts.indexOfFirst { normalize(it.text) == normalize(text) }
            val expiry = if (ttlDays <= 0) Long.MAX_VALUE else now + ttlDays.coerceIn(1, 365) * 86_400_000L
            if (existing >= 0) {
                val old = facts[existing]
                facts[existing] = old.copy(
                    confidence = (old.confidence + 0.12).coerceAtMost(0.95),
                    evidenceCount = old.evidenceCount + 1,
                    lastSeenAt = now,
                    expiresAt = maxOf(old.expiresAt, expiry)
                )
            } else {
                facts += FriendshipFact(text, 0.58, 1, now, expiry)
            }
        }
        val cleanState = cleanFact(delta.recentState).takeIf { !isSensitive(it) }.orEmpty()
        return profile.copy(
            topicCounts = topics.entries.sortedByDescending { it.value }.take(12).associate { it.toPair() },
            traitCounts = traits.entries.sortedByDescending { it.value }.take(12).associate { it.toPair() },
            facts = facts.filter { it.expiresAt > now }.sortedByDescending { it.lastSeenAt }.take(10),
            recentState = cleanState.ifBlank { profile.recentState.takeIf { profile.recentStateExpiresAt > now }.orEmpty() },
            recentStateExpiresAt = if (cleanState.isBlank()) profile.recentStateExpiresAt else {
                now + delta.recentStateTtlDays.coerceIn(1, 45) * 86_400_000L
            },
            lastAnalyzedAt = now,
            lastAnalyzedMessageCount = profile.incomingCount
        )
    }

    private fun MutableMap<String, Int>.bump(key: String) {
        this[key] = (this[key] ?: 0) + 1
    }

    private fun looksExpressive(text: String): Boolean =
        text.contains('!') || text.contains('！') || text.contains('~') || text.contains('～') ||
            Regex("[\\p{So}\\p{Sk}]").containsMatchIn(text)

    private fun messageHash(key: String, message: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$key|$message".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(24)

    private fun cleanLabel(value: String): String = value
        .replace(Regex("[\r\n,，;；]+"), " ")
        .trim().take(24)

    private fun cleanFact(value: String): String = value
        .replace(Regex("[\r\n]+"), " ")
        .trim().take(120)

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("""[\s，。！？,.!?]+"""), "")

    private fun isSensitive(value: String): Boolean = sensitiveWords.any(value::contains)

    private val sensitiveWords = listOf(
        "政治", "政党", "党派", "宗教", "性取向", "性生活", "怀孕", "月经",
        "疾病", "诊断", "病历", "工资", "收入", "银行卡", "身份证", "密码",
        "住址", "手机号", "欠款", "负债"
    )
}

object FriendshipProfileDeltaParser {
    fun parse(raw: String): FriendshipProfileDelta? {
        val json = parseObject(raw) ?: return null
        return parse(json.optJSONObject("profile_delta") ?: json)
    }

    fun parse(json: JSONObject): FriendshipProfileDelta {
        val facts = buildList {
            val array = json.optJSONArray("facts") ?: JSONArray()
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    is JSONObject -> add(item.optString("text") to item.optInt("ttl_days", 90))
                    is String -> add(item to 90)
                }
            }
        }
        return FriendshipProfileDelta(
            topics = json.stringList("topics", 6),
            traits = json.stringList("traits", 6),
            facts = facts.take(5),
            recentState = json.optString("recent_state").trim().take(120),
            recentStateTtlDays = json.optInt("recent_state_ttl_days", 7)
        )
    }

    fun parseObject(raw: String): JSONObject? {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(clean.substring(start, end + 1)) }.getOrNull()
    }

    private fun JSONObject.stringList(key: String, max: Int): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until minOf(array.length(), max)) {
                array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
}

object FriendshipProfileStore {
    private const val PREFS = "tiyo_friendship_profiles_settings"
    private const val KEY_ENABLED = "enabled"
    private const val SECRET_PROFILES = "friendship_profiles_v1"
    private const val MAX_PROFILES = 80
    private val lock = Any()

    fun isEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun observeIncoming(
        context: Context,
        identity: FriendshipIdentity,
        message: String,
        claimAnalysis: Boolean
    ): FriendshipObservation? = synchronized(lock) {
        if (!isEnabled(context)) return@synchronized null
        val profiles = load(context).toMutableMap()
        val result = FriendshipProfileReducer.observe(
            profiles[identity.key], identity, message, System.currentTimeMillis(), claimAnalysis
        )
        if (result.accepted || result.profile != profiles[identity.key]) {
            profiles[identity.key] = result.profile
            save(context, profiles.values)
        }
        result
    }

    fun applyDelta(context: Context, key: String, delta: FriendshipProfileDelta) = synchronized(lock) {
        if (!isEnabled(context)) return@synchronized
        val profiles = load(context).toMutableMap()
        val profile = profiles[key] ?: return@synchronized
        profiles[key] = FriendshipProfileReducer.applyDelta(profile, delta, System.currentTimeMillis())
        save(context, profiles.values)
    }

    fun recordSuggested(context: Context, key: String) = update(context, key) {
        it.copy(suggestedReplyCount = it.suggestedReplyCount + 1)
    }

    fun recordChosen(context: Context, key: String) = update(context, key) {
        it.copy(chosenReplyCount = it.chosenReplyCount + 1)
    }

    fun setRelationship(context: Context, key: String, relationship: String) = update(context, key) {
        it.copy(relationship = relationship.replace(Regex("[\r\n]+"), " ").trim().take(40))
    }

    fun setPaused(context: Context, key: String, paused: Boolean) = update(context, key) {
        it.copy(paused = paused)
    }

    fun delete(context: Context, key: String) = synchronized(lock) {
        val profiles = load(context).toMutableMap()
        profiles.remove(key)
        save(context, profiles.values)
    }

    fun all(context: Context): List<FriendshipProfile> = synchronized(lock) {
        load(context).values.sortedByDescending { it.lastSeenAt }
    }

    fun summaryForPrompt(context: Context, key: String, now: Long = System.currentTimeMillis()): String {
        if (!isEnabled(context)) return ""
        val profile = synchronized(lock) { load(context)[key] } ?: return ""
        if (profile.paused) return ""
        val lines = mutableListOf<String>()
        lines += "平台与会话：${profile.platform} / ${profile.displayName}"
        profile.relationship.takeIf { it.isNotBlank() }?.let { lines += "用户确认的关系：$it" }
        if (profile.groupLike) {
            lines += "这是群聊或多人会话，不把群内多人混成一个人的性格"
        } else {
            val avg = if (profile.incomingCount > 0) profile.totalChars / profile.incomingCount else 0
            lines += "观察样本：${profile.incomingCount}条，平均长度约${avg}字"
            profile.traitCounts.entries.filter { it.value >= 2 }.sortedByDescending { it.value }.take(4)
                .takeIf { it.isNotEmpty() }?.let { traits ->
                    lines += "较稳定的表达习惯：" + traits.joinToString("、") { it.key }
                }
        }
        profile.topicCounts.entries.sortedByDescending { it.value }.take(4)
            .takeIf { it.isNotEmpty() }?.let { topics ->
                lines += "常聊话题：" + topics.joinToString("、") { it.key }
            }
        profile.recentState.takeIf { it.isNotBlank() && profile.recentStateExpiresAt > now }
            ?.let { lines += "近期明确状态：$it（会自动过期）" }
        profile.facts.filter { it.expiresAt > now }.sortedByDescending { it.confidence }.take(4)
            .takeIf { it.isNotEmpty() }?.let { facts ->
                lines += "对方明确说过的事项：" + facts.joinToString("；") { fact ->
                    "${fact.text}（置信${(fact.confidence * 100).toInt()}%）"
                }
            }
        if (profile.suggestedReplyCount > 0) {
            lines += "建议采纳：${profile.chosenReplyCount}/${profile.suggestedReplyCount}，只用于调整建议贴合度"
        }
        return lines.joinToString("\n").take(1_200)
    }

    fun detailText(profile: FriendshipProfile, now: Long = System.currentTimeMillis()): String = buildString {
        append(profile.platform).append(" · ").append(profile.displayName)
        if (profile.relationship.isNotBlank()) append("\n关系备注：").append(profile.relationship)
        append("\n已观察 ").append(profile.incomingCount).append(" 条来信")
        if (profile.paused) append("\n当前已暂停学习")
        profile.traitCounts.entries.filter { it.value >= 2 }.sortedByDescending { it.value }.take(5)
            .takeIf { it.isNotEmpty() }?.let {
                append("\n表达习惯：").append(it.joinToString("、") { item -> item.key })
            }
        profile.topicCounts.entries.sortedByDescending { it.value }.take(5)
            .takeIf { it.isNotEmpty() }?.let {
                append("\n常聊话题：").append(it.joinToString("、") { item -> item.key })
            }
        profile.recentState.takeIf { it.isNotBlank() && profile.recentStateExpiresAt > now }
            ?.let { append("\n近期状态：").append(it) }
        profile.facts.filter { it.expiresAt > now }.take(5).takeIf { it.isNotEmpty() }?.let {
            append("\n明确事项：").append(it.joinToString("；") { fact -> fact.text })
        }
        if (profile.suggestedReplyCount > 0) {
            append("\n建议采纳：").append(profile.chosenReplyCount).append('/')
                .append(profile.suggestedReplyCount)
        }
    }

    private fun update(
        context: Context,
        key: String,
        transform: (FriendshipProfile) -> FriendshipProfile
    ) = synchronized(lock) {
        val profiles = load(context).toMutableMap()
        val profile = profiles[key] ?: return@synchronized
        profiles[key] = transform(profile)
        save(context, profiles.values)
    }

    private fun load(context: Context): Map<String, FriendshipProfile> {
        val raw = TiyoSecureStore.get(context.applicationContext, SECRET_PROFILES)
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            buildMap {
                for (index in 0 until array.length()) {
                    profileFromJson(array.optJSONObject(index) ?: continue)?.let { put(it.key, it) }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun save(context: Context, profiles: Collection<FriendshipProfile>) {
        val kept = profiles.sortedByDescending { it.lastSeenAt }.take(MAX_PROFILES)
        if (kept.isEmpty()) {
            TiyoSecureStore.remove(context.applicationContext, SECRET_PROFILES)
            return
        }
        val array = JSONArray().also { out -> kept.forEach { out.put(profileToJson(it)) } }
        TiyoSecureStore.put(context.applicationContext, SECRET_PROFILES, array.toString())
    }

    private fun profileToJson(profile: FriendshipProfile) = JSONObject()
        .put("key", profile.key)
        .put("package", profile.packageName)
        .put("platform", profile.platform)
        .put("name", profile.displayName)
        .put("relationship", profile.relationship)
        .put("group", profile.groupLike)
        .put("paused", profile.paused)
        .put("first_seen", profile.firstSeenAt)
        .put("last_seen", profile.lastSeenAt)
        .put("incoming", profile.incomingCount)
        .put("chars", profile.totalChars)
        .put("short", profile.shortMessageCount)
        .put("questions", profile.questionCount)
        .put("expressive", profile.expressiveCount)
        .put("topics", mapToJson(profile.topicCounts))
        .put("traits", mapToJson(profile.traitCounts))
        .put("facts", JSONArray().also { array ->
            profile.facts.forEach { fact ->
                array.put(JSONObject()
                    .put("text", fact.text)
                    .put("confidence", fact.confidence)
                    .put("evidence", fact.evidenceCount)
                    .put("last_seen", fact.lastSeenAt)
                    .put("expires", fact.expiresAt))
            }
        })
        .put("recent_state", profile.recentState)
        .put("recent_state_expires", profile.recentStateExpiresAt)
        .put("suggested", profile.suggestedReplyCount)
        .put("chosen", profile.chosenReplyCount)
        .put("last_analyzed", profile.lastAnalyzedAt)
        .put("last_analyzed_count", profile.lastAnalyzedMessageCount)
        .put("fingerprints", JSONArray().also { array ->
            profile.recentFingerprints.forEach { item ->
                array.put(JSONObject().put("hash", item.hash).put("at", item.observedAt))
            }
        })

    private fun profileFromJson(json: JSONObject): FriendshipProfile? {
        val key = json.optString("key")
        if (key.isBlank()) return null
        val facts = buildList {
            val array = json.optJSONArray("facts") ?: JSONArray()
            for (index in 0 until array.length()) {
                val fact = array.optJSONObject(index) ?: continue
                add(FriendshipFact(
                    text = fact.optString("text").take(120),
                    confidence = fact.optDouble("confidence", 0.5).coerceIn(0.0, 1.0),
                    evidenceCount = fact.optInt("evidence", 1).coerceAtLeast(1),
                    lastSeenAt = fact.optLong("last_seen"),
                    expiresAt = fact.optLong("expires", Long.MAX_VALUE)
                ))
            }
        }
        val fingerprints = buildList {
            val array = json.optJSONArray("fingerprints") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(FriendshipFingerprint(item.optString("hash"), item.optLong("at")))
            }
        }
        return FriendshipProfile(
            key = key,
            packageName = json.optString("package"),
            platform = json.optString("platform"),
            displayName = json.optString("name"),
            relationship = json.optString("relationship"),
            groupLike = json.optBoolean("group"),
            paused = json.optBoolean("paused"),
            firstSeenAt = json.optLong("first_seen"),
            lastSeenAt = json.optLong("last_seen"),
            incomingCount = json.optInt("incoming"),
            totalChars = json.optLong("chars"),
            shortMessageCount = json.optInt("short"),
            questionCount = json.optInt("questions"),
            expressiveCount = json.optInt("expressive"),
            topicCounts = jsonToMap(json.optJSONObject("topics")),
            traitCounts = jsonToMap(json.optJSONObject("traits")),
            facts = facts,
            recentState = json.optString("recent_state"),
            recentStateExpiresAt = json.optLong("recent_state_expires"),
            suggestedReplyCount = json.optInt("suggested"),
            chosenReplyCount = json.optInt("chosen"),
            lastAnalyzedAt = json.optLong("last_analyzed"),
            lastAnalyzedMessageCount = json.optInt("last_analyzed_count"),
            recentFingerprints = fingerprints
        )
    }

    private fun mapToJson(map: Map<String, Int>) = JSONObject().also { json ->
        map.forEach { (key, value) -> json.put(key, value) }
    }

    private fun jsonToMap(json: JSONObject?): Map<String, Int> {
        if (json == null) return emptyMap()
        return buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, json.optInt(key))
            }
        }
    }
}
