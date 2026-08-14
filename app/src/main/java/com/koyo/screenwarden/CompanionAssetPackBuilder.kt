package com.koyo.screenwarden

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Builds a resumable visual pack from one user-approved identity anchor. */
object CompanionAssetPackBuilder {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tiyo-companion-pack").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    sealed class Event {
        data class Progress(val completed: Int, val total: Int, val label: String) : Event()
        data class Complete(val companionId: String, val generated: Int) : Event()
        data class Failed(val message: String) : Event()
    }

    fun build(
        context: Context,
        companionId: String,
        forceRoles: Set<CompanionAssetRole> = emptySet(),
        callback: (Event) -> Unit
    ) {
        val appContext = context.applicationContext
        val safeId = CompanionProfileRules.normalizeId(companionId)
        if (!inFlight.add(safeId)) {
            dispatch(callback, Event.Failed("这个角色已经在生成中"))
            return
        }
        val preserveReadyProfile = CompanionProfileStore.find(appContext, companionId)
            ?.status == CompanionStatus.READY
        var snapshotCaptured = false
        executor.execute {
            val result = runCatching {
                buildBlocking(appContext, companionId, forceRoles, callback) {
                    snapshotCaptured = true
                }
            }
            try {
                result.onFailure { throwable ->
                        val restored = if (snapshotCaptured && forceRoles.size == 1) {
                            CompanionAssetPackSnapshot.restoreLatest(appContext, companionId) != null
                        } else false
                        val baseMessage = throwable.message ?: "角色资源包生成失败"
                        val message = if (restored) "$baseMessage，已自动恢复上一版" else baseMessage
                        CompanionBirthCertificateStore.update(appContext, companionId) {
                            it.copy(
                                status = if (preserveReadyProfile) CompanionBirthStatus.READY
                                    else CompanionBirthStatus.FAILED,
                                failureReason = message.take(160)
                            )
                        }
                        if (!preserveReadyProfile) {
                            CompanionProfileStore.setStatus(appContext, companionId, CompanionStatus.FAILED)
                        }
                        dispatch(callback, Event.Failed(message))
                    }
            } finally {
                inFlight.remove(safeId)
            }
        }
    }

    fun isBuilding(companionId: String): Boolean =
        CompanionProfileRules.normalizeId(companionId) in inFlight

    private fun buildBlocking(
        context: Context,
        companionId: String,
        forceRoles: Set<CompanionAssetRole>,
        callback: (Event) -> Unit,
        onSnapshotCaptured: () -> Unit
    ) {
        val profile = CompanionProfileStore.find(context, companionId)
            ?: error("没有找到这个角色")
        val certificate = CompanionBirthCertificateStore.load(context, companionId)
            ?: error("角色出生证不存在")
        require(certificate.status in setOf(
            CompanionBirthStatus.ANCHOR_APPROVED,
            CompanionBirthStatus.BUILDING_PACK,
            CompanionBirthStatus.FAILED,
            CompanionBirthStatus.READY
        )) { "请先确认角色出生证" }
        val anchorName = certificate.anchorFileName ?: error("角色出生证图片不存在")
        val anchor = File(CompanionWorkspace.assetPackRoot(context, companionId), anchorName)
        require(anchor.isFile) { "角色出生证图片已经失效" }
        val capability = ImageGenClient.capability()
        require(capability.canEditReference) { "当前生图模型不能生成角色资源包" }

        val preserveReadyProfile = profile.status == CompanionStatus.READY
        CompanionBirthCertificateStore.update(context, companionId) {
            it.copy(
                status = if (preserveReadyProfile) CompanionBirthStatus.READY
                    else CompanionBirthStatus.BUILDING_PACK,
                failureReason = null
            )
        }
        if (!preserveReadyProfile) {
            CompanionProfileStore.setStatus(context, companionId, CompanionStatus.PACK_BUILDING)
        }

        val anchorB64 = Base64.encodeToString(anchor.readBytes(), Base64.NO_WRAP)
        val refinableRoles = CompanionGenerationPlan.phaseOneAssets
            .map { it.role }
            .filterNot { it == CompanionAssetRole.IDENTITY_ANCHOR }
            .toSet()
        require(forceRoles.all { it in refinableRoles }) { "包含不能精修的角色资源" }
        val specs = CompanionGenerationPlan.phaseOneAssets
            .filterNot { it.role == CompanionAssetRole.IDENTITY_ANCHOR }
            .filter { forceRoles.isEmpty() || it.role in forceRoles }
        val anchorBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(anchor.absolutePath, anchorBounds)
        require(anchorBounds.outWidth >= 512 && anchorBounds.outHeight >= 512) {
            "角色出生证图片已经损坏"
        }
        val entries = CompanionAssetPack.entries(context, companionId)
            .filterNot { it.role == CompanionAssetRole.IDENTITY_ANCHOR }
            .toMutableList()
            .apply {
                add(
                    CompanionAssetEntry(
                        role = CompanionAssetRole.IDENTITY_ANCHOR,
                        fileName = anchor.name,
                        sha256 = CompanionReferenceImporter.sha256(anchor),
                        width = anchorBounds.outWidth,
                        height = anchorBounds.outHeight
                    )
                )
            }
        CompanionAssetPack.writeManifest(context, companionId, entries)
        var generated = 0
        specs.forEachIndexed { index, spec ->
            val forced = spec.role in forceRoles
            if (!forced && CompanionAssetPack.isComplete(context, companionId, spec)) {
                dispatch(callback, Event.Progress(index + 1, specs.size, "${spec.role.label}已存在"))
                return@forEachIndexed
            }
            dispatch(callback, Event.Progress(index, specs.size, "正在生成${spec.role.label}"))
            val references = buildList {
                add(anchorB64)
                sceneReferenceAsset(spec.role)?.let { asset ->
                    context.assets.open(asset).use { input ->
                        add(Base64.encodeToString(input.readBytes(), Base64.NO_WRAP))
                    }
                }
                if (forced) {
                    val currentSheet = File(
                        CompanionWorkspace.assetPackRoot(context, companionId),
                        "${spec.role.key}_sheet.png"
                    )
                    val current = currentSheet.takeIf(File::isFile)
                        ?: CompanionAssetPack.file(context, companionId, spec.role)
                    current?.takeIf(File::isFile)?.let { file ->
                        add(Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
                    }
                }
            }
            var acceptedBytes: ByteArray? = null
            var inspection: CompanionAssetQualityGate.Result? = null
            repeat(2) { attempt ->
                if (acceptedBytes != null) return@repeat
                val repair = if (attempt == 0) "" else
                    "\nThe previous output failed local quality checks: ${inspection?.reason}. Correct that failure strictly"
                val refinement = if (forced) {
                    "\nThis is a targeted refinement. Use the final supplied image as the current approved composition baseline, preserve everything that is already correct, and only improve the requested asset"
                } else ""
                val output = ImageGenClient.editImages(
                    prompt = CompanionGenerationPlan.assetPrompt(profile.displayName, spec.role) +
                        refinement + repair,
                    imageBase64List = references,
                    size = "${spec.width}x${spec.height}",
                    transparentBackground = spec.transparentBackground,
                    allowSingleReferenceFallback = references.size == 1
                ) ?: return@repeat
                val candidate = runCatching { Base64.decode(output, Base64.DEFAULT) }.getOrNull()
                    ?: return@repeat
                inspection = CompanionAssetQualityGate.inspect(candidate, spec)
                if (inspection?.accepted == true) acceptedBytes = candidate
            }
            val bytes = acceptedBytes
            if (bytes == null) {
                val detail = inspection?.reason?.let { "：$it" }.orEmpty()
                if (spec.requiredForActivation) {
                    error("${spec.role.label}未通过质量检查$detail，已保留前面的结果，可稍后续建")
                }
                return@forEachIndexed
            }
            if (forced) {
                require(CompanionAssetPackSnapshot.capture(context, companionId, spec.role)) {
                    "没有成功保存上一版，已取消精修"
                }
                onSnapshotCaptured()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth < 512 || bounds.outHeight < 512) {
                if (spec.requiredForActivation) error("${spec.role.label}返回了无效图片")
                return@forEachIndexed
            }
            val packRoot = CompanionWorkspace.assetPackRoot(context, companionId)
            val frameFiles = if (spec.frameGrid != null) {
                val sheet = File(packRoot, "${spec.role.key}_sheet.png")
                atomicWrite(sheet, bytes)
                splitFrames(sheet, packRoot, spec.role, spec.frameGrid)
            } else {
                emptyList()
            }
            val target = if (frameFiles.isNotEmpty()) {
                frameFiles.first()
            } else {
                File(packRoot, "${spec.role.key}.png").also { atomicWrite(it, bytes) }
            }
            val targetBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, targetBounds)
            val entry = CompanionAssetEntry(
                role = spec.role,
                fileName = target.name,
                sha256 = CompanionReferenceImporter.sha256(target),
                width = targetBounds.outWidth,
                height = targetBounds.outHeight,
                frameFileNames = frameFiles.map(File::getName),
                qualityScore = inspection?.score ?: 0
            )
            entries.removeAll { it.role == spec.role }
            entries += entry
            CompanionAssetPack.writeManifest(context, companionId, entries)
            generated++
            dispatch(callback, Event.Progress(index + 1, specs.size, "${spec.role.label}完成"))
        }

        require(CompanionAssetPack.hasRequiredAssets(context, companionId)) {
            "资源包还不完整，不会启用这个角色"
        }
        require(CompanionReferenceImporter.purgeAfterPackReady(context, companionId)) {
            "原始参考图未能安全删除，暂不启用这个角色"
        }
        CompanionBirthCertificateStore.update(context, companionId) {
            it.copy(status = CompanionBirthStatus.READY, failureReason = null)
        }
        CompanionProfileStore.setStatus(context, companionId, CompanionStatus.READY)
        dispatch(callback, Event.Complete(companionId, generated))
    }

    private fun sceneReferenceAsset(role: CompanionAssetRole): String? = when (role) {
        CompanionAssetRole.ROOM_IDLE -> "deep_companion/scenes/room_idle.png"
        CompanionAssetRole.ROOM_SPEAKING -> "deep_companion/scenes/room_speaking.png"
        CompanionAssetRole.DESK_IDLE -> "deep_companion/scenes/desk_fullscreen_v3.png"
        CompanionAssetRole.SHELF_IDLE -> "deep_companion/scenes/shelf_fullscreen_v2.png"
        else -> null
    }

    private fun dispatch(callback: (Event) -> Unit, event: Event) {
        mainHandler.post { callback(event) }
    }

    private fun splitFrames(
        sheetFile: File,
        packRoot: File,
        role: CompanionAssetRole,
        grid: CompanionFrameGrid
    ): List<File> {
        val sheet = BitmapFactory.decodeFile(sheetFile.absolutePath)
            ?: error("${role.label}动作母版无法解码")
        return try {
            val cellWidth = sheet.width / grid.columns
            val cellHeight = sheet.height / grid.rows
            require(cellWidth > 0 && cellHeight > 0) { "${role.label}动作母版尺寸错误" }
            buildList(grid.frameCount) {
                for (row in 0 until grid.rows) {
                    for (column in 0 until grid.columns) {
                        val frame = Bitmap.createBitmap(
                            sheet,
                            column * cellWidth,
                            row * cellHeight,
                            cellWidth,
                            cellHeight
                        )
                        try {
                            val index = row * grid.columns + column + 1
                            val target = File(packRoot, "%s_frame_%02d.png".format(role.key, index))
                            atomicWriteBitmap(target, frame)
                            add(target)
                        } finally {
                            frame.recycle()
                        }
                    }
                }
            }
        } finally {
            sheet.recycle()
        }
    }

    private fun atomicWriteBitmap(target: File, bitmap: Bitmap) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.outputStream().use { output ->
            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "动作帧写入失败"
            }
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.writeBytes(bytes)
            temp.delete()
        }
    }
}
