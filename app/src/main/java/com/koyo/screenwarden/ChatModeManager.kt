package com.koyo.screenwarden

import android.content.Context

/**
 * 三档聊天视角的轻量状态入口
 *
 * 只保存当前档和用户默认档，不持有聊天记录；三档始终共用 TiyoSessionStore
 */
object ChatModeManager {
    enum class Mode(val storedValue: String) {
        ROOM("room"),
        DESK("desk"),
        FOCUS("focus");

        companion object {
            fun fromStored(value: String?): Mode = values().firstOrNull {
                it.storedValue == value
            } ?: ROOM
        }
    }

    private const val PREFS = "tiyo_chat_mode"
    private const val KEY_CURRENT = "current_mode"
    private const val KEY_DEFAULT = "default_mode"

    fun current(context: Context): Mode = Mode.fromStored(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CURRENT, null)
    )

    fun setCurrent(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CURRENT, mode.storedValue).apply()
    }

    fun defaultMode(context: Context): Mode = Mode.fromStored(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DEFAULT, null)
    )

    fun setDefaultMode(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DEFAULT, mode.storedValue).apply()
    }

    fun isDefaultFocus(context: Context): Boolean = defaultMode(context) == Mode.FOCUS

    fun setDefaultFocus(context: Context, enabled: Boolean) {
        setDefaultMode(context, if (enabled) Mode.FOCUS else Mode.ROOM)
    }
}
