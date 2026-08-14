package com.koyo.screenwarden

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Tiyo 专属 Runtime（Python / Node.js / Git）安装与环境。
 *
 * The optional bootstrap is not distributed by the open-source repository.
 * A maintainer may provide an app-id-compatible archive under [ASSET_PATH].
 *
 * 生命周期：首次启动把 assets 里的 zip 原子解压到 files/usr，
 * 用 .tiyo-runtime-version 标记做版本校验，升级时整树替换。
 */
object TiyoRuntime {

    // Maintainers that bundle an optional runtime must update VERSION and SHA256.
    const val VERSION = "not-bundled"
    const val ASSET_PATH = "runtime/bootstrap-aarch64.zip"
    const val SHA256 = ""

    private const val MARKER = ".tiyo-runtime-version"
    private const val SYMLINKS_FILE = "SYMLINKS.txt"
    private const val BUFFER = 128 * 1024
    const val SYSTEM_SHELL = "/system/bin/sh"

    private val SYSTEM_PATH_PREFIXES = listOf(
        "/system/",
        "/system_ext/",
        "/product/",
        "/vendor/",
        "/odm/",
        "/apex/"
    )

    /** usr 前缀根，对应编译时的 TERMUX_PREFIX */
    fun prefix(context: Context): File = File(context.filesDir, "usr")

    /** Runtime 自己的 HOME，不覆盖 Agent 配置目录 */
    fun runtimeHome(context: Context): File = File(context.filesDir, "home")

    fun isInstalled(context: Context): Boolean {
        val marker = File(prefix(context), MARKER)
        return marker.isFile && marker.readText().trim() == VERSION
    }

    /**
     * 返回 true 表示 Runtime 可用（已安装且通过版本校验）。
     * 首次启动会自动完成解压；失败时返回 false 而不是抛异常，
     * 让上层继续走系统工具兜底。
     */
    fun ensureInstalled(context: Context): Boolean {
        if (isInstalled(context)) return true
        if (!hasBundledRuntime(context)) return false
        return runCatching { install(context) }.getOrElse {
            prefix(context).deleteRecursively()
            false
        }
    }

    private fun hasBundledRuntime(context: Context): Boolean = runCatching {
        context.assets.open(ASSET_PATH).use { }
        true
    }.getOrDefault(false)

    /** Agent 启动时要注入的 Runtime 环境变量 */
    fun environment(context: Context): Map<String, String> {
        val usr = prefix(context)
        return mapOf(
            "PATH" to "${usr}/bin:/system/bin",
            "LD_LIBRARY_PATH" to "${usr}/lib",
            "TMPDIR" to File(usr, "tmp").absolutePath,
            "SHELL" to "${usr}/bin/bash",
            "TERMUX_PREFIX" to usr.absolutePath,
            "TIYO_SHELL" to "${usr}/bin/bash",
            "COOMI_SHELL" to "${usr}/bin/bash"
        )
    }

    /**
     * 内嵌 Agent 专用环境
     *
     * Android 10+ 禁止从可写 app home 直接 execve，因此 Agent 的 shell 和 PATH
     * 绝不能指向 files/usr/bin。Runtime 前缀只作为能力元数据保留；需要 Python、
     * Node 或 Git 的专用调用仍使用 [environment]，不能污染 shell/local_shell。
     */
    fun agentEnvironment(
        context: Context,
        runtimeAvailable: Boolean,
        inheritedPath: String?
    ): Map<String, String> = agentEnvironment(
        runtimePrefix = prefix(context).takeIf { runtimeAvailable },
        inheritedPath = inheritedPath
    )

    internal fun agentEnvironment(
        runtimePrefix: File?,
        inheritedPath: String?
    ): Map<String, String> = buildMap {
        put("PATH", safeSystemPath(inheritedPath))
        put("SHELL", SYSTEM_SHELL)
        put("TIYO_SHELL", SYSTEM_SHELL)
        put("COOMI_SHELL", SYSTEM_SHELL)
        runtimePrefix?.let { put("TIYO_RUNTIME_PREFIX", it.absolutePath) }
    }

