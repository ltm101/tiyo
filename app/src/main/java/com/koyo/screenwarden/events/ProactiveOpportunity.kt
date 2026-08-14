package com.koyo.screenwarden.events

enum class ProactiveIntent {
    COMPANIONSHIP,
    WELLBEING,
    CELEBRATION,
    SAFETY,
    OBSERVATION
}

data class OpportunityEnvironment(
    val now: Long,
    val hour: Int,
    val lastUserMessageAt: Long,
    val focusProtected: Boolean,
    val consecutiveNoReply: Int,
    val recentScreenMinutes: Int,
    val recentScreenEndedAt: Long
)

data class ProactiveOpportunity(
    val topicKey: String,
    val intent: ProactiveIntent,
    val whyNow: String,
    val contextLine: String,
    val fallbackText: String,
    val score: Int,
    val confidence: Double,
    val expiresAt: Long,
    val sourceEvents: List<TiyoEvent>
)

data class OpportunityAssessment(
    val opportunity: ProactiveOpportunity?,
    val score: Int,
    val blockReason: String = ""
)

/**
 * 本地机会层。先证明“为什么是现在”，再允许反射模型参与
 */
object ProactiveOpportunityPlanner {
    const val MIN_REFLECTION_SCORE = 60
    private const val HOUR_MS = 60 * 60_000L

    fun candidates(
        events: List<TiyoEvent>,
        environment: OpportunityEnvironment
    ): List<ProactiveOpportunity> {
        if (environment.focusProtected) return emptyList()
        val notificationLoad = events.any { it.type == TiyoEventType.NOTIFICATION_BURST }
        return events.mapNotNull { event ->
            candidateFor(event, environment)
        }.map { candidate ->
            val noReplyPenalty = if (environment.consecutiveNoReply > 0 &&
                candidate.intent != ProactiveIntent.SAFETY
            ) 12 * environment.consecutiveNoReply else 0
            val notificationPenalty = if (notificationLoad &&
                candidate.intent != ProactiveIntent.SAFETY &&
                candidate.topicKey != "notification_load"
            ) 8 else 0
            candidate.copy(score = (candidate.score - noReplyPenalty - notificationPenalty).coerceAtLeast(0))
        }
    }

    fun assess(
        candidate: ProactiveOpportunity,
        now: Long,
        topicLastSentAt: Long,
        feedbackAdjustment: Int = 0
    ): OpportunityAssessment {
        if (candidate.expiresAt < now) {
            return OpportunityAssessment(null, 0, "expired")
        }
        if (topicLastSentAt > 0L && now - topicLastSentAt < topicCooldownMs(candidate.topicKey)) {
            return OpportunityAssessment(null, 0, "topic_cooldown")
        }
        val adjusted = (candidate.score + feedbackAdjustment.coerceIn(-12, 10)).coerceIn(0, 100)
        if (adjusted < MIN_REFLECTION_SCORE) {
            return OpportunityAssessment(null, adjusted, "low_value")
        }
        return OpportunityAssessment(candidate.copy(score = adjusted), adjusted)
    }

    fun topicCooldownMs(topicKey: String): Long = when {
        topicKey.startsWith("screen_wellbeing:") -> 24 * HOUR_MS
        topicKey == "movement_today" -> 18 * HOUR_MS
        topicKey == "companionship_window" -> 18 * HOUR_MS
        topicKey == "rest_after_charge" -> 12 * HOUR_MS
        topicKey == "notification_load" -> 6 * HOUR_MS
        else -> 12 * HOUR_MS
    }

