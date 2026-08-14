package com.koyo.screenwarden

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * "可又帮你回"拟写回复的历史记录，今天页可追溯。
 *
 * 每次弹出建议回复通知时记一条：时间 / 联系人 / 原消息 / 拟写内容 / 状态 / 来源。
 * 用户在通知上点「复制」或「复制并打开」时，把那条记录的状态更新掉。
 * 上限 MAX 条，超出丢最旧的。
 */
object AutoReplyHistory {

    private const val PREFS = "tiyo_autoreply_history"
    private const val KEY_RECORDS = "records"
    private const val SECRET_RECORDS = "autoreply_history_v1"
    const val MAX = 50

    // 状态
    const val STATUS_SUGGESTED = 0   // 已弹建议通知，还没用
    const val STATUS_COPIED = 1      // 点了「复制」
    const val STATUS_OPENED = 2      // 点了「复制并打开」

    data class Record(
        val id: Long,            // 拟写弹通知时的时间戳，作唯一 id
        val timeMillis: Long,
        val contact: String,
        val message: String,     // 对方发来的原消息
        val reply: String,       // 拟好的回复
        val status: Int,
        val source: String       // 通知监听 / 无障碍 / 邮件遥控
    )

    /** 新增一条（最新的排最前），返回 id 供后续更新状态用 */
    fun add(
        ctx: Context,
        contact: String,
        message: String,
        reply: String,
        source: String,
        scope: CompanionScope = CompanionScope.capture(ctx)
    ): Long {
        val id = System.currentTimeMillis()
        val list = load(ctx, scope).toMutableList()
        list.add(0, Record(id, id, contact, message, reply, STATUS_SUGGESTED, source))
        save(ctx, list.take(MAX), scope)
        return id
    }

    /** 用户点通知按钮后更新该记录状态 */
    fun markUsed(
        ctx: Context,
        id: Long,
        opened: Boolean,
        scope: CompanionScope = CompanionScope.capture(ctx)
    ) {
        val list = load(ctx, scope).map {
            if (it.id == id) it.copy(status = if (opened) STATUS_OPENED else STATUS_COPIED) else it
        }
        save(ctx, list, scope)
    }

    fun load(
        ctx: Context,
        scope: CompanionScope = CompanionScope.capture(ctx)
    ): List<Record> {
        val secureKey = scope.namespaced(SECRET_RECORDS)
        var raw = TiyoSecureStore.get(ctx.applicationContext, secureKey)
        if (raw.isBlank() && scope.isBuiltInCompanion) {
            raw = ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECORDS, "[]") ?: "[]"
            if (raw != "[]") {
                // 旧版明文历史只迁移一次，JSON 结构保持不变
                TiyoSecureStore.put(ctx.applicationContext, secureKey, raw)
                ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_RECORDS).apply()
            }
        }
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Record(
                    id = o.optLong("id"),
                    timeMillis = o.optLong("time"),
                    contact = o.optString("contact", ""),
                    message = o.optString("message", ""),
                    reply = o.optString("reply", ""),
                    status = o.optInt("status", STATUS_SUGGESTED),
                    source = o.optString("source", "")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun clear(
        ctx: Context,
        scope: CompanionScope = CompanionScope.capture(ctx)
    ) {
        TiyoSecureStore.remove(ctx.applicationContext, scope.namespaced(SECRET_RECORDS))
        if (scope.isBuiltInCompanion) {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_RECORDS).apply()
        }
    }

    private fun save(ctx: Context, list: List<Record>, scope: CompanionScope) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("time", r.timeMillis)
                    .put("contact", r.contact)
                    .put("message", r.message)
                    .put("reply", r.reply)
                    .put("status", r.status)
                    .put("source", r.source)
            )
        }
        val secureKey = scope.namespaced(SECRET_RECORDS)
        if (list.isEmpty()) {
            TiyoSecureStore.remove(ctx.applicationContext, secureKey)
        } else {
            TiyoSecureStore.put(ctx.applicationContext, secureKey, arr.toString())
        }
    }
}
