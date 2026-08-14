package com.koyo.screenwarden

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Keeps exactly one recoverable pre-refinement version per custom companion */
object CompanionAssetPackSnapshot {
    private const val DIRECTORY = "rollback"
    private const val METADATA = "snapshot.json"

    data class Info(
        val role: CompanionAssetRole,
        val createdAt: Long
    )

    fun latest(context: Context, companionId: String): Info? {
        val json = readMetadata(context, companionId) ?: return null
        val role = CompanionAssetRole.entries.firstOrNull {
            it.key == json.optString("role")
        } ?: return null
        return Info(role, json.optLong("created_at"))
    }

    fun capture(context: Context, companionId: String, role: CompanionAssetRole): Boolean {
        if (!canCapture(role)) return false
        val profile = CompanionProfileStore.find(context, companionId) ?: return false
        if (profile.isBuiltInCompanion) return false
        val entry = CompanionAssetPack.entry(context, companionId, role) ?: return false
        val packRoot = CompanionWorkspace.assetPackRoot(context, companionId).canonicalFile
        val rollback = File(packRoot, DIRECTORY)
        if (rollback.exists() && !rollback.deleteRecursively()) return false
        if (!rollback.mkdirs() && !rollback.isDirectory) return false

        val sheetName = "${role.key}_sheet.png"
        val sourceNames = (listOf(entry.fileName) + entry.frameFileNames + sheetName)
            .distinct()
            .filter { name -> safeFile(packRoot, name)?.isFile == true }
        if (sourceNames.isEmpty()) return false
        return runCatching {
            sourceNames.forEach { name ->
                val source = safeFile(packRoot, name) ?: error("Invalid asset name")
                source.copyTo(File(rollback, name), overwrite = true)
            }
            val metadata = JSONObject()
                .put("schema_version", 1)
                .put("companion_id", profile.id)
                .put("role", role.key)
                .put("created_at", System.currentTimeMillis())
                .put("files", JSONArray(sourceNames))
                .put("entry", encodeEntry(entry))
            atomicWrite(File(rollback, METADATA), metadata.toString(2))
            true
        }.getOrElse {
            rollback.deleteRecursively()
            false
        }
    }

    fun restoreLatest(context: Context, companionId: String): CompanionAssetRole? {
        val profile = CompanionProfileStore.find(context, companionId) ?: return null
        if (profile.isBuiltInCompanion) return null
        val metadata = readMetadata(context, companionId) ?: return null
        if (CompanionProfileRules.normalizeId(metadata.optString("companion_id")) != profile.id) {
            return null
        }
        val role = CompanionAssetRole.entries.firstOrNull {
            it.key == metadata.optString("role")
        } ?: return null
        val oldEntry = decodeEntry(metadata.optJSONObject("entry") ?: return null) ?: return null
        if (oldEntry.role != role) return null

        val packRoot = CompanionWorkspace.assetPackRoot(context, companionId).canonicalFile
        val rollback = File(packRoot, DIRECTORY).canonicalFile
        val fileNames = buildList {
            val files = metadata.optJSONArray("files") ?: JSONArray()
            for (index in 0 until files.length()) {
                files.optString(index).takeIf(::isSafeName)?.let(::add)
            }
        }.distinct()
        if (fileNames.isEmpty() || fileNames.any { !File(rollback, it).isFile }) return null
        val primaryBackup = File(rollback, oldEntry.fileName)
        if (!primaryBackup.isFile ||
            CompanionReferenceImporter.sha256(primaryBackup) != oldEntry.sha256
        ) return null

        return runCatching {
            val current = CompanionAssetPack.entry(context, companionId, role)
            val currentNames = (listOfNotNull(current?.fileName) +
                current?.frameFileNames.orEmpty() + "${role.key}_sheet.png").distinct()
            currentNames.forEach { name -> safeFile(packRoot, name)?.delete() }
            fileNames.forEach { name ->
                File(rollback, name).copyTo(
                    safeFile(packRoot, name) ?: error("Invalid restore path"),
                    overwrite = true
                )
            }
            val restoredEntries = CompanionAssetPack.entries(context, companionId)
                .filterNot { it.role == role } + oldEntry
            CompanionAssetPack.writeManifest(context, companionId, restoredEntries)
            rollback.deleteRecursively()
            role
        }.getOrNull()
    }

    fun clear(context: Context, companionId: String): Boolean {
        val root = CompanionWorkspace.assetPackRoot(context, companionId).canonicalFile
        val rollback = File(root, DIRECTORY).canonicalFile
        if (rollback.parentFile != root) return false
        return !rollback.exists() || rollback.deleteRecursively()
    }

    private fun readMetadata(context: Context, companionId: String): JSONObject? {
        val root = CompanionWorkspace.assetPackRoot(context, companionId).canonicalFile
        val file = File(File(root, DIRECTORY), METADATA)
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    internal fun canCapture(role: CompanionAssetRole): Boolean =
        role != CompanionAssetRole.IDENTITY_ANCHOR

    internal fun encodeEntry(entry: CompanionAssetEntry): JSONObject = JSONObject()
        .put("role", entry.role.key)
        .put("file_name", entry.fileName)
        .put("sha256", entry.sha256)
        .put("width", entry.width)
        .put("height", entry.height)
        .put("frame_files", JSONArray(entry.frameFileNames))
        .put("quality_score", entry.qualityScore)

    internal fun decodeEntry(json: JSONObject): CompanionAssetEntry? {
        val role = CompanionAssetRole.entries.firstOrNull {
            it.key == json.optString("role")
        } ?: return null
        val fileName = json.optString("file_name")
        val sha = json.optString("sha256")
        if (!isSafeName(fileName) || sha.isBlank()) return null
        val frames = buildList {
            val array = json.optJSONArray("frame_files") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optString(index).takeIf(::isSafeName)?.let(::add)
            }
        }
        return CompanionAssetEntry(
            role = role,
            fileName = fileName,
            sha256 = sha,
            width = json.optInt("width"),
            height = json.optInt("height"),
            frameFileNames = frames,
            qualityScore = json.optInt("quality_score", 100).coerceIn(0, 100)
        )
    }

    private fun safeFile(parent: File, name: String): File? {
        if (!isSafeName(name)) return null
        val file = File(parent, name).canonicalFile
        return file.takeIf { it.parentFile == parent }
    }

    private fun isSafeName(value: String): Boolean =
        value.isNotBlank() && File(value).name == value && value !in setOf(".", "..")

    private fun atomicWrite(target: File, text: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            temp.delete()
        }
    }
}
