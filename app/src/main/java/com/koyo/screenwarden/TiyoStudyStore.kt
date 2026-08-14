package com.koyo.screenwarden

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 学习模式消息。type 预留扩展：text / hint / quiz / diagnosis。 */
data class StudyMessage(
    val role: String,      // "user" | "assistant" | "system"
    val type: String,      // "text" | "hint" | "quiz" | "diagnosis"
    val text: String,
    val timestamp: Long
)

/** 学习模式独立存储：对话历史 + 薄弱点。不复用聊天会话，避免污染会话注册表。 */
object TiyoStudyStore {

    private const val PREFS = "tiyo_study"
    private const val KEY_HISTORY = "history_main"
    private const val KEY_WEAK = "weak_points"
    private const val MAX_HISTORY = 60

    fun loadHistory(context: Context): List<StudyMessage> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        StudyMessage(
                            role = item.optString("role", "assistant"),
                            type = item.optString("type", "text"),
                            text = item.optString("text", ""),
                            timestamp = item.optLong("ts", 0L)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveHistory(context: Context, messages: List<StudyMessage>) {
        val array = JSONArray()
        messages.takeLast(MAX_HISTORY).forEach { m ->
            array.put(
                JSONObject()
                    .put("role", m.role)
                    .put("type", m.type)
                    .put("text", m.text)
                    .put("ts", m.timestamp)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun loadWeakPoints(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_WEAK, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    add(array.optString(i, ""))
                }
            }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addWeakPoints(context: Context, points: List<String>) {
        val current = loadWeakPoints(context).toMutableList()
        points.filter { it.isNotBlank() }.forEach { p ->
            if (!current.contains(p)) current.add(p)
        }
        val array = JSONArray()
        current.takeLast(30).forEach { array.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_WEAK, array.toString()).apply()
    }

    fun clearWeakPoints(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_WEAK).apply()
    }
}
