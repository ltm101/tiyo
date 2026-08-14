package com.koyo.screenwarden

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 手机 ↔ 电脑记忆同步客户端。
 * POST 到电脑 KoyoGateway 的 /tiyo/memory/sync，带配对 token，
 * 上传手机记忆候选事件，接收电脑快照并交给 TiyoMemoryBridge 落盘。
 *
 * 用 HttpURLConnection（与现有网关调用一致，一次性阻塞 POST 即可）。
 */
object TiyoMemorySyncClient {

    data class SyncResult(val ok: Boolean, val acked: Int, val message: String)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 阻塞同步；由调用方放到后台线程 */
    fun sync(context: Context, gatewayUrl: String, token: String): SyncResult {
        return sync(context, CompanionScope.capture(context), gatewayUrl, token)
    }

    fun sync(
        context: Context,
        scope: CompanionScope,
        gatewayUrl: String,
        token: String
    ): SyncResult {
        if (!scope.isBuiltInCompanion) {
            return SyncResult(false, 0, "${scope.displayName}使用独立本地记忆，不读取Tiyo的电脑记忆")
        }
        val connection = try {
            URL("${gatewayUrl.trimEnd('/')}/tiyo/memory/sync")
                .openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return SyncResult(false, 0, "电脑地址不合法：${e.message ?: "请检查输入"}")
        }
        return try {
            connection.apply {
                connectTimeout = 4_000
                readTimeout = 8_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-Tiyo-Pairing-Token", token)
            }
            val payload = TiyoMemoryBridge.buildSyncRequest(context, scope).toString()
                .toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            when {
                code == 401 -> SyncResult(false, 0, "配对失败：token 不正确")
                code in 200..299 -> {
                    val body = runCatching { JSONObject(response) }.getOrNull()
                    val acked = body?.optJSONArray("acknowledged")?.length() ?: 0
                    if (body != null) TiyoMemoryBridge.applySyncResponse(context, scope, body)
                    SyncResult(true, acked, "已同步 $acked 条，电脑记忆已更新")
                }
                code == 404 -> SyncResult(false, 0, "电脑没有记忆同步接口，请升级 KoyoGateway")
                else -> SyncResult(false, 0, "请求失败 $code")
            }
        } catch (e: Exception) {
            SyncResult(false, 0, "连不上电脑：${e.message ?: "网络不可用"}")
        } finally {
            connection.disconnect()
        }
    }

    /** 开线程同步，结果回调到主线程 */
    fun syncAsync(
        context: Context,
        gatewayUrl: String,
        onResult: (SyncResult) -> Unit
    ) {
        val appContext = context.applicationContext
        val scope = CompanionScope.capture(appContext)
        val token = TiyoMemoryBridge.loadToken(appContext, scope)
        if (token.isBlank()) {
            mainHandler.post { onResult(SyncResult(false, 0, "先保存配对密钥")) }
            return
        }
        Thread {
            val result = sync(appContext, scope, gatewayUrl, token)
            mainHandler.post { onResult(result) }
        }.start()
    }

    // ---- 电脑记忆库完整导出 ----

    data class ExportResult(val ok: Boolean, val fileCount: Int, val message: String)

    /** 阻塞拉取电脑记忆库导出（GET /tiyo/memory/export?revision=…）；由调用方放后台线程 */
    fun exportMemory(context: Context, gatewayUrl: String, token: String): ExportResult {
        return exportMemory(context, CompanionScope.capture(context), gatewayUrl, token)
    }

    fun exportMemory(
        context: Context,
        scope: CompanionScope,
        gatewayUrl: String,
        token: String
    ): ExportResult {
        if (!scope.isBuiltInCompanion) {
            return ExportResult(false, 0, "${scope.displayName}使用独立本地记忆，不读取Tiyo的电脑记忆")
        }
        val revision = TiyoMemoryBridge.readExportRevision(context, scope)
        val connection = try {
            URL("${gatewayUrl.trimEnd('/')}/tiyo/memory/export?revision=$revision")
                .openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return ExportResult(false, 0, "电脑地址不合法：${e.message ?: "请检查输入"}")
        }
        return try {
            connection.apply {
                connectTimeout = 4_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("X-Tiyo-Pairing-Token", token)
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            when {
                code == 401 -> ExportResult(false, 0, "配对失败：token 不正确")
                code in 200..299 -> {
                    val body = runCatching { JSONObject(response) }.getOrNull()
                        ?: return ExportResult(false, 0, "电脑返回格式异常")
                    val unchanged = body.optBoolean("unchanged", false)
                    val count = TiyoMemoryBridge.applyMemoryExport(context, scope, body)
                    ExportResult(
                        true, count,
                        if (unchanged) "电脑记忆已是最新"
                        else "已拉取 $count 个记忆文件"
                    )
                }
                code == 404 -> ExportResult(false, 0, "电脑没有记忆导出接口，请升级 KoyoGateway")
                else -> ExportResult(false, 0, "请求失败 $code")
            }
        } catch (e: Exception) {
            ExportResult(false, 0, "连不上电脑：${e.message ?: "网络不可用"}")
        } finally {
            connection.disconnect()
        }
    }

    fun exportMemoryAsync(
        context: Context,
        gatewayUrl: String,
        onResult: (ExportResult) -> Unit
    ) {
        val appContext = context.applicationContext
        val scope = CompanionScope.capture(appContext)
        val token = TiyoMemoryBridge.loadToken(appContext, scope)
        if (token.isBlank()) {
            mainHandler.post { onResult(ExportResult(false, 0, "先保存配对密钥")) }
            return
        }
        Thread {
            val result = exportMemory(appContext, scope, gatewayUrl, token)
            mainHandler.post { onResult(result) }
        }.start()
    }

    // ---- 一键同步：先推候选事件+快照，再拉记忆库导出 ----

    data class CombinedSyncResult(
        val ok: Boolean,
        val acked: Int,
        val exported: Int,
        val message: String
    )

    fun syncAllAsync(
        context: Context,
        gatewayUrl: String,
        onResult: (CombinedSyncResult) -> Unit
    ) {
        val appContext = context.applicationContext
        val scope = CompanionScope.capture(appContext)
        val token = TiyoMemoryBridge.loadToken(appContext, scope)
        if (token.isBlank()) {
            mainHandler.post { onResult(CombinedSyncResult(false, 0, 0, "先保存配对密钥")) }
            return
        }
        Thread {
            val push = sync(appContext, scope, gatewayUrl, token)
            val export = exportMemory(appContext, scope, gatewayUrl, token)
            val message = buildString {
                append(if (push.ok) "已同步 ${push.acked} 条本地记忆" else "记忆推送失败")
                append("；").append(export.message)
            }
            mainHandler.post {
                onResult(
                    CombinedSyncResult(
                        ok = push.ok && export.ok,
                        acked = push.acked,
                        exported = export.fileCount,
                        message = message
                    )
                )
            }
        }.start()
    }
}
