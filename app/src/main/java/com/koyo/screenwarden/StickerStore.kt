package com.koyo.screenwarden

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File

/**
 * Tiyo表情包：
 * - 内置：assets/stickers 下的像素风表情包，文件名即语义（如「晚安.png」）
 * - 用户库：filesDir/stickers，用户自行导入，优先于内置同名
 * - 标签：每个表情包可打多个标签（用途 + 场景），存 SharedPreferences，喂给 AI 选图更准
 *
 * 供聊天 AI 按情绪选一张配进消息，也供主动消息带表情包。
 */
object StickerStore {

    private const val PREFS = "tiyo_sticker_tags"
    private const val KEY_TAGS = "tags"          // JSONObject: 语义名 -> [标签]
    private const val KEY_FREQ = "age_frequency" // 用户是否改过频率（预留，默认按年龄段推断）

    // ---- 枚举 ----

    /** 用户目录（可写）。优先于 assets 内置库。 */
    fun userDir(
        context: Context,
        scope: CompanionScope = CompanionScope.capture(context)
    ): File = if (scope.isBuiltInCompanion) {
        File(context.filesDir, "stickers").apply { mkdirs() }
    } else {
        File(CompanionWorkspace.privateRoot(context, scope.companionId), "stickers")
            .apply { mkdirs() }
    }

