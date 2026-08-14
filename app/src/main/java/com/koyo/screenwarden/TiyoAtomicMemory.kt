package com.koyo.screenwarden

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Companion-scoped structured memory with deterministic upsert keys. */
object TiyoAtomicMemory {

    const val TYPE_PERSONA = "persona"
    const val TYPE_EPISODIC = "episodic"
    const val TYPE_INSTRUCTION = "instruction"
    const val DEFAULT_PRIORITY = 50

    data class AtomicMemory(
        val type: String,
        val priority: Int,
        val content: String,
        val scene: String = "",
        val occurredAt: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
        /** Stable semantic key, for example user.preference.breakfast. */
        val key: String = ""
    ) {
        val typeLabel: String
            get() = when (type) {
                TYPE_PERSONA -> "特质"
                TYPE_EPISODIC -> "事件"
                TYPE_INSTRUCTION -> "规则"
                else -> "记忆"
            }
    }

    enum class WriteDisposition { CREATED, UPDATED, UNCHANGED }

    data class WriteResult(
        val filename: String,
        val disposition: WriteDisposition
    )

    /** Compatibility entry point. New asynchronous code should pass a captured scope. */
    fun write(context: Context, memory: AtomicMemory): String? =
        upsert(context, CompanionScope.capture(context), memory)?.filename

    fun write(context: Context, scope: CompanionScope, memory: AtomicMemory): String? =
        upsert(context, scope, memory)?.filename

    /**
     * Create or update one memory atomically. A semantic key produces a stable
     * filename, so corrected facts replace their previous value instead of
     * accumulating contradictory timestamped files.
     */
    @Synchronized
    fun upsert(context: Context, scope: CompanionScope, memory: AtomicMemory): WriteResult? {
        val content = memory.content.trim()
        if (content.isBlank()) return null
        val type = memory.type.trim().takeIf(::isSupportedType) ?: TYPE_EPISODIC
        val priority = memory.priority.coerceIn(0, 100)
        val semanticKey = normalizeKey(memory.key)
        val filename = filenameFor(type, semanticKey, content, memory.occurredAt)
        val dir = File(
            CompanionWorkspace.agentHome(context, scope.companionId),
            "memory"
        ).apply { mkdirs() }
        val target = File(dir, filename)
        val existing = target.takeIf(File::isFile)?.runCatching { readText(Charsets.UTF_8) }?.getOrNull()
        if (existing != null && equivalent(existing, type, priority, memory.scene, content, semanticKey)) {
            return WriteResult(filename, WriteDisposition.UNCHANGED)
        }

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val created = existing?.let(::frontmatterCreated)?.ifBlank { null }
            ?: memory.occurredAt.toString()
        val name = filename.removeSuffix(".md")
        val markdown = buildString {
            append("---\n")
            append("name: ").append(yamlStr(name)).append('\n')
            append("description: ").append(yamlStr(memory.typeLabel)).append('\n')
            append("type: ").append(yamlStr(type)).append('\n')
            if (semanticKey.isNotBlank()) append("key: ").append(yamlStr(semanticKey)).append('\n')
            append("priority: ").append(priority).append('\n')
            append("scene: ").append(yamlStr(memory.scene.trim())).append('\n')
            append("created: ").append(created).append('\n')
            append("updated: ").append(now).append('\n')
            append("---\n\n")
            append(content).append('\n')
        }

        return try {
            atomicWrite(target, markdown)
            WriteResult(
                filename,
                if (existing == null) WriteDisposition.CREATED else WriteDisposition.UPDATED
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun normalizeKey(value: String): String = slug(value)
        .replace('-', '.')
        .replace(Regex("\\.+"), ".")
        .trim('.')
        .take(64)

    internal fun filenameFor(
        type: String,
        semanticKey: String,
        content: String,
        occurredAt: OffsetDateTime
    ): String {
        val safeType = slug(type).ifBlank { TYPE_EPISODIC }
        if (semanticKey.isNotBlank()) return "$safeType-${semanticKey.replace('.', '-')}.md".take(96)
        val stamp = "%02d%02d%02d%02d%02d%02d".format(
            occurredAt.year % 100,
            occurredAt.monthValue,
            occurredAt.dayOfMonth,
            occurredAt.hour,
            occurredAt.minute,
            occurredAt.second
        )
        return listOf(safeType, slug(content).take(24).ifBlank { "mem" }, stamp)
            .joinToString("-")
            .take(92) + ".md"
    }

    private fun equivalent(
        existing: String,
        type: String,
        priority: Int,
        scene: String,
        content: String,
        semanticKey: String
    ): Boolean = frontmatterValue(existing, "type") == type &&
        frontmatterValue(existing, "priority").toIntOrNull() == priority &&
        frontmatterValue(existing, "scene") == scene.trim() &&
        frontmatterValue(existing, "key") == semanticKey &&
        markdownBody(existing).trim() == content

    private fun frontmatterCreated(markdown: String): String =
        frontmatterValue(markdown, "created")

    private fun frontmatterValue(markdown: String, key: String): String {
        // Frontmatter is tiny, so a bounded line scan is enough.
        markdown.lineSequence().drop(1).take(32).forEach { value ->
            if (value == "---") return ""
            if (value.startsWith("$key:")) {
                return value.substringAfter(':').trim().removeSurrounding("\"")
                    .replace("\\\"", "\"").replace("\\\\", "\\")
            }
        }
        return ""
    }

    private fun markdownBody(markdown: String): String {
        val first = markdown.indexOf("---")
        val second = if (first >= 0) markdown.indexOf("---", first + 3) else -1
        return if (second >= 0) markdown.substring(second + 3).trimStart() else markdown
    }

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
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

    private fun isSupportedType(value: String): Boolean =
        value in setOf(TYPE_PERSONA, TYPE_EPISODIC, TYPE_INSTRUCTION)

    private fun slug(value: String): String = value.trim()
        .lowercase()
        .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5_.-]"), "-")
        .replace(Regex("-+"), "-")
        .trim('-', '_', '.')

    private fun yamlStr(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