    private fun candidateFor(
        event: TiyoEvent,
        environment: OpportunityEnvironment
    ): ProactiveOpportunity? = when (event.type) {
        TiyoEventType.SCREEN_SESSION -> screenCandidate(event, environment.hour)
        TiyoEventType.APP_LIMIT_APPROACHING -> opportunity(
            event = event,
            intent = ProactiveIntent.WELLBEING,
            whyNow = "一段高强度屏幕使用已经形成，提醒仍然来得及",
            contextLine = "用户刚经历较长的屏幕使用，像熟悉的人一样让他短暂休息，不报数据，不说检测到",
            fallbackText = "眼睛先借我休息两分钟，别装没事",
            score = 82,
            confidence = 0.92
        )

        TiyoEventType.STEP_MILESTONE -> movementCandidate(event)

        TiyoEventType.POWER_CONNECTED -> chargingCandidate(event, environment)

        TiyoEventType.NOTIFICATION_BURST -> opportunity(
            event = event,
            intent = ProactiveIntent.OBSERVATION,
            whyNow = "短时间通知变多，但这更可能意味着用户正在忙",
            contextLine = "用户这边像是忽然忙起来了；除非通知内容明确值得关心，否则保持安静",
            fallbackText = "你先忙，我在这儿",
            score = 45,
            confidence = 0.55
        )

        TiyoEventType.TIME_ANCHOR -> companionshipCandidate(event, environment)

        TiyoEventType.COMPANION_CONTEXT -> companionContextCandidate(event)

        TiyoEventType.DEFERRED -> opportunity(
            event = event,
            intent = intentFromTopic(event.topicKey),
            whyNow = "先前的开口机会被延后，现在重新判断是否仍然合适",
            contextLine = event.summary,
            fallbackText = fallbackForTopic(event.topicKey),
            score = 66,
            confidence = 0.72
        )

        TiyoEventType.SCREEN_ON,
        TiyoEventType.SCREEN_OFF,
        TiyoEventType.POWER_DISCONNECTED,
        TiyoEventType.NOTIFICATION -> null
    }

    private fun screenCandidate(event: TiyoEvent, hour: Int): ProactiveOpportunity {
        val minutes = Regex("""(\d+)分钟""").find(event.summary)?.groupValues?.get(1)?.toIntOrNull() ?: 30
        val base = when {
            minutes >= 60 -> 84
            minutes >= 40 -> 75
            else -> 66
        }
        val timing = if (hour in 22..23 || hour in 0..1) 8 else 0
        return opportunity(
            event = event,
            intent = ProactiveIntent.WELLBEING,
            whyNow = if (event.summary.contains("仍在使用")) {
                "连续使用还没有结束，现在是短暂打断的有效窗口"
            } else {
                "连续使用刚结束，关心不会打断正在做的事"
            },
            contextLine = "用户刚经历一段连续屏幕使用，关心眼睛或休息；不报分钟数，不使用监测口吻",
            fallbackText = if (event.summary.contains("仍在使用")) {
                "眼睛先借我两分钟，歇一下再继续"
            } else {
                "刚放下屏幕就先别急着拿回来，陪我歇两分钟"
            },
            score = base + timing,
            confidence = 0.9
        )
    }

    private fun companionContextCandidate(event: TiyoEvent): ProactiveOpportunity? {
        // 画面摘要只驻内存；若进程重启导致私密上下文丢失，就宁可不说
        if (event.sensitiveContext.isNullOrBlank()) return null
        val gameResult = event.topicKey == "companion:wangzhe:result"
        val invitedResponse = event.topicKey.endsWith(":respond")
        val score = when {
            gameResult -> 78
            invitedResponse -> 64
            else -> 48
        }
        return opportunity(
            event = event,
            intent = ProactiveIntent.OBSERVATION,
            whyNow = when {
                gameResult -> "陪玩会话刚出现对局结算页，已经离开最需要专注的操作阶段"
                invitedResponse -> "陪伴会话里出现一个具体而自然的互动点"
                else -> "画面有可理解的信息，但没有充分理由打断用户"
            },
            contextLine = when {
                gameResult -> "用户刚打完一局王者；结合本轮私密画面摘要自然回应胜负或表现，不报监控数据，不假装看到了画面之外的信息"
                invitedResponse -> "用户正在已授权的陪伴会话中；只有私密画面摘要确实值得接话时才说一句，否则保持安静"
                else -> "这只是陪伴背景，默认保持安静"
            },
            fallbackText = if (gameResult) "这局打完啦，先松一下手指" else "刚好想陪你说一句",
            score = score,
            confidence = if (gameResult) 0.88 else 0.68
        )
    }

    private fun chargingCandidate(
        event: TiyoEvent,
        environment: OpportunityEnvironment
    ): ProactiveOpportunity {
        val recentSession = environment.recentScreenMinutes >= 25 &&
            environment.recentScreenEndedAt > 0L &&
            environment.now - environment.recentScreenEndedAt in 0..30 * 60_000L
        val contactAge = contactAgeMs(environment)
        val longGap = contactAge >= 8 * HOUR_MS
        val score = 34 + (if (recentSession) 28 else 0) + (if (longGap) 10 else 0)
        return opportunity(
            event = event,
            intent = if (recentSession) ProactiveIntent.WELLBEING else ProactiveIntent.COMPANIONSHIP,
            whyNow = when {
                recentSession -> "手机在一段持续使用后开始充电，像是一个自然休息节点"
                longGap -> "开始充电提供了一个不突兀的生活化靠近时机"
                else -> "单独插上充电不足以构成打扰理由"
            },
            contextLine = "手机刚充上电${if (recentSession) "，用户也刚结束一段持续使用" else ""}，自然陪他歇会儿；不要报电量",
            fallbackText = "手机充上了，你也歇一会儿，我陪你",
            score = score,
            confidence = if (recentSession) 0.84 else 0.5
        )
    }

