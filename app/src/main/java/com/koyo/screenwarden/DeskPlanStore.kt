package com.koyo.screenwarden

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object DeskPlanStore {
    data class Item(val id: String, val text: String, val done: Boolean)
    private const val PREFS = "deep_companion_desk"
    private const val KEY_ITEMS = "plan_items_v2"
    private const val KEY_OLD_PLAN = "plan"
    private const val KEY_LAST_COMPLETED = "last_plan_completed_at"

    fun load(context: Context): List<Item> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ITEMS, "").orEmpty()
        if (raw.isNotBlank()) return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                Item(item.optString("id"), item.optString("text"), item.optBoolean("done"))
            }.filter { it.text.isNotBlank() }
        }.getOrDefault(emptyList())
        val old = prefs.getString(KEY_OLD_PLAN, "把眼前这一小步做完").orEmpty()
        return replace(context, old.lines())
    }

    fun replace(context: Context, lines: List<String>): List<Item> {
        val items = lines.map(String::trim).filter(String::isNotBlank).take(12)
            .map { Item(UUID.randomUUID().toString(), it.take(80), false) }
        save(context, items)
        return items
    }

    fun setDone(context: Context, id: String, done: Boolean): Item? {
        val items = load(context).toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = items[index].copy(done = done)
        items[index] = updated
        save(context, items)
        if (done) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_COMPLETED, System.currentTimeMillis()).apply()
        return updated
    }

    fun hasUnfinished(context: Context): Boolean = load(context).any { !it.done }
    fun lastCompletedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_COMPLETED, 0L)

    private fun save(context: Context, items: List<Item>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id", it.id).put("text", it.text).put("done", it.done)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, array.toString()).apply()
    }
}
