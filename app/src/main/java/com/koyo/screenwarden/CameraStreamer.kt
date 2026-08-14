package com.koyo.screenwarden

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ESP32-CAM MJPEG 串流解析器。
 * 用法：start(url, onFrame, onError) / stop()
 */
object CameraStreamer {

    private const val TAG = "CameraStreamer"
    private var job: Job? = null
    private var connection: HttpURLConnection? = null

    fun start(baseUrl: String, onFrame: (Bitmap) -> Unit, onError: (String) -> Unit) {
        stop()

        val streamUrl = "${baseUrl.trimEnd('/')}/stream"
        Log.i(TAG, "Starting stream: $streamUrl")

        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(streamUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 10000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "tiyo/1.0")
                }
                connection!!.connect()

                val contentType = connection!!.contentType ?: throw Exception("No Content-Type")
                val boundary = extractBoundary(contentType)
                    ?: throw Exception("Cannot parse boundary from: $contentType")
                Log.i(TAG, "Boundary: $boundary")

                val dis = DataInputStream(connection!!.inputStream)
                val boundaryMarker = "--$boundary"
                val endMarker = "--$boundary--"

                while (isActive) {
                    // 跳到下一帧的 boundary
                    var line: String?
                    while (true) {
                        line = dis.readLine()
                        if (line == null) return@launch           // EOF
                        if (line == endMarker) return@launch      // 流结束
                        if (line == boundaryMarker) break         // 找到帧起始
                    }

                    // 读 MIME headers
                    var contentLength = -1
                    while (true) {
                        line = dis.readLine() ?: return@launch
                        if (line!!.isEmpty()) break               // 空行 = headers 结束
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
                        }
                    }

                    if (contentLength <= 0) continue              // 跳过错帧

                    // 读 JPEG 二进制数据
                    val jpeg = ByteArray(contentLength)
                    dis.readFully(jpeg, 0, contentLength)

                    // 解码
                    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    if (bitmap != null) {
                        withContext(Dispatchers.Main) { onFrame(bitmap) }
                    }
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Stream cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Stream error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onError("连接失败: ${e.message?.take(40) ?: "未知错误"}")
                }
            } finally {
                try { connection?.disconnect() } catch (_: Exception) {}
                connection = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try { connection?.disconnect() } catch (_: Exception) {}
        connection = null
        Log.i(TAG, "Stream stopped")
    }

    private fun extractBoundary(contentType: String): String? {
        val idx = contentType.indexOf("boundary=")
        if (idx < 0) return null
        return contentType.substring(idx + 9).trim()
    }
}
