package com.koyo.screenwarden

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal data class TiyoMemoryExtractionJob(
    val id: String,
    val scope: CompanionScope,
    val sessionId: String,
    val turns: List<TiyoMemoryExtractor.Turn>,
    val createdAt: Long
) {
    val lastMessageAt: Long
        get() = turns.maxOfOrNull { it.timestamp } ?: 0L
}

internal object TiyoMemoryJobStore {
    private const val PREFS = "tiyo_memory_extract_v2"
    private const val KEY_STATE = "state"
    private const val KEY_MESSAGE = "message"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_CREATED = "created_count"
    private const val KEY_UPDATED = "updated_count"
    private const val KEY_UNCHANGED = "unchanged_count"
    private const val CURSOR_PREFIX = "cursor_"
    private const val ENQUEUED_PREFIX = "enqueued_"
    private const val LAST_ENQUEUE_PREFIX = "last_enqueue_"

    @Synchronized
    fun save(context: Context, job: TiyoMemoryExtractionJob): Boolean = runCatching {
        val json = JSONObject()
            .put("id", job.id)
            .put("companion_id", job.scope.companionId)
            .put("companion_name", job.scope.displayName)
            .put("session_id", job.sessionId)
            .put("created_at", job.createdAt)
            .put("turns", JSONArray().also { array ->
                job.turns.forEach { turn ->
                    array.put(
                        JSONObject()
                            .put("text", turn.text)
                            .put("is_user", turn.isUser)
                            .put("timestamp", turn.timestamp)
                    )
                }
            })
        atomicWrite(file(context, job.scope, job.id), json.toString())
        true
    }.getOrDefault(false)

    fun load(context: Context, scope: CompanionScope, jobId: String): TiyoMemoryExtractionJob? {
        val json = runCatching {
            JSONObject(file(context, scope, jobId).readText(Charsets.UTF_8))
        }.getOrNull() ?: return null
        if (json.optString("companion_id") != scope.companionId) return null
        val turnsJson = json.optJSONArray("turns") ?: return null
        val turns = buildList {
            for (index in 0 until turnsJson.length()) {
                val item = turnsJson.optJSONObject(index) ?: continue
                val text = item.optString("text").trim()
                if (text.isBlank()) continue
                add(
                    TiyoMemoryExtractor.Turn(
                        text = text,
                        isUser = item.optBoolean("is_user"),
                        timestamp = item.optLong("timestamp")
                    )
                )
            }
        }
        if (turns.isEmpty()) return null
        return TiyoMemoryExtractionJob(
            id = json.optString("id"),
            scope = CompanionScope.of(
                json.optString("companion_id"),
                json.optString("companion_name")
            ),
            sessionId = json.optString("session_id"),
            turns = turns,
            createdAt = json.optLong("created_at")
        )
    }

    @Synchronized
    fun delete(context: Context, scope: CompanionScope, jobId: String) {
        val target = file(context, scope, jobId)
        if (target.exists()) target.delete()
        File(target.path + ".bak").delete()
    }

    fun successfulCursor(context: Context, scope: CompanionScope, sessionId: String): Long =
        prefs(context, scope).getLong(CURSOR_PREFIX + sessionKey(sessionId), 0L)

    fun enqueuedCursor(context: Context, scope: CompanionScope, sessionId: String): Long =
        prefs(context, scope).getLong(ENQUEUED_PREFIX + sessionKey(sessionId), 0L)

    fun lastEnqueuedAt(context: Context, scope: CompanionScope, sessionId: String): Long =
        prefs(context, scope).getLong(LAST_ENQUEUE_PREFIX + sessionKey(sessionId), 0L)

    fun markQueued(context: Context, job: TiyoMemoryExtractionJob): Boolean =
        prefs(context, job.scope).edit()
            .putLong(ENQUEUED_PREFIX + sessionKey(job.sessionId), job.lastMessageAt)
            .putLong(LAST_ENQUEUE_PREFIX + sessionKey(job.sessionId), job.createdAt)
            .putString(KEY_STATE, TiyoMemoryExtractor.UpdateState.QUEUED.name)
            .putString(KEY_MESSAGE, "等待提炼")
            .putLong(KEY_UPDATED_AT, job.createdAt)
            .commit()

