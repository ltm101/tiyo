package com.koyo.screenwarden.presence

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import java.io.File

/** 有界、原子写入的本地跨应用事件流。 */
object PresenceEventStore {
    private const val MAX_EVENTS = 200
    private val lock = Any()

    fun record(context: Context, event: PresenceEvent): PresenceEvent = synchronized(lock) {
        val safe = event.persistedCopy()
        val events = readLocked(context).filterNot { it.id == safe.id }.toMutableList()
        events.add(safe)
        writeLocked(context, events.takeLast(MAX_EVENTS))
        safe
    }

    fun recent(context: Context, limit: Int = 50): List<PresenceEvent> = synchronized(lock) {
        readLocked(context).takeLast(limit.coerceIn(0, MAX_EVENTS)).asReversed()
    }

    fun markConsumed(
        context: Context,
        eventId: String,
        consumedAt: Long = System.currentTimeMillis()
    ): Boolean = synchronized(lock) {
        var changed = false
        val events = readLocked(context).map { event ->
            if (event.id == eventId && event.consumedAt == null) {
                changed = true
                event.copy(consumedAt = consumedAt)
            } else event
        }
        if (changed) writeLocked(context, events)
        changed
    }

    private fun readLocked(context: Context): List<PresenceEvent> {
        val atomic = atomicFile(context)
        if (!atomic.baseFile.isFile) return emptyList()
        return runCatching {
            val text = atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = JSONArray(text)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let(PresenceEvent::fromJson)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeLocked(context: Context, events: List<PresenceEvent>) {
        val atomic = atomicFile(context)
        val output = atomic.startWrite()
        try {
            val bytes = JSONArray().apply {
                events.takeLast(MAX_EVENTS).forEach { put(it.toJson()) }
            }.toString().toByteArray(Charsets.UTF_8)
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun atomicFile(context: Context): AtomicFile {
        val dir = File(context.filesDir, "presence").apply { mkdirs() }
        return AtomicFile(File(dir, "events.json"))
    }
}
