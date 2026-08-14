package com.koyo.screenwarden

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * 动作执行引擎：读 driver 描述 → 经对应 connector 发指令 → 归一化结果。
 *
 * 只认动作名，不写死任何设备。LAN 驱动经 LanConnector 的 HTTP 能力发指令；
 * BLE 驱动由 BleConnector 承接（第 6/7 步接入）。
 */
object PeripheralActionEngine {

    sealed class Result {
        data class Success(val reply: String = "") : Result()
        data class HttpError(val code: Int, val message: String) : Result()
        object Timeout : Result()
        object NetworkError : Result()
    }

    /**
     * 执行 LAN 驱动的一个动作。
     *
     * @param baseUrl          设备基地址，如 http://koyo-car.local
     * @param driver           驱动描述（提供 connection/actions）
     * @param actionName       动作名，如 forward / setSpeed / chat
     * @param paramValue       用户输入值（滑条速度、颜色、表情等）；空则不拼
     * @param connectTimeoutMs / readTimeoutMs 可覆盖（chat 需长读超时）
     * @param body             POST body；action.bodyKind=text 时发送
     */
    fun executeLan(
        baseUrl: String,
        driver: DriverDescriptor,
        actionName: String,
        paramValue: String? = null,
        connectTimeoutMs: Int = 3500,
        readTimeoutMs: Int = 3500,
        body: String? = null
    ): Result {
        val action = driver.actions[actionName]
            ?: return Result.HttpError(0, "未知动作 $actionName")
        val url = baseUrl + buildPath(action, paramValue)
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = action.method.uppercase()
                if (action.method.equals("POST", ignoreCase = true)) {
                    doOutput = true
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                }
            }
            if (action.method.equals("POST", ignoreCase = true)) {
                val data = (body ?: "").toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(data.size)
                connection.outputStream.use { it.write(data) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val reply = stream?.bufferedReader()?.use { it.readText() }?.trim().orEmpty()
            connection.disconnect()
            if (code in 200..299) Result.Success(reply)
            else Result.HttpError(code, reply.ifEmpty { "请求失败（$code）" })
        } catch (_: SocketTimeoutException) {
            Result.Timeout
        } catch (_: IOException) {
            Result.NetworkError
        }
    }

    private fun buildPath(action: DriverDescriptor.Action, paramValue: String?): String {
        if (paramValue == null) return action.path
        return when (action.paramPlacement) {
            "path_suffix" -> action.path + paramValue
            else -> action.path + "?" + action.param + "=" + paramValue
        }
    }
}
