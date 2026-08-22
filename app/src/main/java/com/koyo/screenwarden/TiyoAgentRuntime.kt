package com.koyo.screenwarden

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class TiyoAgentRuntimeInfo(
    val port: Int,
    val authToken: String,
    val workspace: File,
    val companionId: String,
    val capabilities: Set<String> = emptySet()
)

internal fun buildTiyoAgentRuntimeCommand(
    binary: File,
    home: File,
    workspace: File,
    port: Int,
    authToken: String,
    staticDir: File
): List<String> = listOf(
    binary.absolutePath,
    "--home", home.absolutePath,
    "--cwd", workspace.absolutePath,
    "serve",
    "--port", port.toString(),
    "--token", authToken,
    "--static-dir", staticDir.absolutePath
)

/** Owns Tiyo's embedded native Agent process. It never launches another Android app. */
object TiyoAgentRuntime {

    private const val PREFS_GLOBAL_MEMORY = "tiyo_global_memory"
    private const val KEY_GLOBAL_MEMORY_ASKED = "asked"
    private const val KEY_GLOBAL_MEMORY_ENABLED = "enabled"

    /** 个人/家庭版默认开启，公开版按首次引导的选择。 */
    fun globalMemoryEnabled(context: Context): Boolean = when (BuildConfig.FLAVOR) {
        "personal", "family" -> true
        "public" -> context.getSharedPreferences(PREFS_GLOBAL_MEMORY, 0)
            .getBoolean(KEY_GLOBAL_MEMORY_ENABLED, false)
        else -> false
    }

    /** 公开版首次引导是否已经问过。 */
    fun globalMemoryAsked(context: Context): Boolean =
        context.getSharedPreferences(PREFS_GLOBAL_MEMORY, 0)
            .getBoolean(KEY_GLOBAL_MEMORY_ASKED, false)

    fun markGlobalMemoryAsked(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_GLOBAL_MEMORY, 0).edit()
            .putBoolean(KEY_GLOBAL_MEMORY_ASKED, true)
            .putBoolean(KEY_GLOBAL_MEMORY_ENABLED, enabled)
            .apply()
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var process: Process? = null
    @Volatile private var runtimeInfo: TiyoAgentRuntimeInfo? = null

    fun isReady(): Boolean = runtimeInfo?.let { info ->
        process?.isAlive == true && health(info.port, info.authToken)
    } == true

    fun currentInfo(): TiyoAgentRuntimeInfo? = runtimeInfo?.takeIf { isReady() }

    fun currentInfo(scope: CompanionScope): TiyoAgentRuntimeInfo? =
        currentInfo()?.takeIf { it.companionId == scope.companionId }

    fun ensureStarted(
        context: Context,
        onReady: (TiyoAgentRuntimeInfo) -> Unit,
        onError: (String) -> Unit
    ) = ensureStarted(context, CompanionScope.capture(context), onReady, onError)

    fun ensureStarted(
        context: Context,
        scope: CompanionScope,
        onReady: (TiyoAgentRuntimeInfo) -> Unit,
        onError: (String) -> Unit
    ) {
        currentInfo(scope)?.let {
            mainHandler.post { onReady(it) }
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            try {
                val info = synchronized(lock) {
                    currentInfo(scope) ?: run {
                        if (process?.isAlive == true) stopLocked()
                        startLocked(appContext, scope)
                    }
                }
                mainHandler.post { onReady(info) }
            } catch (error: Exception) {
                mainHandler.post {
                    onError(error.message?.takeIf { it.isNotBlank() } ?: "Tiyo Agent 启动失败")
                }
            }
        }
    }

    fun restart(
        context: Context,
        onReady: (TiyoAgentRuntimeInfo) -> Unit,
        onError: (String) -> Unit
    ) = restart(context, CompanionScope.capture(context), onReady, onError)

