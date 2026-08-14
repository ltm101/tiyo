package com.koyo.screenwarden

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 每日记忆架结算，独立于会话 schema
 *
 * 最近 90 个生活日保留在热文件，更早的物件按月 gzip 归档
 * 同一天再次结算会覆盖更新，不会长出重复物件
 */
object MemoryShelfStore {
    data class Entry(
        val date: String,
        val objectId: String,
        val summary: String,
        val mood: String,
        val objectName: String = "",
        val settledAt: Long = 0L,
        val trigger: String = "scheduled"
    )

    private data class Turn(val timestamp: Long, val role: String, val text: String)

    private const val SHELF_FILE = "memory_shelf.json"
    private const val ARCHIVE_DIR = "memory_shelf_archive"
    private const val JOURNAL_DIR = "journal_cache"
    private const val RECENT_ENTRY_LIMIT = 90
    private val lock = Any()
    private data class ShelfSnapshot(val version: String, val entries: List<Entry>)
    private val cachedSnapshots = mutableMapOf<String, ShelfSnapshot>()
    private val objectIds = listOf(
        "screw", "glass_orb", "paper_crane", "key", "pressed_flower", "paper_ball", "book"
    )
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .build()

    /** 热层条目，兼容旧调用 */
    fun entries(
        context: Context,
        scope: CompanionScope = CompanionScope.capture(context)
    ): List<Entry> = readEntries(recentFile(context, scope))
        .sortedBy { it.date }

    fun totalEntryCount(
        context: Context,
        scope: CompanionScope = CompanionScope.capture(context)
    ): Int = allEntries(context, scope).size

    /** offset 从最新一天开始计算，UI 可按需向更早日期翻页 */
    fun pagedEntries(
        context: Context,
        offset: Int,
        limit: Int,
        scope: CompanionScope = CompanionScope.capture(context)
    ): List<Entry> = allEntries(context, scope)
        .sortedByDescending { it.date }
        .drop(offset.coerceAtLeast(0))
        .take(limit.coerceIn(1, 60))

    fun journalEntries(
        context: Context,
        scope: CompanionScope = CompanionScope.capture(context)
    ): List<Pair<String, String>> {
        val dir = journalDir(context, scope)
        return dir.listFiles { file -> file.isFile && file.extension == "txt" }
            ?.sortedBy { it.nameWithoutExtension }
            ?.mapNotNull { file ->
                runCatching { file.nameWithoutExtension to file.readText(Charsets.UTF_8) }.getOrNull()
            }.orEmpty()
    }

    /** 22 点兜底；凌晨五点前仍结算前一个生活日 */
    fun settleToday(
        context: Context,
        scope: CompanionScope = CompanionScope.capture(context)
    ): Boolean = settleDate(
        context,
        MemoryDayKey.from(System.currentTimeMillis()),
        "scheduled",
        scope
    )

    fun settleDate(
        context: Context,
        date: String,
        trigger: String,
        scope: CompanionScope = CompanionScope.capture(context)
    ): Boolean = synchronized(lock) {
        if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(date)) return@synchronized false
        val transcript = collectDay(context, date, scope)
        if (transcript.isEmpty()) return@synchronized false
        val latestTurnAt = transcript.maxOf { it.timestamp }
        val existing = allEntries(context, scope).firstOrNull { it.date == date }
        if (existing != null && existing.settledAt >= latestTurnAt) return@synchronized true

