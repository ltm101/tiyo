package com.koyo.screenwarden

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 传给 LLM 的一条消息。 */
data class ChatMsg(val role: String, val content: String)

/** 学习引擎接口：v1 用 DeepSeek 直调，后续可换 Agent 引擎。 */
interface StudyEngine {
    fun chat(
        messages: List<ChatMsg>,
        jsonMode: Boolean,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    )
}

/** 直调 OpenAI 兼容端点（默认 DeepSeek），非流式，后台线程 + 主线程回调。 */
class DeepSeekStudyEngine(private val context: Context) : StudyEngine {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun chat(
        messages: List<ChatMsg>,
        jsonMode: Boolean,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val config = TiyoAgentConfig.load(context)
                val apiKey = TiyoAgentConfig.providerKey(context)
                if (config.baseUrl.isBlank() || apiKey.isBlank()) {
                    throw IllegalStateException("还没有配置 API Key")
                }
                val body = JSONObject()
                    .put("model", config.model)
                    .put("messages", JSONArray().apply {
                        messages.forEach { m ->
                            put(JSONObject().put("role", m.role).put("content", m.content))
                        }
                    })
                    .put("max_tokens", 2048)
                    .put("temperature", 0.6)
                if (jsonMode) {
                    body.put("response_format", JSONObject().put("type", "json_object"))
                }
                val payload = body.toString().toByteArray(Charsets.UTF_8)

                val connection = (
                    URL("${config.baseUrl.trimEnd('/')}/chat/completions").openConnection()
                        as HttpURLConnection
                ).apply {
                    connectTimeout = 10000
                    readTimeout = 120000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                connection.disconnect()

                if (code !in 200..299) {
                    throw IllegalStateException(response.ifBlank { "请求失败 $code" })
                }
                val content = JSONObject(response)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    .orEmpty()
                if (content.isBlank()) throw IllegalStateException("模型返回为空")
                mainHandler.post { onResult(content.trim()) }
            } catch (error: Exception) {
                mainHandler.post { onError(error.message ?: "未知错误") }
            }
        }.start()
    }
}

/** 轻量知识库检索：按问题关键词对 study 目录文件打分，拼上下文。 */
object KbRetriever {

    private const val MAX_CONTEXT_CHARS = 12000
    private const val MAX_FILES = 4
    private const val PER_FILE_CHARS = 4000
    private const val READ_AHEAD_BYTES = 64 * 1024 // 只读文件头部片段，避免把大文件整体读进内存
    private val STOPWORDS = setOf(
        "什么", "怎么", "如何", "为什么", "这个", "那个", "一个", "一下", "你们", "我们", "知道",
        "可以", "没有", "不是", "就是", "请问", "一下", "帮助", "说说", "讲讲", "一下"
    )

    /** 无法解析为文字的二进制类型（含 PDF、Office、图片、压缩包等），给出可见提示而不是静默跳过。 */
    private val unreadableExtensions = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svgz",
        "mp3", "mp4", "avi", "mkv", "mov", "flac", "aac", "wav",
        "apk", "zip", "rar", "7z", "tar", "gz", "jar",
        "exe", "dll", "so", "dex", "bin", "dat", "db", "sqlite"
    )

    fun listStudyFiles(context: Context): List<File> {
        val dir = TiyoWorkspace.study(context)
        return dir.listFiles()
            ?.filter { it.isFile && it.length() > 0 }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    /** 扩展名是否是可读文本（txt/md 等）；false = 二进制（PDF/Office/图片…），需先转格式。 */
    fun isReadableText(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext !in unreadableExtensions
    }

    fun buildContext(context: Context, question: String): String {
        val files = listStudyFiles(context)
        if (files.isEmpty()) return ""
        if (files.size <= MAX_FILES) {
            return files.joinToString("\n\n") { readFileSnippet(it) }
                .take(MAX_CONTEXT_CHARS)
        }
        val keywords = extractKeywords(question)
        val scored = files.map { file ->
            val content = readFileSnippet(file)
            var score = 0
            if (keywords.isEmpty()) {
                score = content.length.coerceAtMost(PER_FILE_CHARS)
            } else {
                val lowerName = file.name.lowercase()
                val lowerContent = content.lowercase()
                keywords.forEach { k ->
                    if (lowerName.contains(k, ignoreCase = true)) score += 50
                    score += countOccurrences(lowerContent, k) * 3
                }
            }
            file to (content to score)
        }.sortedByDescending { it.second.second }
            .take(MAX_FILES)

        val contextText = scored.joinToString("\n\n") { "【${it.first.name}】\n${it.second.first}" }
        return if (contextText.length > MAX_CONTEXT_CHARS) {
            contextText.take(MAX_CONTEXT_CHARS) + "\n（资料较长已截断）"
        } else {
            contextText
        }
    }

    private fun readFileSnippet(file: File): String {
        val ext = file.extension.lowercase()
        if (ext in unreadableExtensions) {
            return "【${file.name}】是 ${ext.uppercase()} 文件，暂不支持解析为文字，建议转成 txt 或 markdown 再导入"
        }
        val text = runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(READ_AHEAD_BYTES)
                val count = input.read(head)
                String(head, 0, count.coerceAtLeast(0), Charsets.UTF_8)
            }
        }.getOrElse { e ->
            "【${file.name}】读取失败：${e.message ?: "未知原因"}"
        }
        return text.trim().take(PER_FILE_CHARS)
    }

    /** 提取问题关键词：连续汉字二元组 + 英文词。 */
    private fun extractKeywords(question: String): List<String> {
        val normalized = question.lowercase().trim()
        val words = mutableListOf<String>()
        // 英文/数字 token
        Regex("[a-z0-9_]{2,}").findAll(normalized).forEach { words.add(it.value) }
        // 中文：2 字窗口
        val hanzi = normalized.filter { it in '一'..'鿿' }
        for (i in 0..hanzi.length - 2) {
            val gram = hanzi.substring(i, i + 2)
            if (gram !in STOPWORDS) words.add(gram)
        }
        return words.distinct().take(30)
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = 0
        while (true) {
            val found = haystack.indexOf(needle, index)
            if (found < 0) break
            count++
            index = found + needle.length
        }
        return count
    }
}
