package com.koyo.screenwarden

import java.io.File

/** Safe shared-storage operations used by the visible file studio. */
object FileManager {

    private val allowedRoots = listOf(
        File("/sdcard"),
        File("/storage/emulated"),
        File("/storage/self")
    )

    fun isAllowed(path: String): Boolean = runCatching {
        val canonical = File(path).canonicalFile
        allowedRoots.any { root ->
            val allowed = root.canonicalFile
            canonical == allowed || canonical.toPath().startsWith(allowed.toPath())
        }
    }.getOrDefault(false)

    fun listFiles(directory: File): List<File> {
        if (!isAllowed(directory.absolutePath) || !directory.isDirectory) return emptyList()
        return directory.listFiles()
            ?.filter { !it.isHidden }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()
    }

    fun readText(file: File, maxBytes: Int = 2_000_000): Result<String> = runCatching {
        require(isAllowed(file.absolutePath)) { "这个位置不在手机共享文件范围内" }
        require(file.isFile) { "文件不存在" }
        require(!isBinary(file)) { "这是二进制文件，不能作为文字编辑" }
        require(file.length() <= maxBytes) { "文件超过 ${formatSize(maxBytes.toLong())}，请用专门编辑器打开" }
        file.readText(Charsets.UTF_8)
    }

    fun writeText(file: File, content: String): Result<Unit> = runCatching {
        require(isAllowed(file.absolutePath)) { "这个位置不在手机共享文件范围内" }
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    fun create(directory: File, name: String, folder: Boolean): Result<File> = runCatching {
        require(isAllowed(directory.absolutePath)) { "这个位置不可写" }
        val safeName = sanitizeName(name)
        require(safeName.isNotBlank()) { "需要一个名称" }
        val target = File(directory, safeName)
        require(isAllowed(target.absolutePath)) { "目标位置不可写" }
        require(!target.exists()) { "同名文件已经存在" }
        val created = if (folder) target.mkdirs() else {
            target.parentFile?.mkdirs()
            target.createNewFile()
        }
        require(created) { "创建失败" }
        target
    }

    fun rename(file: File, newName: String): Result<File> = runCatching {
        require(isAllowed(file.absolutePath)) { "这个位置不可修改" }
        val target = File(file.parentFile, sanitizeName(newName))
        require(isAllowed(target.absolutePath)) { "目标位置不可写" }
        require(!target.exists()) { "同名文件已经存在" }
        require(file.renameTo(target)) { "重命名失败" }
        target
    }

    fun delete(file: File): Result<Unit> = runCatching {
        require(isAllowed(file.absolutePath)) { "这个位置不可删除" }
        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
        require(deleted) { "删除失败" }
    }

    fun isEditable(file: File): Boolean = file.isFile && !isBinary(file)

    fun isWebsite(file: File): Boolean = file.extension.lowercase() in setOf("html", "htm")

    fun readFile(path: String): String {
        val file = File(path)
        if (!file.exists()) return "文件不存在: $path"
        if (!file.isFile) return listDir(path)
        if (isBinary(file)) {
            return "文件: ${file.name}\n大小: ${formatSize(file.length())}\n类型: 二进制文件"
        }
        return readText(file).fold(
            onSuccess = { text ->
                "File: ${file.name}  (${formatSize(file.length())})\n" +
                    "-".repeat(40) + "\n" + text.lineSequence().take(500).joinToString("\n")
            },
            onFailure = { "读取失败: ${it.message}" }
        )
    }

    fun listDir(path: String): String {
        val directory = File(path)
        if (!directory.exists()) return "路径不存在: $path"
        if (!directory.isDirectory) return readFile(path)
        return buildString {
            appendLine("Directory: $path")
            appendLine("-".repeat(40))
            val items = listFiles(directory)
            items.forEach { file ->
                append(if (file.isDirectory) "[D] " else "[F] ")
                append(file.name)
                if (file.isFile) append("  ${formatSize(file.length())}")
                appendLine()
            }
            if (items.isEmpty()) appendLine("(空目录)")
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

    fun sanitizeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(160)

    private fun isBinary(file: File): Boolean {
        if (file.extension.lowercase() in binaryExtensions) return true
        if (file.length() == 0L) return false
        return runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(1024)
                val count = input.read(buffer)
                (0 until count).any { index -> buffer[index].toInt() == 0 }
            }
        }.getOrDefault(true)
    }

    private val binaryExtensions = setOf(
        "apk", "zip", "rar", "7z", "tar", "gz", "jar",
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svgz",
        "mp3", "mp4", "avi", "mkv", "mov", "flac", "aac", "wav",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "exe", "dll", "so", "dex", "bin", "dat", "db", "sqlite"
    )
}
