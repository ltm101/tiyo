package com.koyo.screenwarden

import android.content.Context

/**
 * 邮箱与天气配置：对外发布版由使用者在"我的 → 邮箱设置"里填自己的。
 * 未配置时邮件遥控/报告/天气静默停用，不崩溃、不打扰。
 * 邮箱地址和天气位置存 SharedPreferences，邮箱授权码存 TiyoSecureStore。
 */
object MailConfig {
    private const val PREFS = "tiyo_mail"
    private const val KEY_QQ_EMAIL = "qq_email"
    private const val SECRET_QQ_AUTH = "mail_qq_auth_code"
    private const val KEY_AGENT_EMAIL = "agent_email"
    private const val KEY_LAT = "weather_lat"
    private const val KEY_LON = "weather_lon"

    private fun sp() =
        TiyoApp.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 发件 QQ 邮箱 */
    fun qqEmail(): String = sp().getString(KEY_QQ_EMAIL, "").orEmpty().trim()

    /** 发件 QQ 邮箱授权码 */
    fun qqAuth(): String = TiyoSecureStore.get(TiyoApp.appContext, SECRET_QQ_AUTH).trim()

    /** 收报告/预警的 agent 邮箱 */
    fun agentEmail(): String = sp().getString(KEY_AGENT_EMAIL, "").orEmpty().trim()

    /** 天气纬度 */
    fun weatherLat(): String = sp().getString(KEY_LAT, "").orEmpty().trim()

    /** 天气经度 */
    fun weatherLon(): String = sp().getString(KEY_LON, "").orEmpty().trim()

    /** 发件邮箱是否配齐（QQ邮箱 + 授权码都有） */
    fun isMailReady(): Boolean = qqEmail().isNotEmpty() && qqAuth().isNotEmpty()

    /** 收报告邮箱是否已配置 */
    fun isAgentReady(): Boolean = agentEmail().isNotEmpty()

    /** 天气坐标是否已配置 */
    fun isWeatherReady(): Boolean = weatherLat().isNotEmpty() && weatherLon().isNotEmpty()

    fun save(qqEmail: String, qqAuth: String, agentEmail: String, lat: String, lon: String) {
        sp().edit()
            .putString(KEY_QQ_EMAIL, qqEmail.trim())
            .putString(KEY_AGENT_EMAIL, agentEmail.trim())
            .putString(KEY_LAT, lat.trim())
            .putString(KEY_LON, lon.trim())
            .apply()
        qqAuth.trim().takeIf(String::isNotEmpty)?.let {
            TiyoSecureStore.put(TiyoApp.appContext, SECRET_QQ_AUTH, it)
        }
    }
}
