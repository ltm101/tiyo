package com.koyo.screenwarden

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/** 本机 Agent 的 MCP 与 Skill 配置，目录与 libtiyo_agent 使用的 TIYO_HOME 保持一致 */
object TiyoExtensionStore {

    data class McpServer(
        val name: String,
        val transport: String,
        val endpoint: String,
        val args: List<String>,
        val enabled: Boolean,
    )

    data class InstalledSkill(
        val name: String,
        val summary: String,
    )

    private fun home(context: Context) =
        CompanionWorkspace.agentHome(context)

    private fun configDir(context: Context) =
        File(home(context), "config").apply { mkdirs() }

    private fun mcpFile(context: Context) = File(configDir(context), "mcp_servers.json")

    private fun skillsFile(context: Context) = File(configDir(context), "skills.json")

    private fun skillsDir(context: Context) =
        File(home(context), "skills").apply { mkdirs() }

    @Synchronized
    fun mcpServers(context: Context): List<McpServer> {
        val servers = readObject(mcpFile(context)).optJSONObject("servers") ?: JSONObject()
        return servers.keys().asSequence().mapNotNull { name ->
            val config = servers.optJSONObject(name) ?: return@mapNotNull null
            val transport = config.optString("transport", "stdio").lowercase()
            val endpoint = if (transport == "stdio") {
                config.optString("command")
            } else {
                config.optString("url")
            }
            McpServer(
                name = name,
                transport = transport,
                endpoint = endpoint,
                args = config.optJSONArray("args").toStringList(),
                enabled = config.optBoolean("enabled", true),
            )
        }.sortedBy { it.name.lowercase() }.toList()
    }

    @Synchronized
    fun saveMcpServer(
        context: Context,
        name: String,
        transport: String,
        endpoint: String,
        rawArgs: String,
    ) {
        val safeName = requireSingleName(name, "MCP 名称")
        val normalizedTransport = transport.lowercase()
        require(normalizedTransport in setOf("stdio", "http", "sse")) { "不支持的 MCP 连接方式" }
        require(endpoint.isNotBlank()) { if (normalizedTransport == "stdio") "请填写命令" else "请填写 URL" }

        val document = readObject(mcpFile(context))
        val servers = document.optJSONObject("servers") ?: JSONObject().also {
            document.put("servers", it)
        }
        val config = JSONObject()
            .put("transport", normalizedTransport)
            .put("enabled", true)
        if (normalizedTransport == "stdio") {
            config.put("command", endpoint.trim())
            config.put("args", JSONArray(parseCommandArgs(rawArgs)))
        } else {
            require(endpoint.trim().startsWith("http://") || endpoint.trim().startsWith("https://")) {
                "远程 MCP 地址需要以 http:// 或 https:// 开头"
            }
            config.put("url", endpoint.trim())
        }
        servers.put(safeName, config)
        writeObject(mcpFile(context), document)
    }

    @Synchronized
    fun removeMcpServer(context: Context, name: String) {
        val document = readObject(mcpFile(context))
        document.optJSONObject("servers")?.remove(name)
        writeObject(mcpFile(context), document)
    }

    fun installedSkills(context: Context): List<InstalledSkill> {
        return skillsDir(context).listFiles()
            .orEmpty()
            .filter { it.isDirectory && File(it, "SKILL.md").isFile }
            .map { dir ->
                InstalledSkill(dir.name, skillSummary(File(dir, "SKILL.md")))
            }
            .sortedBy { it.name.lowercase() }
    }

    /** 导入包含 SKILL.md 的 zip，支持 zip 外层再套一层目录 */
    @Synchronized
    fun installSkillZip(context: Context, uri: Uri, requestedName: String): Result<String> =
        runCatching {
            val temp = File(context.cacheDir, "tiyo-skill-${UUID.randomUUID()}")
            temp.mkdirs()
            try {
                unzipSkill(context, uri, temp)
                val skillFile = temp.walkTopDown()
                    .filter { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }
                    .minByOrNull { it.relativeTo(temp).invariantSeparatorsPath.count { ch -> ch == '/' } }
                    ?: error("压缩包里没有 SKILL.md")
                val sourceRoot = skillFile.parentFile ?: error("Skill 目录无效")
                val suggested = requestedName.trim().ifBlank { sourceRoot.name }
                val safeName = requireSingleName(suggested, "Skill 名称")
                val target = File(skillsDir(context), safeName)
                require(!target.exists()) { "同名 Skill 已存在，请先移除或换个名字" }
                require(sourceRoot.copyRecursively(target, overwrite = false)) { "复制 Skill 文件失败" }
                recordSkill(context, safeName, uri.toString())
                safeName
            } finally {
                temp.deleteRecursively()
            }
        }

