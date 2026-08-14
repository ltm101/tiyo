package com.koyo.screenwarden

import android.content.Context

/** Deep companion mode preferences are deliberately isolated from normal chat settings. */
internal object DeepCompanionPrefs {
    private const val PREFS = "deep_companion_v1"
    private const val KEY_DEFAULT = "open_by_default"
    private const val KEY_ASKED = "asked_default_once"

    fun opensByDefault(context: Context): Boolean = prefs(context).getBoolean(KEY_DEFAULT, false)

    fun hasAskedDefault(context: Context): Boolean = prefs(context).getBoolean(KEY_ASKED, false)

    fun setOpensByDefault(context: Context, enabled: Boolean, markAsked: Boolean = true) {
        prefs(context).edit()
            .putBoolean(KEY_DEFAULT, enabled)
            .apply {
                if (markAsked) putBoolean(KEY_ASKED, true)
            }
            .apply()
    }

    fun answerDefaultQuestion(context: Context, enabled: Boolean) {
        setOpensByDefault(context, enabled, markAsked = true)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
