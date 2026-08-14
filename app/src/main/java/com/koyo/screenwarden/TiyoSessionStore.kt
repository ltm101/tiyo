package com.koyo.screenwarden

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TiyoChatSession(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val pinned: Boolean = false
)

/** Companion-scoped session persistence. */
object TiyoSessionStore {
    private const val KEY_ACTIVE = "active_session"
    private const val KEY_REGISTRY = "registry_v1"
    private const val HISTORY_PREFIX = "history_"
    private val scopeLocks = ConcurrentHashMap<String, Any>()

    fun activeId(context: Context, legacyId: String? = null): String =
        activeId(context, CompanionScope.capture(context), legacyId)

    fun activeId(context: Context, scope: CompanionScope, legacyId: String? = null): String =
        synchronized(lock(scope)) {
            val preferences = prefs(context, scope)
            preferences.getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }?.let { return it }
            val id = legacyId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            preferences.edit()
                .putString(KEY_ACTIVE, id)
                .putString(KEY_REGISTRY, JSONArray().put(sessionJson(TiyoChatSession(id, "最近的对话", now))).toString())
                .commit()
            id
        }

    fun sessions(context: Context): List<TiyoChatSession> =
        sessions(context, CompanionScope.capture(context))

    fun sessions(context: Context, scope: CompanionScope): List<TiyoChatSession> {
        val raw = prefs(context, scope).getString(KEY_REGISTRY, "[]").orEmpty()
        val result = mutableListOf<TiyoChatSession>()
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                result += TiyoChatSession(
                    id = id,
                    title = item.optString("title").ifBlank { "未命名对话" },
                    updatedAt = item.optLong("updated_at"),
                    pinned = item.optBoolean("pinned")
                )
            }
        }
        return result.sortedWith(
            compareByDescending<TiyoChatSession> { it.pinned }.thenByDescending { it.updatedAt }
        )
    }

    fun create(context: Context): TiyoChatSession = create(context, CompanionScope.capture(context))

    fun create(context: Context, scope: CompanionScope): TiyoChatSession = synchronized(lock(scope)) {
        val session = TiyoChatSession(
            id = UUID.randomUUID().toString(),
            title = "新对话",
            updatedAt = System.currentTimeMillis()
        )
        writeRegistry(context, scope, sessions(context, scope) + session)
        activate(context, scope, session.id)
        session
    }

    fun activate(context: Context, id: String) = activate(context, CompanionScope.capture(context), id)

    fun activate(context: Context, scope: CompanionScope, id: String) = synchronized(lock(scope)) {
        prefs(context, scope).edit().putString(KEY_ACTIVE, id).commit()
        touch(context, scope, id)
    }

    fun touch(context: Context, id: String, title: String? = null) =
        touch(context, CompanionScope.capture(context), id, title)

    fun touch(context: Context, scope: CompanionScope, id: String, title: String? = null) =
        synchronized(lock(scope)) {
            val now = System.currentTimeMillis()
            val current = sessions(context, scope).toMutableList()
            val index = current.indexOfFirst { it.id == id }
            if (index >= 0) {
                val previous = current[index]
                current[index] = previous.copy(
                    title = title?.takeIf { it.isNotBlank() } ?: previous.title,
                    updatedAt = now
                )
            } else {
                current += TiyoChatSession(id, title ?: "新对话", now)
            }
            writeRegistry(context, scope, current)
        }

    fun setPinned(context: Context, id: String, pinned: Boolean) =
        setPinned(context, CompanionScope.capture(context), id, pinned)

    fun setPinned(context: Context, scope: CompanionScope, id: String, pinned: Boolean) =
        synchronized(lock(scope)) {
            writeRegistry(context, scope, sessions(context, scope).map {
                if (it.id == id) it.copy(pinned = pinned) else it
            })
        }

    fun delete(context: Context, id: String) = delete(context, CompanionScope.capture(context), id)

    fun delete(context: Context, scope: CompanionScope, id: String) = synchronized(lock(scope)) {
        prefs(context, scope).edit().remove(HISTORY_PREFIX + id).commit()
        writeRegistry(context, scope, sessions(context, scope).filterNot { it.id == id })
    }

    fun history(context: Context, id: String): String? =
        history(context, CompanionScope.capture(context), id)

    fun history(context: Context, scope: CompanionScope, id: String): String? =
        prefs(context, scope).getString(HISTORY_PREFIX + id, null)

    fun saveHistory(context: Context, id: String, json: String) =
        saveHistory(context, CompanionScope.capture(context), id, json)

    fun saveHistory(context: Context, scope: CompanionScope, id: String, json: String) =
        synchronized(lock(scope)) {
            prefs(context, scope).edit().putString(HISTORY_PREFIX + id, json).commit()
            touch(context, scope, id)
        }

    fun appendAssistantMessage(
        context: Context,
        id: String,
        text: String,
        sticker: String? = null
    ): Boolean = appendAssistantMessage(context, CompanionScope.capture(context), id, text, sticker)

    /** Atomic read-modify-write prevents UI saves and background delivery from losing each other. */
    fun appendAssistantMessage(
        context: Context,
        scope: CompanionScope,
        id: String,
        text: String,
        sticker: String? = null
    ): Boolean = synchronized(lock(scope)) {
        if (text.isBlank() || id.isBlank()) return false
        val raw = history(context, scope, id) ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        while (array.length() >= 60) array.remove(0)
        val item = JSONObject()
            .put("role", "assistant")
            .put("text", text)
            .put("timestamp", System.currentTimeMillis())
        if (sticker != null) item.put("sticker", sticker)
        array.put(item)
        saveHistory(context, scope, id, array.toString())
        true
    }

    fun migrateLegacyHistory(context: Context, id: String, raw: String?) =
        migrateLegacyHistory(context, CompanionScope.capture(context), id, raw)

    fun migrateLegacyHistory(context: Context, scope: CompanionScope, id: String, raw: String?) =
        synchronized(lock(scope)) {
            if (raw.isNullOrBlank() || history(context, scope, id) != null) return
            saveHistory(context, scope, id, raw)
        }

    private fun writeRegistry(context: Context, scope: CompanionScope, sessions: List<TiyoChatSession>) {
        val array = JSONArray()
        sessions.distinctBy { it.id }.take(80).forEach { array.put(sessionJson(it)) }
        prefs(context, scope).edit().putString(KEY_REGISTRY, array.toString()).commit()
    }

    private fun prefs(context: Context, scope: CompanionScope) = context.getSharedPreferences(
        CompanionWorkspace.sessionsPrefsName(scope.companionId),
        Context.MODE_PRIVATE
    )

    private fun lock(scope: CompanionScope): Any =
        scopeLocks.getOrPut(scope.companionId) { Any() }

    private fun sessionJson(session: TiyoChatSession): JSONObject = JSONObject()
        .put("id", session.id)
        .put("title", session.title)
        .put("updated_at", session.updatedAt)
        .put("pinned", session.pinned)
}