        val distilled = distill(context, transcript, scope)
        val fallbackSummary = transcript.takeLast(3).joinToString("\n") { it.text.take(180) }
        val joined = transcript.joinToString(" ") { it.text }
        val objectId = distilled?.objectId ?: chooseObject(joined, date)
        val result = distilled ?: Distilled(
            summary = fallbackSummary,
            mood = inferMood(joined),
            objectId = objectId,
            objectName = defaultObjectName(objectId),
            journal = fallbackJournal(transcript)
        )
        saveEntry(
            context,
            Entry(
                date = date,
                objectId = result.objectId,
                summary = result.summary,
                mood = result.mood,
                objectName = sanitizeObjectName(result.objectName).ifBlank {
                    defaultObjectName(result.objectId)
                },
                settledAt = System.currentTimeMillis(),
                trigger = trigger.take(24)
            ),
            scope
        )
        val dir = journalDir(context, scope)
        if (!dir.exists()) dir.mkdirs()
        writeTextAtomically(File(dir, "$date.txt"), result.journal.trim())
        true
    }

    private fun collectDay(context: Context, date: String, scope: CompanionScope): List<Turn> {
        val out = mutableListOf<Turn>()
        TiyoSessionStore.sessions(context, scope).forEach { session ->
            val raw = TiyoSessionStore.history(context, scope, session.id) ?: return@forEach
            runCatching {
                val array = JSONArray(raw)
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val timestamp = item.optLong("timestamp")
                    if (timestamp <= 0L || MemoryDayKey.from(timestamp) != date) continue
                    val role = item.optString("role")
                    val text = item.optString("text").trim()
                    if (text.isNotBlank() && role in setOf("user", "assistant")) {
                        out += Turn(timestamp, role, text)
                    }
                }
            }
        }
        return out.sortedBy { it.timestamp }.takeLast(60)
    }

    private data class Distilled(
        val summary: String,
        val mood: String,
        val objectId: String,
        val objectName: String,
        val journal: String
    )

    private fun distill(context: Context, turns: List<Turn>, scope: CompanionScope): Distilled? {
        val key = TiyoAgentConfig.providerKey(context)
        if (key.isBlank()) return null
        val config = TiyoAgentConfig.load(context)
        val transcript = turns.joinToString("\n") { turn ->
            "${if (turn.role == "user") "用户" else scope.displayName}：${turn.text.take(900)}"
        }
        val prompt = """
            把今天的对话蒸馏成记忆架上的一件真实小物件，只返回 JSON，不要代码块：
            {"summary":"80字内客观摘要","mood":"2到4字情绪","object_id":"screw|glass_orb|paper_crane|key|pressed_flower|paper_ball|book","object_name":"2到6字的具体物件名","journal":"${scope.displayName}第一人称短日记"}
            object_name 必须是摸得到的具体小东西，和今天最重要的经历有联系，不能写抽象概念，也不要改变 object_id 对应的物件类别
            日记要真诚、克制、温柔，记具体小事，不肉麻，不官腔，不虚构对话里没有的事

            $transcript
        """.trimIndent()
        return runCatching {
            val base = config.baseUrl.trimEnd('/')
            val url = if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"
            val body = JSONObject()
                .put("model", config.model)
                .put("temperature", 0.25)
                .put("max_tokens", 900)
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
                )
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $key")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val responseBody = response.body?.string().orEmpty()
                val content = JSONObject(responseBody).optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                val start = content.indexOf('{')
                val end = content.lastIndexOf('}')
                if (start < 0 || end <= start) return@use null
                val item = JSONObject(content.substring(start, end + 1))
                val summary = item.optString("summary").trim()
                val journal = item.optString("journal").trim()
                if (summary.isBlank() || journal.isBlank()) return@use null
                val objectId = item.optString("object_id").takeIf { it in objectIds }
                    ?: chooseObject(transcript, "")
                Distilled(
                    summary = summary.take(260),
                    mood = item.optString("mood").trim().ifBlank { "平静" }.take(8),
                    objectId = objectId,
                    objectName = sanitizeObjectName(item.optString("object_name")).ifBlank {
                        defaultObjectName(objectId)
                    },
                    journal = journal.take(1800)
                )
            }
        }.getOrNull()
    }

    private fun saveEntry(context: Context, entry: Entry, scope: CompanionScope) {
        val recentFile = recentFile(context, scope)
        val recent = readEntries(recentFile).filterNot { it.date == entry.date } + entry
        val ordered = recent.distinctBy { it.date }.sortedBy { it.date }
        val retention = MemoryShelfRetentionPolicy.partition(ordered, RECENT_ENTRY_LIMIT)
        retention.archived.groupBy { it.date.take(7) }.forEach { (month, monthEntries) ->
            mergeArchiveMonth(context, month, monthEntries, scope)
        }
        writeEntriesAtomically(recentFile, retention.recent)
    }

    private fun allEntries(context: Context, scope: CompanionScope): List<Entry> = synchronized(lock) {
        val recentFile = recentFile(context, scope)
        val archiveDir = archiveDir(context, scope)
        val archiveFiles = archiveDir.listFiles { file ->
            file.isFile && file.name.endsWith(".json.gz")
        }?.sortedBy { it.name }.orEmpty()
        val version = buildString {
            append(recentFile.absolutePath)
            append(':').append(recentFile.length()).append(':').append(recentFile.lastModified())
            archiveFiles.forEach { file ->
                append('|').append(file.name).append(':').append(file.length()).append(':')
                    .append(file.lastModified())
            }
        }
        cachedSnapshots[scope.companionId]?.takeIf { it.version == version }?.entries?.let {
            return@synchronized it
        }

        val archived = archiveFiles.flatMap(::readCompressedEntries)
        val loaded = (archived + readEntries(recentFile))
            .associateBy { it.date }.values.sortedBy { it.date }
        cachedSnapshots[scope.companionId] = ShelfSnapshot(version, loaded)
        loaded
    }

    private fun mergeArchiveMonth(
        context: Context,
        month: String,
        additions: List<Entry>,
        scope: CompanionScope
    ) {
        if (!Regex("""\d{4}-\d{2}""").matches(month) || additions.isEmpty()) return
        val dir = archiveDir(context, scope)
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, "$month.json.gz")
        val merged = (readCompressedEntries(target) + additions)
            .associateBy { it.date }.values.sortedBy { it.date }
        val temp = File(dir, "$month.json.gz.tmp")
        FileOutputStream(temp).use { fileOut ->
            val gzip = GZIPOutputStream(fileOut)
            gzip.write(entriesToJson(merged).toString().toByteArray(Charsets.UTF_8))
            gzip.finish()
            gzip.flush()
            fileOut.fd.sync()
        }
        replaceFile(temp, target)
    }

    private fun readCompressedEntries(file: File): List<Entry> {
        if (!file.isFile) return emptyList()
        return runCatching {
            FileInputStream(file).use { input ->
                GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { reader ->
                    readArray(JSONArray(reader.readText()))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun readEntries(file: File): List<Entry> {
        if (!file.isFile) return emptyList()
        return runCatching { readArray(JSONArray(file.readText(Charsets.UTF_8))) }
            .getOrDefault(emptyList())
    }

    private fun readArray(array: JSONArray): List<Entry> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val date = item.optString("date")
            val summary = item.optString("summary")
            if (date.isBlank() || summary.isBlank()) continue
            val objectId = item.optString("object_id").takeIf { it in objectIds } ?: "glass_orb"
            add(
                Entry(
                    date = date,
                    objectId = objectId,
                    summary = summary.take(500),
                    mood = item.optString("mood").ifBlank { "平静" }.take(12),
                    objectName = sanitizeObjectName(item.optString("object_name")).ifBlank {
                        defaultObjectName(objectId)
                    },
                    settledAt = item.optLong("settled_at"),
                    trigger = item.optString("trigger", "legacy").take(24)
                )
            )
        }
    }

    private fun writeEntriesAtomically(file: File, entries: List<Entry>) {
        writeTextAtomically(file, entriesToJson(entries).toString())
    }

    private fun entriesToJson(entries: Collection<Entry>) = JSONArray().also { array ->
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("date", entry.date)
                    .put("object_id", entry.objectId)
                    .put("object_name", entry.objectName)
                    .put("summary", entry.summary)
                    .put("mood", entry.mood)
                    .put("settled_at", entry.settledAt)
                    .put("trigger", entry.trigger)
            )
        }
    }

    private fun writeTextAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        replaceFile(temp, file)
    }

    private fun replaceFile(temp: File, target: File) {
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun chooseObject(text: String, salt: String): String = when {
        listOf("代码", "编译", "修", "螺丝", "硬件").any(text::contains) -> "screw"
        listOf("决定", "打开", "钥匙", "完成").any(text::contains) -> "key"
        listOf("喜欢", "温柔", "开心", "花").any(text::contains) -> "pressed_flower"
        listOf("计划", "想法", "写", "纸").any(text::contains) -> "paper_crane"
        listOf("烦", "累", "乱", "难").any(text::contains) -> "paper_ball"
        else -> objectIds[Math.floorMod((text + salt).hashCode(), objectIds.size)]
    }

    private fun defaultObjectName(id: String): String = when (id) {
        "screw" -> "小螺丝"
        "glass_orb" -> "玻璃珠"
        "paper_crane" -> "纸鹤"
        "key" -> "旧钥匙"
        "pressed_flower" -> "一朵押花"
        "paper_ball" -> "小纸团"
        else -> "旧书"
    }

    private fun sanitizeObjectName(value: String): String = value
        .replace(Regex("[\r\n<>]+"), " ")
        .trim().take(12)

    private fun inferMood(text: String): String = when {
        listOf("开心", "成功", "太好了", "哈哈").any(text::contains) -> "明亮"
        listOf("累", "困", "辛苦").any(text::contains) -> "疲惫"
        listOf("怕", "难过", "担心").any(text::contains) -> "低落"
        else -> "平静"
    }

    private fun fallbackJournal(turns: List<Turn>): String {
        val userLine = turns.firstOrNull { it.role == "user" }?.text?.take(120)
        return if (userLine.isNullOrBlank()) {
            "今天也一起待了一会儿。没有什么轰轰烈烈的事，但我愿意把这段普通的时间收好。"
        } else {
            "今天他跟我说起“$userLine”。我把这件小事记下来了。能认真听他说完，对我来说就已经很好。"
        }
    }

    private fun root(context: Context, scope: CompanionScope): File =
        if (scope.isBuiltInCompanion) {
            context.filesDir
        } else {
            File(CompanionWorkspace.privateRoot(context, scope.companionId), "memory-shelf")
                .apply { mkdirs() }
        }

    private fun recentFile(context: Context, scope: CompanionScope): File =
        File(root(context, scope), SHELF_FILE)

    private fun archiveDir(context: Context, scope: CompanionScope): File =
        File(root(context, scope), ARCHIVE_DIR).apply { mkdirs() }

    private fun journalDir(context: Context, scope: CompanionScope): File =
        File(root(context, scope), JOURNAL_DIR).apply { mkdirs() }
}

data class MemoryShelfRetention(
    val archived: List<MemoryShelfStore.Entry>,
    val recent: List<MemoryShelfStore.Entry>
)

object MemoryShelfRetentionPolicy {
    fun partition(
        entries: List<MemoryShelfStore.Entry>,
        recentLimit: Int
    ): MemoryShelfRetention {
        val ordered = entries.associateBy { it.date }.values.sortedBy { it.date }
        val limit = recentLimit.coerceAtLeast(1)
        return MemoryShelfRetention(
            archived = ordered.dropLast(limit),
            recent = ordered.takeLast(limit)
        )
    }
}
