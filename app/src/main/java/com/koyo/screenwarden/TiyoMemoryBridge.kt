package com.koyo.screenwarden

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 手机 agent 记忆桥：本地待同步队列（outbox）+ 电脑快照 + 配对配置。
 *
 * 手机 agent 每次 memory_write 时，ChatFragment 把参数交过来转成
 * memory_candidate 事件存进 outbox；连上电脑 KoyoGateway 后一键推送，
 * 并拉回电脑端快照写成手机记忆文件，让 agent 能读到电脑侧记忆。
 *
 * outbox / snapshot / gateway 存普通 SharedPreferences（非机密），
 * 配对 token 走 TiyoSecureStore（AndroidKeyStore 加密）。
 */
object TiyoMemoryBridge {

    private const val PREFS = "tiyo_memory"
    private const val KEY_OUTBOX = "memory_outbox"
    private const val KEY_SNAPSHOT = "memory_snapshot"
    private const val KEY_GATEWAY = "gateway_url"
    private const val KEY_EXPORT_REVISION = "memory_export_revision"

    private const val TOKEN_KEY = "memory_sync_token"

    private const val MAX_OUTBOX = 100
    private const val MAX_EVENT_CHARS = 1000
    private const val MAX_SNAPSHOT_CHARS = 6000
    private const val SNAPSHOT_NAME = "computer-snapshot"
    private const val MEMORY_DIR_NAME = "memory"
    private const val STAGING_DIR_NAME = ".memory-staging"
    private const val GUIDANCE_MARKER = "## 电脑记忆库"

    const val DEFAULT_GATEWAY = "http://192.168.1.10:8888"

    // ---- 配对 / 网关 ----

    fun loadGateway(context: Context): String = loadGateway(context, CompanionScope.capture(context))

    fun loadGateway(context: Context, scope: CompanionScope): String =
        prefs(context, scope).getString(KEY_GATEWAY, DEFAULT_GATEWAY).orEmpty()
            .ifBlank { DEFAULT_GATEWAY }

    fun saveGateway(context: Context, url: String) {
        saveGateway(context, CompanionScope.capture(context), url)
    }

    fun saveGateway(context: Context, scope: CompanionScope, url: String) {
        prefs(context, scope).edit().putString(KEY_GATEWAY, url.trim()).apply()
    }

    fun loadToken(context: Context): String = loadToken(context, CompanionScope.capture(context))

    fun loadToken(context: Context, scope: CompanionScope): String =
        TiyoSecureStore.get(context, tokenKey(scope))

    fun saveToken(context: Context, token: String) {
        saveToken(context, CompanionScope.capture(context), token)
    }

    fun saveToken(context: Context, scope: CompanionScope, token: String) {
        TiyoSecureStore.put(context, tokenKey(scope), token.trim())
    }

    fun hasToken(context: Context): Boolean = loadToken(context).isNotBlank()

    fun hasToken(context: Context, scope: CompanionScope): Boolean =
        loadToken(context, scope).isNotBlank()

    // ---- outbox ----

    /** 把 memory_write 的参数组装成记忆候选事件，加入待同步队列 */
    fun enqueueMemoryWrite(context: Context, arguments: JSONObject) {
        enqueueMemoryWrite(context, CompanionScope.capture(context), arguments)
    }

    fun enqueueMemoryWrite(context: Context, scope: CompanionScope, arguments: JSONObject) {
        val content = memoryWriteToContent(scope, arguments)
        if (content.isBlank()) return
        val outbox = readOutbox(context, scope)
        outbox.put(
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("type", "memory_candidate")
                .put("createdAt", System.currentTimeMillis())
                .put("content", content.take(MAX_EVENT_CHARS))
        )
        while (outbox.length() > MAX_OUTBOX) outbox.remove(0)
        prefs(context, scope).edit().putString(KEY_OUTBOX, outbox.toString()).apply()
    }

    fun outboxCount(context: Context): Int =
        outboxCount(context, CompanionScope.capture(context))

    fun outboxCount(context: Context, scope: CompanionScope): Int =
        readOutbox(context, scope).length()

    /**
     * 面向用户的本地记忆落盘：把 memory_write 参数直接写成标准 frontmatter md，
     * 落到 files/tiyo-agent/memory/，让"可又的时刻"时间线能读到。
     * 手机本地原生闭环，不依赖电脑同步；与 Rust 记忆同名去重（本目录先被扫描）。
     */
    fun saveLocalMemory(context: Context, arguments: JSONObject) {
        saveLocalMemory(context, CompanionScope.capture(context), arguments)
    }