    fun markRunning(context: Context, scope: CompanionScope) {
        updateStatus(context, scope, TiyoMemoryExtractor.UpdateState.RUNNING, "正在提炼")
    }

    fun markSucceeded(
        context: Context,
        job: TiyoMemoryExtractionJob,
        result: TiyoMemoryExtractor.ExtractionResult
    ) {
        val key = sessionKey(job.sessionId)
        val preferences = prefs(context, job.scope)
        val cursor = maxOf(preferences.getLong(CURSOR_PREFIX + key, 0L), job.lastMessageAt)
        preferences.edit()
            .putLong(CURSOR_PREFIX + key, cursor)
            .putString(KEY_STATE, TiyoMemoryExtractor.UpdateState.SUCCEEDED.name)
            .putString(KEY_MESSAGE, result.message)
            .putInt(KEY_CREATED, result.created)
            .putInt(KEY_UPDATED, result.updated)
            .putInt(KEY_UNCHANGED, result.unchanged)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .commit()
    }

    fun markRetrying(context: Context, scope: CompanionScope, message: String) {
        updateStatus(context, scope, TiyoMemoryExtractor.UpdateState.RETRYING, message)
    }

    fun markFailed(context: Context, job: TiyoMemoryExtractionJob, message: String) {
        val preferences = prefs(context, job.scope)
        val key = sessionKey(job.sessionId)
        val successful = preferences.getLong(CURSOR_PREFIX + key, 0L)
        val enqueued = preferences.getLong(ENQUEUED_PREFIX + key, 0L)
        val editor = preferences.edit()
            .putString(KEY_STATE, TiyoMemoryExtractor.UpdateState.FAILED.name)
            .putString(KEY_MESSAGE, message.take(240))
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
        // Only rewind this failed job's reservation. A later job may already
        // have advanced the session and must keep its own boundary.
        if (enqueued <= job.lastMessageAt) {
            editor
                .putLong(ENQUEUED_PREFIX + key, successful)
                .putLong(LAST_ENQUEUE_PREFIX + key, 0L)
        }
        editor.commit()
    }

    fun latestStatus(context: Context, scope: CompanionScope): TiyoMemoryExtractor.UpdateStatus {
        val preferences = prefs(context, scope)
        val state = runCatching {
            TiyoMemoryExtractor.UpdateState.valueOf(
                preferences.getString(KEY_STATE, TiyoMemoryExtractor.UpdateState.IDLE.name).orEmpty()
            )
        }.getOrDefault(TiyoMemoryExtractor.UpdateState.IDLE)
        return TiyoMemoryExtractor.UpdateStatus(
            state = state,
            message = preferences.getString(KEY_MESSAGE, "").orEmpty(),
            updatedAt = preferences.getLong(KEY_UPDATED_AT, 0L),
            created = preferences.getInt(KEY_CREATED, 0),
            updated = preferences.getInt(KEY_UPDATED, 0),
            unchanged = preferences.getInt(KEY_UNCHANGED, 0)
        )
    }

    private fun updateStatus(
        context: Context,
        scope: CompanionScope,
        state: TiyoMemoryExtractor.UpdateState,
        message: String
    ) {
        prefs(context, scope).edit()
            .putString(KEY_STATE, state.name)
            .putString(KEY_MESSAGE, message.take(240))
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .commit()
    }

    private fun prefs(context: Context, scope: CompanionScope) =
        context.getSharedPreferences(scope.namespaced(PREFS), Context.MODE_PRIVATE)

    private fun file(context: Context, scope: CompanionScope, jobId: String): File {
        val root = File(CompanionWorkspace.privateRoot(context, scope.companionId), "memory-jobs")
            .apply { mkdirs() }
        val safeId = jobId.replace(Regex("[^a-zA-Z0-9-]"), "").take(64)
        return File(root, "$safeId.json")
    }

    private fun sessionKey(sessionId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sessionId.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun atomicWrite(target: File, content: String) {
        val atomic = AtomicFile(target)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(content.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            stream?.let(atomic::failWrite)
            throw error
        }
    }
}
