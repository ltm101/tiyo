package com.koyo.screenwarden.enuman.experience

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Pure JVM ledger store used by [ExperienceLedger].
 *
 * Keeping the file format and atomic-rename logic here (rather than directly in
 * an Android object) makes the immutability, isolation, corruption-recovery and
 * bounded-retention rules unit-testable without Android framework stubs.
 */
internal class ExperienceLedgerStore(
    private val directory: File,
    private val maxRecords: Int = ExperienceLedger.MAX_RECORDS
) {
    private val lock = Any()
    private val file: File get() = File(directory, FILE_NAME)

    fun append(record: ExperienceRecord): Boolean = synchronized(lock) {
        val existing = readLocked()
        if (existing.any { it.id == record.id }) return@synchronized false
        writeLocked((existing + record).takeLast(maxRecords.coerceAtLeast(1)))
        true
    }

    fun records(): List<ExperienceRecord> = synchronized(lock) { readLocked() }

    fun count(): Int = synchronized(lock) { readLocked().size }

    fun schemaVersion(): Int = SCHEMA_VERSION

    private fun readLocked(): List<ExperienceRecord> {
        if (!file.isFile) return emptyList()
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        return runCatching {
            val json = JSONObject(raw)
            if (json.optInt("schema_version", -1) != SCHEMA_VERSION) return emptyList()
            val array = json.optJSONArray("records") ?: return emptyList()
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let(ExperienceRecord::fromJson)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeLocked(records: List<ExperienceRecord>) {
        directory.mkdirs()
        val document = JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("records", JSONArray(records.map(ExperienceRecord::toJson)))
        val text = document.toString()
        val tmp = File(directory, "$FILE_NAME.tmp-${System.nanoTime()}")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                // Windows / some filesystems may fail rename if target exists; replace via delete+rename.
                if (file.exists() && !file.delete()) {
                    throw IllegalStateException("unable to replace ledger file")
                }
                if (!tmp.renameTo(file)) {
                    throw IllegalStateException("unable to commit ledger file")
                }
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val FILE_NAME = "experience.json"
    }
}