    fun saveLocalMemory(context: Context, scope: CompanionScope, arguments: JSONObject) {
        val name = arguments.optString("name").trim()
        if (name.isBlank()) return
        val now = OffsetDateTime.now(ZoneOffset.UTC).toString()
        val description = arguments.optString("description").trim()
            .ifBlank { "手机${scope.displayName}记录的记忆" }
        val type = arguments.optString("type").trim().ifBlank { "project" }
        val content = arguments.optString("content").trim()
        val safeName = name.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "-").take(60)
        val md = buildString {
            append("---\n")
            append("name: ").append(yamlStr(name)).append('\n')
            append("description: ").append(yamlStr(description)).append('\n')
            append("type: ").append(yamlStr(type)).append('\n')
            append("created: ").append(now).append('\n')
            append("updated: ").append(now).append('\n')
            append("---\n\n")
            if (content.isNotBlank()) append(content).append('\n')
        }
        val dir = File(CompanionWorkspace.agentHome(context, scope.companionId), "memory").apply { mkdirs() }
        atomicWriteText(File(dir, "$safeName.md"), md)
    }

    private fun yamlStr(s: String) =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** 构造同步请求体：deviceId + 上次快照 revision + outbox 候选事件 */
    fun buildSyncRequest(context: Context): JSONObject =
        buildSyncRequest(context, CompanionScope.capture(context))

    fun buildSyncRequest(context: Context, scope: CompanionScope): JSONObject {
        val deviceId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        return JSONObject()
            .put("deviceId", deviceId)
            .put("revision", readSnapshot(context, scope).optLong("revision", 0L))
            .put("events", readOutbox(context, scope))
    }

    /** 处理同步响应：存快照 + 按 acknowledged 清理 outbox */
    fun applySyncResponse(context: Context, response: JSONObject) {
        applySyncResponse(context, CompanionScope.capture(context), response)
    }

    fun applySyncResponse(context: Context, scope: CompanionScope, response: JSONObject) {
        response.optJSONObject("snapshot")?.let { snap ->
            val sanitized = JSONObject()
                .put("revision", snap.optLong("revision", 0L))
                .put("identity", snap.optString("identity").take(MAX_SNAPSHOT_CHARS))
                .put("about", snap.optString("about").take(MAX_SNAPSHOT_CHARS))
                .put("now", snap.optString("now").take(MAX_SNAPSHOT_CHARS))
                .put("liveContext", snap.optString("liveContext").take(MAX_SNAPSHOT_CHARS))
                .put("updatedAt", snap.optLong("updatedAt", System.currentTimeMillis()))
            prefs(context, scope).edit().putString(KEY_SNAPSHOT, sanitized.toString()).apply()
            saveSnapshotToMemory(context, scope)
        }
        val acknowledged = response.optJSONArray("acknowledged") ?: return
        val ids = mutableSetOf<String>()
        for (index in 0 until acknowledged.length()) {
            acknowledged.optString(index).takeIf { it.isNotBlank() }?.let(ids::add)
        }
        if (ids.isEmpty()) return
        val existing = readOutbox(context, scope)
        val remaining = JSONArray()
        for (index in 0 until existing.length()) {
            val event = existing.optJSONObject(index) ?: continue
            if (event.optString("id") !in ids) remaining.put(event)
        }
        prefs(context, scope).edit().putString(KEY_OUTBOX, remaining.toString()).apply()
    }

    // ---- snapshot ----

    fun readSnapshot(context: Context): JSONObject {
        return readSnapshot(context, CompanionScope.capture(context))
    }

    fun readSnapshot(context: Context, scope: CompanionScope): JSONObject {
        val raw = prefs(context, scope).getString(KEY_SNAPSHOT, null)
        return runCatching {
            if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
        }.getOrDefault(JSONObject())
    }

    /**
     * 把电脑快照写成手机 agent 全局记忆目录下的标准 frontmatter md，
     * 与 Rust memory.rs 反序列化格式一致，memory_read 可读，今天页记忆时间线也能显示。
     */
    fun saveSnapshotToMemory(context: Context) {
        saveSnapshotToMemory(context, CompanionScope.capture(context))
    }

    fun saveSnapshotToMemory(context: Context, scope: CompanionScope) {
        if (!scope.isBuiltInCompanion) return
        val snap = readSnapshot(context, scope)
        if (snap.length() == 0) return
        val now = OffsetDateTime.now(ZoneOffset.UTC).toString()
        val body = buildString {
            snap.optString("about").takeIf { it.isNotBlank() }?.let {
                append("## 关于用户\n").append(it).append("\n\n")
            }
            snap.optString("now").takeIf { it.isNotBlank() }?.let {
                append("## 当前状态\n").append(it).append("\n\n")
            }
            snap.optString("liveContext").takeIf { it.isNotBlank() }?.let {
                append("## 跨系统近期动态\n").append(it).append("\n")
            }
        }
        if (body.isBlank()) return
        val md = buildString {
            append("---\n")
            append("name: ").append(SNAPSHOT_NAME).append('\n')
            append("description: 电脑端可又的共享记忆快照，来自 KoyoGateway 同步\n")
            append("type: reference\n")
            append("created: ").append(now).append('\n')
            append("updated: ").append(now).append('\n')
            append("---\n\n").append(body)
        }
        val dir = File(CompanionWorkspace.agentHome(context, scope.companionId), "memory").apply { mkdirs() }
        atomicWriteText(File(dir, "$SNAPSHOT_NAME.md"), md)
    }

    // ---- 电脑记忆库完整导出 ----

    /** 电脑记忆库导出目录（agent cwd 之下，read_file/list_files 可直接读） */
    fun exportDir(context: Context): File =
        exportDir(context, CompanionScope.capture(context))

    fun exportDir(context: Context, scope: CompanionScope): File =
        File(CompanionWorkspace.publicRoot(context, scope.companionId), MEMORY_DIR_NAME).apply { mkdirs() }

    fun readExportRevision(context: Context): Long =
        readExportRevision(context, CompanionScope.capture(context))

    fun readExportRevision(context: Context, scope: CompanionScope): Long =
        prefs(context, scope).getLong(KEY_EXPORT_REVISION, 0L)

    /**
     * 把导出响应落到 <workspace>/memory/。
     * 先写 staging，全部成功后整体替换并更新 revision；任何一步失败都保留旧文件与旧 revision。
     * 返回成功写入文件数；unchanged 返回 0。
     */
    fun applyMemoryExport(context: Context, response: JSONObject): Int {
        return applyMemoryExport(context, CompanionScope.capture(context), response)
    }

    fun applyMemoryExport(context: Context, scope: CompanionScope, response: JSONObject): Int {
        val revision = response.optLong("revision", 0L)
        val files = response.optJSONArray("files") ?: return 0
        if (files.length() == 0) {
            // unchanged：只更新 revision，不动文件
            if (revision > 0) prefs(context, scope).edit().putLong(KEY_EXPORT_REVISION, revision).apply()
            return 0
        }
        val workspace = CompanionWorkspace.publicRoot(context, scope.companionId)
        val staging = File(workspace, STAGING_DIR_NAME)
        val backup = File(workspace, ".memory-backup")
        return try {
            staging.deleteRecursively()
            backup.deleteRecursively()
            staging.mkdirs()
            var written = 0
            for (i in 0 until files.length()) {
                val entry = files.optJSONObject(i) ?: continue
                val path = entry.optString("path")
                if (path.isBlank() || resolveInside(staging, path) == null) continue
                val stageFile = File(staging, path)
                stageFile.parentFile?.mkdirs()
                stageFile.writeText(entry.optString("content"), Charsets.UTF_8)
                written++
            }
            if (written == 0) return 0
            val targetRoot = File(workspace, MEMORY_DIR_NAME)
            if (targetRoot.exists() && !targetRoot.renameTo(backup)) {
                targetRoot.copyRecursively(backup, overwrite = true)
                check(targetRoot.deleteRecursively()) { "无法准备记忆目录替换" }
            }
            val installed = staging.renameTo(targetRoot) || runCatching {
                staging.copyRecursively(targetRoot, overwrite = true)
                staging.deleteRecursively()
                true
            }.getOrDefault(false)
            if (!installed) {
                targetRoot.deleteRecursively()
                if (backup.exists()) backup.renameTo(targetRoot)
                error("无法安装新的记忆导出")
            }
            backup.deleteRecursively()
            prefs(context, scope).edit().putLong(KEY_EXPORT_REVISION, revision).apply()
            ensureMemoryGuidance(context, scope)
            written
        } catch (e: Exception) {
            staging.deleteRecursively()
            val targetRoot = File(workspace, MEMORY_DIR_NAME)
            if (backup.exists()) {
                targetRoot.deleteRecursively()
                if (!backup.renameTo(targetRoot)) {
                    backup.copyRecursively(targetRoot, overwrite = true)
                    backup.deleteRecursively()
                }
            }
            0
        }
    }

    /** path 必须落在 root 之下（规范化解算），逃逸返回 null —— 防 ../ 路径逃逸 */
    private fun resolveInside(root: File, path: String): File? {
        return try {
            val target = File(root, path)
            val cr = root.canonicalFile
            val ct = target.canonicalFile
            if (ct.toPath().startsWith(cr.toPath())) target else null
        } catch (_: Exception) {
            null
        }
    }

    /** 幂等：确保 TIYO.md 里有"## 电脑记忆库"指引段 */
    fun ensureMemoryGuidance(context: Context) {
        ensureMemoryGuidance(context, CompanionScope.capture(context))
    }

    fun ensureMemoryGuidance(context: Context, scope: CompanionScope) {
        val personaFile = CompanionWorkspace.personaFile(context, scope.companionId)
        val existing = if (personaFile.isFile) personaFile.readText() else ""
        if (existing.contains(GUIDANCE_MARKER)) return
        val guidance = buildString {
            if (!scope.isBuiltInCompanion) {
                append("\n\n## 独立记忆空间\n\n")
                append("你是${scope.displayName}，只使用这个角色目录下的本地记忆与会话。\n")
                append("不要读取、冒领或推测可又及其他角色的私人记忆、日记和历史会话。\n")
                append("需要回忆时先浏览本工作区 `memory/`，只把与你和用户真实发生过的内容当作共同经历。")
                return@buildString
            }
            append("\n\n## 电脑记忆库\n\n")
            append("用户的电脑记忆系统已同步到本工作区 `memory/` 目录，这就是\"用户的完整记忆\"：\n\n")
            append("- `diary/`：按日期 YYYY-MM-DD.md 的日记，是主要生活与事件记录\n")
            append("- `context/stream.md`（值得跨会话记住的事）、`context/now.md`（当前状态）、`context/live_context.md`（最近跨系统动态）\n")
            append("- `user/`：关于用户的人物设定；`soul/`：${scope.displayName}的人格、反思、价值观\n")
            append("- `memory_index.md`：日记检索索引；想找某天发生的事先读它，再按日期打开 diary\n")
            append("- `state/current_state.md`：电脑侧当前关注点\n\n")
            append("手机本地记忆也结构化：`files/tiyo-agent/memory/` 下 `persona-*`（用户特质）、`episodic-*`（事件）、`instruction-*`（行为规则）带优先级，回忆某个话题时优先读这些文件。\n\n")
            append("使用：对话涉及过去的经历、项目进度、\"你还记得吗\"时，先 `list_files memory/` 浏览，再 `read_file memory/<对应路径>` 取内容。和电脑上一样，相似不是调用理由，价值才是。")
        }
        personaFile.parentFile?.mkdirs()
        atomicWriteText(personaFile, existing + guidance)
    }

    /** UI 展示用：电脑记忆库同步摘要 */
    fun memoryExportSummary(context: Context): String {
        return memoryExportSummary(context, CompanionScope.capture(context))
    }

    fun memoryExportSummary(context: Context, scope: CompanionScope): String {
        val count = exportDir(context, scope).listFiles()?.size ?: 0
        if (count == 0) return "电脑记忆库尚未同步"
        return "电脑记忆库 $count 个文件"
    }

    // ---- 内部 ----

    /** memory_write 参数 → 可读中文事件文本（网关原样落盘，不解析结构） */
    private fun memoryWriteToContent(scope: CompanionScope, arguments: JSONObject): String {
        val name = arguments.optString("name")
        val type = arguments.optString("type")
        val memoryScope = arguments.optString("scope")
        val description = arguments.optString("description")
        val content = arguments.optString("content")
        return buildString {
            append("手机端").append(scope.displayName).append("记录了一条记忆")
            if (name.isNotBlank()) append("\n名称：").append(name)
            if (type.isNotBlank()) append("\n类型：").append(type)
            if (memoryScope.isNotBlank()) append("\n范围：").append(memoryScope)
            if (description.isNotBlank()) append("\n说明：").append(description)
            if (content.isNotBlank()) append("\n内容：").append(content)
        }.trim()
    }

    private fun readOutbox(context: Context, scope: CompanionScope): JSONArray {
        val raw = prefs(context, scope).getString(KEY_OUTBOX, null)
        return runCatching {
            if (raw.isNullOrBlank()) JSONArray() else JSONArray(raw)
        }.getOrDefault(JSONArray())
    }

    private fun prefs(context: Context, scope: CompanionScope): SharedPreferences =
        context.getSharedPreferences(scope.namespaced(PREFS), Context.MODE_PRIVATE)

    private fun tokenKey(scope: CompanionScope): String = scope.namespaced(TOKEN_KEY)

    private fun atomicWriteText(target: File, content: String) {
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
}
