package com.koyo.screenwarden

/**
 * Today 页文案引擎：问候语 + "此刻"状态行。
 *
 * 全部本地模板生成，不依赖 Agent（Provider 未配置时也要活）。
 * 语气对齐可又：短、稳、不撒娇、不堆数字。
 * Agent 接通后可在同一入口替换为模型生成，调用方无感。
 */
object GreetingComposer {

    /** 默认会话标题不具备引用价值，过滤掉 */
    private val defaultTitles = setOf("最近的对话", "新对话", "未命名对话")

    data class Input(
        val hour: Int,                      // 0..23
        val weather: String?,               // 如 "多云 28°C"，没有则 null
        val userName: String = "用户",
        val recentChatTitle: String? = null, // 最近一次会话标题（默认标题传 null）
        val recentChatAgeHours: Double? = null
    )

    data class Result(
        val greeting: String,   // 短问候，永不换行（"很晚了，用户"）
        val detail: String?     // 第二行小字：天气/聊天接续，没有就不显示
    )

    fun compose(input: Input): Result {
        val greeting = when (input.hour) {
            in 5..9 -> "早上好，${input.userName}"
            in 10..12 -> "中午好，${input.userName}"
            in 13..17 -> "下午好，${input.userName}"
            in 18..21 -> "晚上好，${input.userName}"
            in 22..23 -> "很晚了，${input.userName}"
            else -> "还没睡吗" // 0..4
        }

        // 近期联系只影响陪伴感，不拿旧标题要求用户续聊
        val detail = chatClause(input) ?: weatherClause(input)
        return Result(greeting, detail)
    }

    private fun weatherClause(input: Input): String? {
        val raw = input.weather?.takeIf { it.isNotBlank() } ?: return null
        val condition = raw.substringBefore(" ")
        val lateNight = input.hour in 22..23 || input.hour in 0..4
        return when {
            condition.contains("雨") && lateNight -> "外面在下雨，今晚适合早点睡"
            condition.contains("雨") -> "今天有雨，出门记得带伞"
            condition.contains("雪") -> "今天有雪，路上慢一点"
            condition.contains("雷") -> "今天有雷，别在窗边待太久"
            condition.contains("晴") && input.hour in 5..11 -> "今天天气不错"
            condition.contains("雾") -> "今天有雾，看不清的路慢点开"
            else -> null
        }
    }

    private fun chatClause(input: Input): String? {
        input.recentChatTitle
            ?.takeIf { it.isNotBlank() && it !in defaultTitles }
            ?: return null
        val age = input.recentChatAgeHours ?: return null
        return when {
            age < 1.0 -> null // 刚聊过，状态行会说
            age < 8.0 -> "想来陪你待会儿，忙你的也行"
            else -> null
        }
    }
}

/**
 * "此刻"状态行：tiyo 当下在做什么、看到了什么。
 * 优先级：刚发生的互动 > 今日的观察 > 陪伴兜底。
 */
object PresenceComposer {

    data class Input(
        val agentReady: Boolean,
        val lastChatAgeMinutes: Double?, // null = 今天没聊过
        val stepsToday: Int,             // -1 = 无数据
        val screenMinutesToday: Int,     // -1 = 无数据
        val hour: Int
    )

    fun compose(input: Input): String {
        input.lastChatAgeMinutes?.let { age ->
            if (age < 45) return "我们刚聊过，还没走远"
        }
        if (input.stepsToday >= 15000) return "你今天走了不少路，我有点佩服"
        if (input.stepsToday in 8000..14999) return "今天走得不错，继续保持"
        if (input.screenMinutesToday >= 360) return "今天盯着屏幕很久了，我没吵你"
        if (input.hour in 22..23 || input.hour in 0..4) return "夜深了，我陪你到睡"
        if (input.agentReady) return "我在，随时叫我"
        return "今天也在陪着你"
    }
}

/**
 * tiyo 建议卡：规则生成（Provider 未配置也要活）。
 * 一条就够，说到就停，是关心的语气不是说教。
 */
object SuggestionComposer {

    private val defaultTitles = setOf("最近的对话", "新对话", "未命名对话")

    data class Input(
        val hour: Int,
        val recentChatTitle: String? = null,
        val recentChatAgeHours: Double? = null,
        val stepsToday: Int = -1,
        val screenMinutesToday: Int = -1,
        val weather: String? = null
    )

    fun compose(input: Input): String {
        if (input.screenMinutesToday >= 420) return "今天屏幕时间很长了，眼睛休息一下吧"
        if (input.stepsToday in 0..2999 && input.hour in 15..20) {
            return "今天还没怎么动，晚点要不要出去走走"
        }
        val condition = input.weather?.substringBefore(" ").orEmpty()
        if (condition.contains("雨")) return "今天有雨，窝在家里做点喜欢的事也不错"
        if (condition.contains("雪")) return "今天有雪，适合喝杯热的"
        if (input.hour in 22..23 || input.hour in 0..4) return "很晚了，明天的事明天再说"
        val hasRecentRealChat = input.recentChatTitle
            ?.takeIf { it.isNotBlank() && it !in defaultTitles } != null &&
            input.recentChatAgeHours?.let { it in 1.0..8.0 } == true
        return if (hasRecentRealChat) "我来陪你待一会儿，不说正事也行" else "今天也加油，我一直在"
    }
}