    /** 丢掉所有可写 app 目录，只保留 Android 系统提供的可执行路径。 */
    internal fun safeSystemPath(inheritedPath: String?): String {
        // 这是写给 Android 子进程的 PATH，即使 JVM 单测跑在 Windows 也必须按 ':' 解析
        val safe = inheritedPath.orEmpty()
            .split(':')
            .map(String::trim)
            .filter(String::isNotBlank)
            .filter { entry -> SYSTEM_PATH_PREFIXES.any(entry::startsWith) }
            .distinct()
        return safe.takeIf { it.isNotEmpty() }?.joinToString(":")
            ?: "/system/bin"
    }

    private fun install(context: Context): Boolean {
        val zipCache = copyAssetToCache(context)
        val digest = sha256(zipCache)
        check(SHA256.isBlank() || digest.equals(SHA256, ignoreCase = true)) {
            "bootstrap SHA-256 不匹配"
        }

        val usr = prefix(context)
        val tmp = File(context.filesDir, "usr.tmp.${System.currentTimeMillis()}")
        tmp.deleteRecursively()
        tmp.mkdirs()

        extract(zipCache, tmp)
        restoreSymlinks(File(tmp, SYMLINKS_FILE), tmp)
        File(tmp, SYMLINKS_FILE).delete()
        File(tmp, MARKER).writeText(VERSION)

        // 原子替换：失败则回滚，绝不留下半套 usr
        val backup = File(context.filesDir, "usr.bak.${System.currentTimeMillis()}")
        val replaced = if (usr.exists()) {
            usr.renameTo(backup) && tmp.renameTo(usr)
        } else {
            tmp.renameTo(usr)
        }
        check(replaced) { "usr 目录替换失败" }
        backup.deleteRecursively()
        zipCache.delete()
        return true
    }

    private fun copyAssetToCache(context: Context): File {
        val target = File(context.cacheDir, "bootstrap-aarch64.zip")
        context.assets.open(ASSET_PATH).use { input ->
            target.outputStream().use { output -> input.copyTo(output, BUFFER) }
        }
        return target
    }

    private fun extract(zipFile: File, dest: File) {
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val name = entry.name.removePrefix("./")
                if (name.isBlank()) return@forEach
                val target = File(dest, name)
                if (entry.isDirectory) {
                    target.mkdirs()
                    return@forEach
                }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output, BUFFER) }
                }
                // Android 解压不保留 zip 里的权限位，按布局或 shebang 补执行位
                if (shouldBeExecutable(name) || isShebangScript(target)) {
                    target.setExecutable(true, false)
                }
            }
        }
    }

    /** 补 x 位的布局规则：bin/、libexec/，以及 npm 等包放在 lib/node_modules 下各包 bin 子目录的脚本 */
    private fun shouldBeExecutable(name: String): Boolean {
        val first = name.substringBefore('/')
        if (first == "bin" || first == "libexec") return true
        return name.startsWith("lib/node_modules/") && name.contains("/bin/")
    }

    /** 以 shebang（#!）开头的脚本也需要执行位 */
    private fun isShebangScript(file: File): Boolean {
        return runCatching {
            file.inputStream().use { input ->
                val b = ByteArray(2)
                val read = input.read(b)
                read == 2 && b[0] == '#'.code.toByte() && b[1] == '!'.code.toByte()
            }
        }.getOrDefault(false)
    }

    /** SYMLINKS.txt 每行格式：target←link（link 相对 usr 前缀） */
    private fun restoreSymlinks(symlinksFile: File, dest: File) {
        if (!symlinksFile.isFile) return
        symlinksFile.readLines().forEach { line ->
            val sep = line.indexOf('←')
            if (sep <= 0) return@forEach
            val linkPath = line.substring(sep + 1).removePrefix("./")
            if (linkPath.isBlank()) return@forEach
            val link = File(dest, linkPath)
            link.parentFile?.mkdirs()
            // target 是相对 PREFIX 的路径，从 dest 解析
            val target = line.substring(0, sep).removePrefix("./")
            link.delete()
            try {
                // target 按 POSIX 语义相对于链接所在目录解析，原样写入
                java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target))
            } catch (_: Exception) {
                // 个别环境不允许符号链接时退化为拷贝；相对 target 按链接所在目录解析
                val src = if (target.startsWith("/")) File(target) else File(link.parentFile, target)
                if (src.isFile) src.copyTo(link)
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
