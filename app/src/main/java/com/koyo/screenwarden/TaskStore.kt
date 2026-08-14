package com.koyo.screenwarden

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 今日任务（本地轻量待办，v1 不接 Agent）。
 * 存 SharedPreferences JSON，UI-5 任务系统落地后可平滑替换数据源。
 */
object TaskStore {

    data class Task(
        val id: String,
        val text: String,
        val timeLabel: String,   // 可空串，如 "14:00"
        val done: Boolean,
        val createdAt: Long
    )

    private const val PREFS = "tiyo_tasks"
    private const val KEY = "tasks_v1"

    fun list(context: Context): List<Task> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]").orEmpty()
        val out = mutableListOf<Task>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out += Task(
                    id = o.optString("id"),
                    text = o.optString("text"),
                    timeLabel = o.optString("time"),
                    done = o.optBoolean("done"),
                    createdAt = o.optLong("created_at")
                )
            }
        }
        // 未完成在前（按创建时间），完成沉底
        return out.sortedWith(compareBy<Task> { it.done }.thenBy { it.createdAt })
    }

    fun add(context: Context, text: String, timeLabel: String): Task {
        val task = Task(
            id = UUID.randomUUID().toString(),
            text = text.trim(),
            timeLabel = timeLabel.trim(),
            done = false,
            createdAt = System.currentTimeMillis()
        )
        save(context, list(context) + task)
        return task
    }

    fun toggle(context: Context, id: String) {
        save(context, list(context).map {
            if (it.id == id) it.copy(done = !it.done) else it
        })
    }

    fun remove(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
    }

    private fun save(context: Context, tasks: List<Task>) {
        val arr = JSONArray()
        tasks.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("text", t.text)
                    .put("time", t.timeLabel)
                    .put("done", t.done)
                    .put("created_at", t.createdAt)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