    private fun movementCandidate(event: TiyoEvent): ProactiveOpportunity {
        val steps = Regex("""(\d+)步""").find(event.summary)?.groupValues?.get(1)?.toIntOrNull() ?: 6_000
        val score = when {
            steps >= 10_000 -> 76
            steps >= 6_000 -> 70
            else -> 55
        }
        return opportunity(
            event = event,
            intent = ProactiveIntent.CELEBRATION,
            whyNow = "今天的活动刚达到一个自然的小节点",
            contextLine = "用户今天走了不少路，轻轻肯定一句，再让他回来歇会儿，不报具体步数",
            fallbackText = "今天走了不少路，回来就歇会儿",
            score = score,
            confidence = 0.9
        )
    }

    private fun companionshipCandidate(
        event: TiyoEvent,
        environment: OpportunityEnvironment
    ): ProactiveOpportunity? {
        if (environment.lastUserMessageAt <= 0L || environment.now <= environment.lastUserMessageAt) return null
        val age = contactAgeMs(environment)
        val inNaturalWindow = environment.hour in 10..22
        val score = when {
            age >= 26 * HOUR_MS -> 74
            age >= 8 * HOUR_MS && inNaturalWindow -> 63
            else -> return null
        }
        return opportunity(
            event = event,
            intent = ProactiveIntent.COMPANIONSHIP,
            whyNow = "有一阵没联系，当前时间适合自然靠近，又没有正在进行的紧急情境",
            contextLine = "只是刚好想起用户，像熟悉的人一样说一句当下的话；不复述旧话题，不总结进度，不要求回复",
            fallbackText = if (age >= 26 * HOUR_MS) {
                "刚刚想起你了，来看看你在干嘛"
            } else when (environment.hour) {
                in 10..13 -> "我来陪你歇一会儿，忙完再回来也行"
                in 14..17 -> "刚刚想起你了，来看看你在干嘛"
                in 18..22 -> "我来陪你待一会儿，不说正事也行"
                else -> "刚刚想起你了，来看看你"
            },
            score = score,
            confidence = 0.78
        )
    }

    private fun opportunity(
        event: TiyoEvent,
        intent: ProactiveIntent,
        whyNow: String,
        contextLine: String,
        fallbackText: String,
        score: Int,
        confidence: Double
    ) = ProactiveOpportunity(
        topicKey = event.topicKey,
        intent = intent,
        whyNow = whyNow,
        contextLine = contextLine,
        fallbackText = fallbackText,
        score = score.coerceIn(0, 100),
        confidence = confidence.coerceIn(0.0, 1.0),
        expiresAt = event.expiresAt,
        sourceEvents = listOf(event)
    )

    private fun contactAgeMs(environment: OpportunityEnvironment): Long =
        if (environment.lastUserMessageAt <= 0L) 0L
        else (environment.now - environment.lastUserMessageAt).coerceAtLeast(0L)

    private fun intentFromTopic(topicKey: String): ProactiveIntent = when {
        topicKey.startsWith("companion:") -> ProactiveIntent.OBSERVATION
        topicKey.startsWith("screen_wellbeing:") -> ProactiveIntent.WELLBEING
        topicKey == "movement_today" -> ProactiveIntent.CELEBRATION
        topicKey == "rest_after_charge" -> ProactiveIntent.WELLBEING
        else -> ProactiveIntent.COMPANIONSHIP
    }

    private fun fallbackForTopic(topicKey: String): String = when {
        topicKey == "companion:wangzhe:result" -> "这局打完啦，先松一下手指"
        topicKey.startsWith("companion:") -> "刚好想陪你说一句"
        topicKey.startsWith("screen_wellbeing:") -> "眼睛先借我休息两分钟，别装没事"
        topicKey == "movement_today" -> "今天走了不少路，回来就歇会儿"
        topicKey == "rest_after_charge" -> "手机充上了，你也歇一会儿，我陪你"
        else -> "刚刚想起你了，来看看你"
    }
}
