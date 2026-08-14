package com.koyo.screenwarden.events

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

/** 小型原子文件队列，不引入 Room。队列中只有脱敏事件摘要。 */
object EventQueue {
    private const val MAX_EVENTS = 128
    private val lock = Any()

    fun enqueue(context: Context, event: TiyoEvent): Boolean = synchronized(lock) {
        val safe = event.persistedCopy()
        val events = load(context).toMutableList()
        val window = dedupeWindowMs(safe.type)
        val duplicate = events.any {
            it.type == safe.type && it.summary == safe.summary &&
                safe.occurredAt - it.occurredAt in 0..window
        }
        if (duplicate) return@synchronized false
        events += safe
        write(context, events.sortedBy { it.occurredAt }.takeLast(MAX_EVENTS))
        true
    }

    fun takeReady(context: Context, now: Long, max: Int = 12): List<TiyoEvent> = synchronized(lock) {
        val events = load(context)
        val ready = events.filter { it.notBefore <= now }.take(max)
        if (ready.isNotEmpty()) {
            val ids = ready.mapTo(HashSet()) { it.id }
            write(context, events.filterNot { it.id in ids })
        }
        ready
    }

    fun nextReadyAt(context: Context): Long? = synchronized(lock) {
        load(context).minOfOrNull { it.notBefore }
    }

    internal fun queueFile(context: Context): File =
        File(File(context.filesDir, "tiyo-events"), "events.json")

    private fun load(context: Context): List<TiyoEvent> {
        val file = queueFile(context)
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let(TiyoEvent::fromJson)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, events: List<TiyoEvent>) {
        val file = queueFile(context)
        file.parentFile?.mkdirs()
        val array = JSONArray().also { out -> events.forEach { out.put(it.toJson()) } }
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private fun dedupeWindowMs(type: TiyoEventType): Long = when (type) {
        TiyoEventType.NOTIFICATION -> 30_000L
        TiyoEventType.NOTIFICATION_BURST -> 10 * 60_000L
        TiyoEventType.SCREEN_ON, TiyoEventType.SCREEN_OFF -> 5 * 60_000L
        TiyoEventType.SCREEN_SESSION -> 30 * 60_000L
        TiyoEventType.POWER_CONNECTED, TiyoEventType.POWER_DISCONNECTED -> 2 * 60_000L
        TiyoEventType.TIME_ANCHOR -> 30 * 60_000L
        TiyoEventType.COMPANION_CONTEXT -> 30 * 60_000L
        else -> 15 * 60_000L
    }
}
