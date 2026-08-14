package com.koyo.screenwarden

import android.content.Context

/** 回复性格描述存储：默认Tiyo口吻，用户可自定义，可一键克隆。 */
object ReplyStyleManager {

    private const val PREFS = "tiyo_reply_style"
    private const val KEY_STYLE = "style"

    fun defaultStyle(
        context: Context,
        scope: CompanionScope = CompanionScope.capture(context)
    ): String = "以${scope.displayName}的口吻回复，贴心、自然、活泼但不过分亲昵，句末不加句号"

    fun load(context: Context, scope: CompanionScope = CompanionScope.capture(context)): String =
        context.getSharedPreferences(scope.namespaced(PREFS), Context.MODE_PRIVATE)
            .getString(KEY_STYLE, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: defaultStyle(context, scope)

    fun save(
        context: Context,
        style: String,
        scope: CompanionScope = CompanionScope.capture(context)
    ) {
        context.getSharedPreferences(scope.namespaced(PREFS), Context.MODE_PRIVATE)
            .edit().putString(KEY_STYLE, style.trim()).apply()
    }

    fun reset(context: Context, scope: CompanionScope = CompanionScope.capture(context)) {
        context.getSharedPreferences(scope.namespaced(PREFS), Context.MODE_PRIVATE)
            .edit().remove(KEY_STYLE).apply()
    }
}