    fun restart(
        context: Context,
        scope: CompanionScope,
        onReady: (TiyoAgentRuntimeInfo) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            synchronized(lock) { stopLocked() }
            mainHandler.post { ensureStarted(context, scope, onReady, onError) }
        }
    }

    private fun startLocked(context: Context, scope: CompanionScope): TiyoAgentRuntimeInfo {
        require(TiyoAgentConfig.isConfigured(context)) { "请先配置模型和 API Key" }

        val binary = File(context.applicationInfo.nativeLibraryDir, "libtiyo_agent.so")
        require(binary.isFile) { "当前安装包缺少 Tiyo Agent 原生核心" }

        val home = CompanionWorkspace.agentHome(context, scope.companionId)
        val configDir = File(home, "config").apply { mkdirs() }
        val staticDir = File(home, "static").apply { mkdirs() }
        File(staticDir, "index.html").writeText("<html><body>Tiyo</body></html>")
        val workspace = CompanionWorkspace.publicRoot(context, scope.companionId)
        writeProviderConfig(context, File(configDir, "providers.json"))

        // Runtime 只作为额外能力包存在，shell/local_shell 永远走 Android 系统 shell
        val runtimeAvailable = TiyoRuntime.ensureInstalled(context)
        require(systemShellIsRunnable(workspace)) {
            "Android 系统 shell 不可用，已停止启动 Agent，避免进入反复 Permission denied 状态"
        }

        val port = ServerSocket(0).use { it.localPort }
        val authToken = TiyoAgentConfig.runtimeToken(context)
        val logFile = File(home, "agent.log")
        if (logFile.length() > 1_000_000) logFile.writeText("")
        logFile.appendText(
            "[runtime] shell=${TiyoRuntime.SYSTEM_SHELL}; " +
                "runtime=${if (runtimeAvailable) "installed-metadata-only" else "unavailable"}\n"
        )

        val builder = ProcessBuilder(
            buildTiyoAgentRuntimeCommand(binary, home, workspace, port, authToken, staticDir)
        )
        builder.directory(workspace)
        builder.redirectErrorStream(true)
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        builder.environment().apply {
            put("HOME", home.absolutePath)
            put("TIYO_HOME", home.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            putAll(
                TiyoRuntime.agentEnvironment(
                    context = context,
                    runtimeAvailable = runtimeAvailable,
                    inheritedPath = get("PATH")
                )
            )
            put("TIYO_WORKBENCH_TOKEN", authToken)
            put("TIYO_PROVIDER_API_KEY", TiyoAgentConfig.providerKey(context))
            put("TIYO_IMAGE_GEN_PROVIDER", TiyoAgentConfig.imageGenProvider(context))
            // 自用版识图走豆包（火山方舟视觉），发布版不注入
            if (BuildConfig.FLAVOR == "personal" && BuildConfig.TIYO_ARK_API_KEY.isNotBlank()) {
                put("TIYO_ARK_API_KEY", BuildConfig.TIYO_ARK_API_KEY)
                put("TIYO_ARK_MODEL", BuildConfig.TIYO_ARK_MODEL)
            }
            put("TIYO_IMAGE_GEN_API_KEY", TiyoAgentConfig.imageGenKey(context))
            put("TIYO_IMAGE_GEN_BASE_URL", TiyoAgentConfig.imageGenBaseUrl(context))
            put("TIYO_IMAGE_GEN_MODEL", TiyoAgentConfig.imageGenModel(context))
            put("RUST_BACKTRACE", "1")
        }

        val started = builder.start()
        process = started
        val ready = waitForHealth(port, 12_000, authToken)
        if (!ready) {
            val tail = logFile.takeIf { it.isFile }?.readLines()?.takeLast(8)?.joinToString("\n")
                .orEmpty()
            started.destroyForcibly()
            process = null
            throw IllegalStateException(
                if (tail.isBlank()) "Tiyo Agent 没有在规定时间内启动" else tail
            )
        }
        // 个人/家庭版默认开启，公开版按首次引导选择；让 Rust 能召回 home/memory 里的全局记忆
        if (globalMemoryEnabled(context)) {
            enableGlobalMemory(port, authToken, true)
        }
        val capabilities = readHealth(port, authToken)
            ?.optJSONArray("capabilities")
            ?.let { array -> buildSet { for (index in 0 until array.length()) add(array.optString(index)) } }
            .orEmpty()
        val info = TiyoAgentRuntimeInfo(port, authToken, workspace, scope.companionId, capabilities)
        runtimeInfo = info
        Thread {
            runCatching { started.waitFor() }
            synchronized(lock) {
                if (process === started) {
                    process = null
                    runtimeInfo = null
                }
            }
        }.apply { name = "tiyo-agent-waiter" }.start()
        return info
    }

    /** 真正执行一次，而不是只看文件或版本标记，提前暴露系统级 EACCES。 */
    private fun systemShellIsRunnable(workspace: File): Boolean = runCatching {
        val probe = ProcessBuilder(TiyoRuntime.SYSTEM_SHELL, "-c", "exit 0")
            .directory(workspace)
            .redirectErrorStream(true)
            .start()
        val finished = probe.waitFor(2, TimeUnit.SECONDS)
        if (!finished) probe.destroyForcibly()
        finished && probe.exitValue() == 0
    }.getOrDefault(false)

    private fun writeProviderConfig(context: Context, target: File) {
        val config = TiyoAgentConfig.load(context)
        val provider = JSONObject()
            .put("type", "openai_compatible")
            .put("tool_protocol", "openai_compatible")
            .put("display", "Tiyo")
            .put("api_key", TiyoAgentConfig.providerKey(context))
            .put("base_url", config.baseUrl)
            .put("model", config.model)
            .put("context_window", 1_000_000)
            .put("max_output_tokens", 8192)
            .put("supports_vision", TiyoAgentConfig.supportsVision(config.model))
            .put("supports_native_tools", true)
        val document = JSONObject()
            .put("active", TiyoAgentConfig.PROVIDER_ID)
            .put("providers", JSONObject().put(TiyoAgentConfig.PROVIDER_ID, provider))
        target.writeText(document.toString(2))
    }

    private fun waitForHealth(port: Int, timeoutMs: Long, authToken: String): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (health(port, authToken)) return true
            Thread.sleep(180)
        }
        return false
    }

    private fun health(port: Int, authToken: String): Boolean {
        return runCatching {
            val connection = URL(
                "http://127.0.0.1:$port/api/runtime/health?token=$authToken"
            ).openConnection() as HttpURLConnection
            connection.connectTimeout = 500
            connection.readTimeout = 500
            connection.requestMethod = "GET"
            val ok = connection.responseCode == 200
            connection.disconnect()
            ok
        }.getOrDefault(false)
    }

    private fun readHealth(port: Int, authToken: String): JSONObject? {
        return runCatching {
            val connection = URL(
                "http://127.0.0.1:$port/api/runtime/health?token=$authToken"
            ).openConnection() as HttpURLConnection
            connection.connectTimeout = 500
            connection.readTimeout = 500
            connection.requestMethod = "GET"
            val body = if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else null
            connection.disconnect()
            body?.let { JSONObject(it) }
        }.getOrNull()
    }

    /** 启动后动态开关跨会话长期记忆；仅当 Agent 已就绪时生效，失败返回 false。 */
    fun setGlobalMemory(enabled: Boolean): Boolean {
        val info = currentInfo() ?: return false
        return enableGlobalMemory(info.port, info.authToken, enabled)
    }

    /** 开启/关闭 Rust 内核的跨会话长期记忆（global memory），失败不阻塞启动。 */
    private fun enableGlobalMemory(port: Int, authToken: String, enabled: Boolean): Boolean {
        return runCatching {
            val connection = URL(
                "http://127.0.0.1:$port/api/runtime/global-memory?token=$authToken"
            ).openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("{\"enabled\":$enabled}".toByteArray()) }
            val ok = connection.responseCode == 200
            connection.disconnect()
            ok
        }.getOrDefault(false)
    }

    private fun stopLocked() {
        process?.let { running ->
            running.destroy()
            runCatching { running.waitFor() }
            if (running.isAlive) running.destroyForcibly()
        }
        process = null
        runtimeInfo = null
    }
}