    @Synchronized
    fun removeSkill(context: Context, name: String) {
        val root = skillsDir(context).canonicalFile
        val target = File(root, name).canonicalFile
        require(target.parentFile == root) { "Skill 路径无效" }
        if (target.exists()) require(target.deleteRecursively()) { "Skill 文件删除失败" }
        val document = readObject(skillsFile(context))
        document.optJSONObject("skills")?.remove(name)
        writeObject(skillsFile(context), document)
    }

    internal fun parseCommandArgs(raw: String): List<String> {
        val result = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var escaped = false
        fun flush() {
            if (token.isNotEmpty()) {
                result += token.toString()
                token.clear()
            }
        }
        raw.forEach { ch ->
            when {
                escaped -> {
                    token.append(ch)
                    escaped = false
                }
                ch == '\\' && quote != '\'' -> escaped = true
                quote != null && ch == quote -> quote = null
                quote == null && (ch == '\'' || ch == '"') -> quote = ch
                quote == null && ch.isWhitespace() -> flush()
                else -> token.append(ch)
            }
        }
        if (escaped) token.append('\\')
        require(quote == null) { "参数里的引号没有闭合" }
        flush()
        return result
    }

    private fun unzipSkill(context: Context, uri: Uri, target: File) {
        val rootPath = target.canonicalPath + File.separator
        var entries = 0
        var totalBytes = 0L
        val maxBytes = 50L * 1024L * 1024L
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries += 1
                    require(entries <= 1_000) { "Skill 压缩包文件太多" }
                    val output = File(target, entry.name).canonicalFile
                    require(output.canonicalPath.startsWith(rootPath)) { "压缩包包含越界路径" }
                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else {
                        output.parentFile?.mkdirs()
                        output.outputStream().buffered().use { sink ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                totalBytes += read
                                require(totalBytes <= maxBytes) { "Skill 解压后超过 50MB" }
                                sink.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("无法读取这个压缩包")
    }

    private fun recordSkill(context: Context, name: String, source: String) {
        val document = readObject(skillsFile(context))
        val skills = document.optJSONObject("skills") ?: JSONObject().also {
            document.put("skills", it)
        }
        skills.put(
            name,
            JSONObject()
                .put("source", source)
                .put("source_type", "untracked")
                .put("enabled", true)
        )
        writeObject(skillsFile(context), document)
    }

    private fun skillSummary(file: File): String {
        val lines = runCatching { file.readLines().take(30) }.getOrDefault(emptyList())
        val description = lines.firstOrNull { it.trim().startsWith("description:", true) }
            ?.substringAfter(':')
            ?.trim()
            ?.trim('"', '\'')
        return description?.takeIf { it.isNotBlank() }
            ?: lines.firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") }
                ?.trim()
                ?.take(120)
            ?: "按需能力包"
    }

    private fun requireSingleName(value: String, label: String): String {
        val name = value.trim()
        require(name.isNotBlank()) { "请填写$label" }
        require(name.length <= 64) { "${label}不能超过64个字符" }
        require(name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.isISOControl() }) {
            "${label}不能包含路径符号"
        }
        return name
    }

    private fun readObject(file: File): JSONObject = runCatching {
        if (file.isFile) JSONObject(file.readText()) else JSONObject()
    }.getOrElse { JSONObject() }

    private fun writeObject(file: File, value: JSONObject) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(value.toString(2))
        if (!temp.renameTo(file)) {
            file.writeText(value.toString(2))
            temp.delete()
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }
    }
}
