package com.koyo.screenwarden

import android.content.Context

/**
 * 用户设置：对外发布版首次启动时收集的称呼与年龄段。
 * 明文存 SharedPreferences（称呼/年龄段不是敏感数据），模式参照 TiyoAgentConfig。
 */
object UserPrefs {
    private const val PREFS = "tiyo_user"
    private const val KEY_NAME = "user_name"
    private const val KEY_ONBOARDED = "onboarded"
    private const val KEY_AGE_GROUP = "age_group"

    /** 年龄段档位。决定Tiyo的人格应对、表情包频率与文字大小等。 */
    enum class AgeGroup(val key: String, val label: String) {
        CHILD("child", "0-15岁"),
        YOUTH("youth", "16-30岁"),
        MIDDLE("middle", "30-60岁"),
        ELDER("elder", "60岁及以上");

        companion object {
            fun fromKey(key: String?): AgeGroup =
                entries.firstOrNull { it.key == key } ?: YOUTH
        }
    }

    private fun sp(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 用户设置的称呼；未设置返回空串 */
    fun getName(context: Context): String =
        sp(context).getString(KEY_NAME, "").orEmpty().trim()

    /** 未设置称呼时 UI 用的兜底称呼 */
    fun displayName(context: Context): String = getName(context).ifBlank { "用户" }

    fun setName(context: Context, name: String) {
        sp(context).edit().putString(KEY_NAME, normalizeName(name)).apply()
    }

    /** 称呼会进入 UI 与 TIYO.md，收拢空白并限制长度，避免粘贴换行污染人格文件。 */
    internal fun normalizeName(name: String): String =
        name.replace(Regex("\\s+"), " ").trim().take(20)

    /** 用户年龄段；未设置默认青年档 */
    fun getAgeGroup(context: Context): AgeGroup =
        AgeGroup.fromKey(sp(context).getString(KEY_AGE_GROUP, null))

    fun setAgeGroup(context: Context, group: AgeGroup) {
        sp(context).edit().putString(KEY_AGE_GROUP, group.key).apply()
    }

    fun isOnboarded(context: Context): Boolean =
        sp(context).getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(context: Context, value: Boolean = true) {
        sp(context).edit().putBoolean(KEY_ONBOARDED, value).apply()
    }
}
