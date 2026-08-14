package com.koyo.screenwarden

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 记忆加载器：直接读 Agent 落盘的记忆 markdown（与 Rust MemoryManager 同格式），
 * 不走 websocket，Provider 没配置时目录为空也能优雅降级。
 *
 * 扫描三个层级（与 MemoryManager 一致）：
 *  - global:  files/tiyo-agent/memory/
 *  - project: files/tiyo-agent/projects/<hash>/memory/
 *  - local:   <workspace>/.tiyo/memory/
 *
 * 管教：忘掉 = 删文件 + 清 MEMORY.md 索引行；重要 = 本地 prefs 标记（不改 Rust 格式）。
 */
object MemoryTimelineLoader {

    data class MemoryItem(
        val name: String,
        val description: String,
        val updatedMillis: Long,
        val file: File,
        /** 结构化原子记忆类型（persona/episodic/instruction）；非原子记忆为 null */
        val atomType: String? = null,
        /** 原子记忆优先级 0-100；非原子记忆为 null */
        val atomPriority: Int? = null
    ) {
        val typeLabel: String
            get() = when (atomType) {
                TiyoAtomicMemory.TYPE_PERSONA -> "特质"
                TiyoAtomicMemory.TYPE_EPISODIC -> "事件"
                TiyoAtomicMemory.TYPE_INSTRUCTION -> "规则"
                else -> ""
            }
    }

    private const val PREFS = "tiyo_memory_care"
    private const val KEY_IMPORTANT = "important_names"

    // ---------- 扫描 ----------

    fun scan(
        context: Context,
        scope: CompanionScope = CompanionScope.capture(context)
    ): List<MemoryItem> {
        val dirs = mutableListOf<File>()
        val agentHome = CompanionWorkspace.agentHome(context, scope.companionId)
        dirs += File(agentHome, "memory")
        File(agentHome, "projects").listFiles()?.forEach { proj ->
            dirs += File(proj, "memory")
        }
        try {
            dirs += File(CompanionWorkspace.publicRoot(context, scope.companionId), ".tiyo/memory")
        } catch (_: Exception) {}
        // 电脑记忆库导出目录（TiyoMemoryBridge.applyMemoryExport 落盘处，
        // 与桌面端导出的记忆结构同构：diary/ context/ user/ soul/ 等）
        try {
            dirs += CompanionWorkspace.memoryRoot(context, scope.companionId)
        } catch (_: Exception) {}

        val result = mutableListOf<MemoryItem>()
        val seen = HashSet<String>()
        for (dir in dirs) {
            dir.listFiles()?.filter { it.extension == "md" && it.name != "MEMORY.md" }?.forEach { file ->
                val item = parse(file) ?: return@forEach
                // 与 Rust 一致：同名去重，先扫到的（local 优先顺序无所谓，够用）优先
                if (seen.add(item.name)) result += item
            }
        }
        return result
    }

    /** frontmatter 是 serde_yaml：name/description/type/created/updated + 原子记忆的 priority */
    private fun parse(file: File): MemoryItem? {
        return try {
            val text = file.readText()
            if (!text.startsWith("---\n")) return null
            val end = text.indexOf("\n---\n")
            if (end < 0) return null
            val fm = text.substring(4, end)
            val name = yamlField(fm, "name") ?: file.nameWithoutExtension
            val description = yamlField(fm, "description") ?: return null
            val updated = yamlField(fm, "updated")?.let { parseIso(it) }
                ?: file.lastModified()
            val atomType = yamlField(fm, "type")?.takeIf {
                it in setOf(
                    TiyoAtomicMemory.TYPE_PERSONA,
                    TiyoAtomicMemory.TYPE_EPISODIC,
                    TiyoAtomicMemory.TYPE_INSTRUCTION
                )
            }
            val atomPriority = yamlField(fm, "priority")?.toIntOrNull()
                ?.coerceIn(0, 100)
            MemoryItem(name, description, updated, file, atomType, atomPriority)
        } catch (_: Exception) { null }
    }

    /**
     * 按相关度 + 优先级召回记忆。关键词子串匹配打分（name*5 / description*3 / content*1），
     * 命中优先级（priority > 0）的记忆再按 (1 + priority/100) 加权，按综合分降序返回。
     * 保留 scan() 兼容时间线展示；本方法给记忆管理页搜索框和 agent 记忆引用。
     */
    fun recall(
        context: Context,
        query: String,
        limit: Int = 5,
        scope: CompanionScope = CompanionScope.capture(context)
    ): List<MemoryItem> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val terms = q.split(Regex("[^\\w\\u4e00-\\u9fa5-]+"))
            .map { it.lowercase() }
            .filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()

        val scored = mutableListOf<Pair<Int, MemoryItem>>()
        for (item in scan(context, scope)) {
            val name = item.name.lowercase()
            val description = item.description.lowercase()
            val content = readContent(item)?.lowercase().orEmpty()
            var score = 0
            for (term in terms) {
                if (name.contains(term)) score += 5
                if (description.contains(term)) score += 3
                if (content.contains(term)) score += 1
            }
            if (score <= 0) continue
            val priorityWeight = item.atomPriority?.let { 1 + it / 100.0 } ?: 1.0
            scored.add(((score * priorityWeight).toInt().coerceAtLeast(1)) to item)
        }
        scored.sortByDescending { it.first }
        return scored.take(limit.coerceAtLeast(1)).map { it.second }
    }

    private fun yamlField(fm: String, key: String): String? {
        val m = Regex("(?m)^$key:\\s*(.+)$").find(fm) ?: return null
        return m.groupValues[1].trim().removeSurrounding("\"")
            .removeSurrounding("'").takeIf { it.isNotBlank() }
    }

    private fun parseIso(raw: String): Long? {
        return try {
            val clean = raw.removeSurrounding("\"").removeSurrounding("'")
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(clean.substringBefore('Z').substringBefore('+'))?.time
        } catch (_: Exception) { null }
    }

    /** 读记忆 md 全文（详情页用）；文件不存在或读失败返回 null */
    fun readContent(item: MemoryItem): String? =
        runCatching { if (item.file.isFile) item.file.readText() else null }.getOrNull()

    // ---------- 管教 ----------

    fun isImportant(context: Context, name: String): Boolean =
        importantSet(context).contains(name)

    fun setImportant(context: Context, name: String, important: Boolean) {
        val set = importantSet(context)
        if (important) set.add(name) else set.remove(name)
        carePrefs(context)
            .edit().putStringSet(KEY_IMPORTANT, set).apply()
    }

    private fun importantSet(context: Context): MutableSet<String> =
        HashSet(
            carePrefs(context)
                .getStringSet(KEY_IMPORTANT, emptySet()) ?: emptySet()
        )

    /** 忘掉：删 md 文件 + 清掉 MEMORY.md 里的索引行 */
    fun forget(item: MemoryItem): Boolean {
        return try {
            item.file.delete()
            val index = File(item.file.parentFile, "MEMORY.md")
            if (index.isFile) {
                val kept = index.readLines()
                    .filterNot { it.contains("](./${item.name}.md)") }
                index.writeText(kept.joinToString("\n"))
            }
            true
        } catch (_: Exception) { false }
    }

    private fun carePrefs(context: Context) = context.getSharedPreferences(
        if (CompanionProfileStore.activeId(context) == CompanionProfileRules.DEFAULT_COMPANION_ID) {
            PREFS
        } else {
            "${PREFS}_${CompanionProfileStore.activeId(context)}"
        },
        Context.MODE_PRIVATE
    )
}
