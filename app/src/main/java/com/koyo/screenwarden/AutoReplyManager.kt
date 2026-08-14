package com.koyo.screenwarden

import android.content.Context

/**
 * "可又帮你回"总开关的持久化。默认关。
 * 关着时可又端不生成回复，几乎零成本。
 *
 * 附带同一联系人 1 分钟冷却：避免群消息/连发时疯狂弹拟写通知。
 */
object AutoReplyManager {

    private const val PREFS = "screen_warden_autoreply"
    private const val KEY_ON = "auto_reply_on"
    private const val COOLDOWN_MS = 1 * 60 * 1000L

    fun isOn(ctx: Context): Boolean =
        ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ON, false)

    fun setOn(ctx: Context, on: Boolean) {
        ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ON, on)
            .apply()
    }

    /** 同一联系人冷却：距上次拟写 >= 1 分钟则放行并刷新时间戳；否则 false。 */
    fun canSuggest(ctx: Context, contact: String): Boolean {
        val prefs = ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "last_suggest_" + contact
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(key, 0L) < COOLDOWN_MS) return false
        prefs.edit().putLong(key, now).apply()
        return true
    }

    /** 拟写失败时清掉冷却：失败不该占着 3 分钟，让下一条消息能立刻重试。 */
    fun resetCooldown(ctx: Context, contact: String) {
        ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("last_suggest_" + contact)
            .apply()
    }
}