    private fun assetsAll(context: Context, scope: CompanionScope): List<String> =
        if (!scope.isBuiltInCompanion) emptyList() else
        try {
            context.assets.list("stickers")
                ?.filter { it.endsWith(".png") }
                ?.map { it.removeSuffix(".png") }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    private fun userAll(context: Context, scope: CompanionScope): List<String> =
        userDir(context, scope).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".png") }
            ?.map { it.name.removeSuffix(".png") }
            ?: emptyList()

    /** 全部表情包名（去重，用户优先），按字典序 */
    fun all(context: Context, scope: CompanionScope = CompanionScope.capture(context)): List<String> {
        val set = LinkedHashSet<String>()
        set.addAll(assetsAll(context, scope))
        set.addAll(generated(context, scope).keys)
        set.addAll(userAll(context, scope))
        return set.sorted()
    }

    /** 表情包是否存在于内置或用户库 */
    fun has(
        context: Context,
        name: String,
        scope: CompanionScope = CompanionScope.capture(context)
    ): Boolean {
        if (name.isBlank()) return false
        return all(context, scope).contains(name)
    }

    /** 是否为用户导入的表情包（可删）；内置的返回 false */
    fun isUserSticker(
        context: Context,
        name: String,
        scope: CompanionScope = CompanionScope.capture(context)
    ): Boolean {
        if (name.isBlank()) return false
        return File(userDir(context, scope), "$name.png").isFile
    }

    /** 加载表情包 Bitmap：优先用户库，fallback 内置（透明像素风 PNG），失败返回 null */
    fun loadBitmap(
        context: Context,
        name: String,
        scope: CompanionScope = CompanionScope.capture(context)
    ): Bitmap? {
        if (name.isBlank()) return null
        // 用户库优先
        val userFile = File(userDir(context, scope), "$name.png")
        if (userFile.isFile) {
            return try {
                BitmapFactory.decodeFile(userFile.absolutePath)
            } catch (_: Exception) {
                null
            }
        }
        generated(context, scope)[name]?.let { generatedFile ->
            return runCatching { BitmapFactory.decodeFile(generatedFile.absolutePath) }.getOrNull()
        }
        if (!scope.isBuiltInCompanion) return null
        // 内置 fallback 只属于内置Tiyo，自创角色绝不借用她的表情
        return try {
            context.assets.open("stickers/$name.png").use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---- 标签 ----

    private fun prefs(context: Context, scope: CompanionScope) =
        context.getSharedPreferences(scope.namespaced(PREFS), Context.MODE_PRIVATE)

    /** 某个表情包的标签列表（没打过的返回空） */
    fun tags(
        context: Context,
        name: String,
        scope: CompanionScope = CompanionScope.capture(context)
    ): List<String> {
        if (name.isBlank()) return emptyList()
        val json = prefs(context, scope).getString(KEY_TAGS, null) ?: return emptyList()
        return runCatching {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray(name) ?: return emptyList()
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /** 保存某表情包的标签（空列表=清除） */
    fun saveTags(
        context: Context,
        name: String,
        tags: List<String>,
        scope: CompanionScope = CompanionScope.capture(context)
    ) {
        if (name.isBlank()) return
        val json = prefs(context, scope).getString(KEY_TAGS, null) ?: "{}"
        val obj = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        val clean = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) obj.remove(name)
        else obj.put(name, org.json.JSONArray(clean))
        prefs(context, scope).edit().putString(KEY_TAGS, obj.toString()).apply()
    }

    // ---- 用户库增删 ----

    /** 导入用户表情包：按语义名写入用户目录，并保存标签。返回是否成功。 */
    fun importSticker(
        context: Context,
        bitmap: Bitmap,
        name: String,
        tags: List<String>,
        scope: CompanionScope = CompanionScope.capture(context)
    ): Boolean {
        val safe = name.trim()
        if (safe.isBlank() || safe.contains("/") || safe.contains("\\")) return false
        return try {
            val file = File(userDir(context, scope), "$safe.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            saveTags(context, safe, tags, scope)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 删除用户表情包（内置的不可删）。返回是否删掉。 */
    fun deleteSticker(
        context: Context,
        name: String,
        scope: CompanionScope = CompanionScope.capture(context)
    ): Boolean {
        if (!isUserSticker(context, name, scope)) return false
        return try {
            File(userDir(context, scope), "$name.png").delete()
        } catch (_: Exception) {
            false
        }
    }

    // ---- 给 AI 的清单 ----

    /**
     * 给 AI 的表情包清单：中文名（标签）逗号分隔，供 AI 按情绪和场景选一张。
     * 标签叠加在语义名后面，帮助 AI 判断什么时候用。
     */
    fun promptCatalog(
        context: Context,
        scope: CompanionScope = CompanionScope.capture(context)
    ): String {
        val names = all(context, scope)
        if (names.isEmpty()) return "（暂无表情包）"
        return names.joinToString("、") { name ->
            val t = tags(context, name, scope)
            if (t.isEmpty()) name else "$name(${t.joinToString("/")})"
        }
    }

    /** 按年龄段给出表情包频率建议（给 AI 的指令文本），让不同年龄段发图节奏不同 */
    fun frequencyHint(ageGroup: UserPrefs.AgeGroup): String = when (ageGroup) {
        UserPrefs.AgeGroup.CHILD -> "可以常配表情包，两三句配一张都合适，选活泼可爱的"
        UserPrefs.AgeGroup.YOUTH -> "不是每条都配，只在合适时配一张"
        UserPrefs.AgeGroup.MIDDLE -> "表情包少配，只在情绪真到的时候配一张，宁缺毋滥"
        UserPrefs.AgeGroup.ELDER -> "表情包很少配，只在特别合适时配一张，选一目了然的，以文字为主"
    }

    /** 从 AI 回复提取 {sticker:名字}，返回(去掉标记的正文, 表情包名或null) */
    fun extractSticker(text: String): Pair<String, String?> {
        val m = Regex("\\{sticker:([^{}]+)\\}").find(text) ?: return text to null
        val name = m.groupValues[1].trim()
        val cleaned = text.replace(m.value, "").trim()
        return cleaned to name
    }

    private fun generated(context: Context, scope: CompanionScope): Map<String, File> {
        if (scope.isBuiltInCompanion) return emptyMap()
        val entry = CompanionAssetPack.entry(
            context,
            scope.companionId,
            CompanionAssetRole.STICKER_SHEET
        ) ?: return emptyMap()
        if (entry.frameFileNames.isEmpty()) return emptyMap()
        val labels = listOf("开心", "害羞", "惊讶", "担心", "得意", "困", "鼓励", "调侃", "温柔")
        val root = CompanionWorkspace.assetPackRoot(context, scope.companionId)
        return labels.zip(entry.frameFileNames)
            .mapNotNull { (label, fileName) ->
                File(root, fileName).takeIf(File::isFile)?.let { label to it }
            }
            .toMap(LinkedHashMap())
    }
}
