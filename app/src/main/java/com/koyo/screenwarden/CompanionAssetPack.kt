package com.koyo.screenwarden

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CompanionAssetEntry(
    val role: CompanionAssetRole,
    val fileName: String,
    val sha256: String,
    val width: Int,
    val height: Int,
    val frameFileNames: List<String> = emptyList(),
    val qualityScore: Int = 100
)

object CompanionAssetPack {
    private const val MANIFEST = "manifest.json"

    fun activeFile(context: Context, role: CompanionAssetRole): File? {
        val profile = CompanionProfileStore.active(context)
        if (profile.isBuiltInCompanion || profile.status != CompanionStatus.READY) return null
        return file(context, profile.id, role)
    }

    fun file(context: Context, companionId: String, role: CompanionAssetRole): File? {
        val entry = entry(context, companionId, role) ?: return null
        return File(CompanionWorkspace.assetPackRoot(context, companionId), entry.fileName)
            .takeIf(File::isFile)
    }

    fun entry(context: Context, companionId: String, role: CompanionAssetRole): CompanionAssetEntry? =
        entries(context, companionId).firstOrNull { it.role == role }

    fun actionFile(context: Context, action: String): File? {
        return actionFiles(context, action).firstOrNull()
    }

    fun actionFiles(context: Context, action: String): List<File> {
        val role = roleForAction(action) ?: return emptyList()
        val profile = CompanionProfileStore.active(context)
        if (profile.isBuiltInCompanion || profile.status != CompanionStatus.READY) return emptyList()
        val entry = entries(context, profile.id).firstOrNull { it.role == role } ?: return emptyList()
        val root = CompanionWorkspace.assetPackRoot(context, profile.id)
        val names = entry.frameFileNames.ifEmpty { listOf(entry.fileName) }
        return names.distinct().map { File(root, it) }.filter(File::isFile)
    }

    internal fun roleForAction(action: String): CompanionAssetRole? = when (action) {
        "invite_chat", "wave" -> CompanionAssetRole.TODAY_WAVE
        "idle", "blink_full", "stretch", "hug_knees", "push_down", "walk", "run",
        "yawn", "tilt", "arms_crossed", "sleep" -> CompanionAssetRole.TODAY_IDLE
        "prone", "blink_prone", "chin", "read", "write", "typing", "peek_up" ->
            CompanionAssetRole.CHAT_PRONE
        "bust", "talk", "blink", "happy", "surprise", "shy", "look_left", "look_right",
        "boba", "rub_eyes", "edge_peek", "heart" -> CompanionAssetRole.CHAT_PORTRAIT
        else -> CompanionAssetRole.CHAT_PORTRAIT
    }

    fun sceneFile(context: Context, requestedAsset: String): File? {
        val role = when {
            requestedAsset.contains("desk") -> CompanionAssetRole.DESK_IDLE
            requestedAsset.contains("shelf") -> CompanionAssetRole.SHELF_IDLE
            requestedAsset.contains("room_speaking") -> CompanionAssetRole.ROOM_SPEAKING
            requestedAsset.contains("room_") -> CompanionAssetRole.ROOM_IDLE
            else -> return null
        }
        return activeFile(context, role)
    }

    fun entries(context: Context, companionId: String): List<CompanionAssetEntry> {
        val manifest = File(CompanionWorkspace.assetPackRoot(context, companionId), MANIFEST)
        if (!manifest.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(manifest.readText(Charsets.UTF_8))
            val array = root.optJSONArray("assets") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val role = CompanionAssetRole.entries.firstOrNull {
                        it.key == item.optString("role")
                    } ?: continue
                    val fileName = item.optString("file_name")
                    val sha = item.optString("sha256")
                    if (!isSafeFileName(fileName) || sha.isBlank()) continue
                    val frameFiles = buildList {
                        val frames = item.optJSONArray("frame_files") ?: JSONArray()
                        for (frameIndex in 0 until frames.length()) {
                            frames.optString(frameIndex).takeIf(::isSafeFileName)?.let(::add)
                        }
                    }
                    add(
                        CompanionAssetEntry(
                            role = role,
                            fileName = fileName,
                            sha256 = sha,
                            width = item.optInt("width"),
                            height = item.optInt("height"),
                            frameFileNames = frameFiles,
                            qualityScore = item.optInt("quality_score", 100).coerceIn(0, 100)
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    fun writeManifest(
        context: Context,
        companionId: String,
        entries: List<CompanionAssetEntry>
    ) {
        val array = JSONArray()
        entries.forEach { entry ->
            val frames = JSONArray()
            entry.frameFileNames.forEach(frames::put)
            array.put(
                JSONObject()
                    .put("role", entry.role.key)
                    .put("file_name", entry.fileName)
                    .put("sha256", entry.sha256)
                    .put("width", entry.width)
                    .put("height", entry.height)
                    .put("frame_files", frames)
                    .put("quality_score", entry.qualityScore.coerceIn(0, 100))
            )
        }
        val text = JSONObject()
            .put("schema_version", 1)
            .put("companion_id", CompanionProfileRules.normalizeId(companionId))
            .put("assets", array)
            .toString(2)
        val target = File(CompanionWorkspace.assetPackRoot(context, companionId), MANIFEST)
        val temp = File(target.parentFile, "$MANIFEST.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            temp.delete()
        }
    }

    fun hasRequiredAssets(context: Context, companionId: String): Boolean {
        return CompanionGenerationPlan.phaseOneAssets
            .filter(CompanionAssetSpec::requiredForActivation)
            .all { spec -> isComplete(context, companionId, spec) }
    }

    fun isComplete(context: Context, companionId: String, spec: CompanionAssetSpec): Boolean {
        val entry = entries(context, companionId).firstOrNull { it.role == spec.role } ?: return false
        val names = entry.frameFileNames.ifEmpty { listOf(entry.fileName) }
        val expectedFrames = spec.frameGrid?.frameCount
        if (expectedFrames != null && entry.frameFileNames.size != expectedFrames) return false
        val root = CompanionWorkspace.assetPackRoot(context, companionId)
        return entry.qualityScore >= 60 && names.isNotEmpty() &&
            names.all { name -> File(root, name).isFile }
    }

    private fun isSafeFileName(value: String): Boolean =
        value.isNotBlank() && File(value).name == value && value !in setOf(".", "..")
}
