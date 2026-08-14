package com.koyo.screenwarden

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors

/** Coordinates the first, user-approved identity-anchor generation step. */
object CompanionBirthEngine {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tiyo-companion-birth").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    sealed class Result {
        data class AnchorReady(val file: File, val certificate: CompanionBirthCertificate) : Result()
        data class Failed(val message: String) : Result()
    }

    fun createDraft(
        context: Context,
        displayName: String,
        consent: CompanionPhotoConsent
    ): CompanionProfile {
        val appContext = context.applicationContext
        val profile = CompanionProfileStore.createDraft(appContext, displayName)
        CompanionBirthCertificateStore.create(appContext, profile, consent)
        CompanionRelationshipStore.ensureForProfile(appContext, profile)
        initializePersona(appContext, profile)
        return profile
    }

    fun generateIdentityAnchor(
        context: Context,
        companionId: String,
        callback: (Result) -> Unit
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val result = runCatching {
                generateIdentityAnchorBlocking(appContext, companionId)
            }.getOrElse { throwable ->
                val message = failureMessage(throwable)
                fail(appContext, companionId, message)
                Result.Failed(message)
            }
            mainHandler.post { callback(result) }
        }
    }

    fun approveIdentityAnchor(context: Context, companionId: String): Boolean {
        val certificate = CompanionBirthCertificateStore.load(context, companionId) ?: return false
        if (certificate.status != CompanionBirthStatus.AWAITING_APPROVAL) return false
        val anchorName = certificate.anchorFileName ?: return false
        val anchor = File(CompanionWorkspace.assetPackRoot(context, companionId), anchorName)
        if (!anchor.isFile) return false
        CompanionBirthCertificateStore.update(context, companionId) {
            it.copy(status = CompanionBirthStatus.ANCHOR_APPROVED, failureReason = null)
        }
        CompanionProfileStore.setStatus(context, companionId, CompanionStatus.PACK_BUILDING)
        return true
    }

    fun rejectIdentityAnchor(context: Context, companionId: String): Boolean {
        val certificate = CompanionBirthCertificateStore.load(context, companionId) ?: return false
        certificate.anchorFileName?.let { name ->
            File(CompanionWorkspace.assetPackRoot(context, companionId), name).delete()
        }
        CompanionBirthCertificateStore.update(context, companionId) {
            it.copy(
                status = CompanionBirthStatus.REFERENCES_READY,
                anchorFileName = null,
                anchorSha256 = null,
                failureReason = null
            )
        }
        CompanionProfileStore.setStatus(context, companionId, CompanionStatus.ANCHOR_PENDING)
        return true
    }

    private fun generateIdentityAnchorBlocking(context: Context, companionId: String): Result {
        val profile = CompanionProfileStore.find(context, companionId)
            ?: error("没有找到这个角色草稿")
        require(!profile.isBuiltInCompanion) { "默认Tiyo不会被重新生成" }
        val certificate = CompanionBirthCertificateStore.load(context, companionId)
            ?: error("角色出生证草稿不存在")
        require(certificate.references.isNotEmpty()) { "请先选择参考图" }
        val capability = ImageGenClient.capability()
        require(capability.configured && capability.canEditReference) {
            "请先配置支持参考图编辑的生图模型"
        }

        CompanionBirthCertificateStore.update(context, companionId) {
            it.copy(status = CompanionBirthStatus.GENERATING_ANCHOR, failureReason = null)
        }
        CompanionProfileStore.setStatus(context, companionId, CompanionStatus.ANCHOR_PENDING)

        val root = CompanionWorkspace.referenceRoot(context, companionId)
        val references = certificate.references.mapNotNull { reference ->
            File(root, reference.fileName).takeIf(File::isFile)?.let(::encodeReferenceForUpload)
        }
        require(references.isNotEmpty()) { "参考图文件已经失效，请重新选择" }
        val generated = ImageGenClient.editImagesRequired(
            prompt = CompanionGenerationPlan.anchorPrompt(profile.displayName),
            imageBase64List = references,
            size = "1536x1024",
            transparentBackground = false
        )

        val bytes = Base64.decode(generated, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth >= 512 && bounds.outHeight >= 512) { "生图结果不是有效图片" }
        val target = File(CompanionWorkspace.assetPackRoot(context, companionId), "identity-anchor.png")
        atomicWrite(target, bytes)
        val sha = CompanionReferenceImporter.sha256(target)
        val manifestEntries = CompanionAssetPack.entries(context, companionId)
            .filterNot { it.role == CompanionAssetRole.IDENTITY_ANCHOR }
            .toMutableList()
            .apply {
                add(
                    CompanionAssetEntry(
                        role = CompanionAssetRole.IDENTITY_ANCHOR,
                        fileName = target.name,
                        sha256 = sha,
                        width = bounds.outWidth,
                        height = bounds.outHeight
                    )
                )
            }
        CompanionAssetPack.writeManifest(context, companionId, manifestEntries)
        val updated = CompanionBirthCertificateStore.update(context, companionId) {
            it.copy(
                status = CompanionBirthStatus.AWAITING_APPROVAL,
                anchorFileName = target.name,
                anchorSha256 = sha,
                failureReason = null
            )
        } ?: error("角色出生证保存失败")
        CompanionProfileStore.setStatus(context, companionId, CompanionStatus.ANCHOR_REVIEW)
        return Result.AnchorReady(target, updated)
    }

    private fun initializePersona(context: Context, profile: CompanionProfile) {
        val personaFile = CompanionWorkspace.personaFile(context, profile.id)
        if (personaFile.isFile) return
        val userName = UserPrefs.displayName(context)
        val personalized = PersonaFragment.customCompanionPersona(
            profile.displayName,
            userName,
            UserPrefs.getAgeGroup(context)
        )
        personaFile.parentFile?.mkdirs()
        personaFile.writeText(personalized, Charsets.UTF_8)
    }

    private fun fail(context: Context, companionId: String, reason: String) {
        CompanionBirthCertificateStore.update(context, companionId) {
            it.copy(status = CompanionBirthStatus.FAILED, failureReason = reason.take(160))
        }
        CompanionProfileStore.setStatus(context, companionId, CompanionStatus.FAILED)
    }

    private fun failureMessage(error: Throwable): String = when (error) {
        is SocketTimeoutException -> "生图服务处理超过 6 分钟，参考图已经保留，请稍后再试"
        is UnknownHostException -> "暂时连不上生图服务，请检查网络后重试"
        else -> error.message?.takeIf(String::isNotBlank) ?: "角色出生证生成失败"
    }

    /** Normalize large gallery files before multipart upload to reduce proxy and memory pressure. */
    private fun encodeReferenceForUpload(file: File): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "参考图无法读取" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: error("参考图无法读取")
        try {
            val output = ByteArrayOutputStream()
            val format = if (bitmap.hasAlpha()) {
                android.graphics.Bitmap.CompressFormat.PNG
            } else {
                android.graphics.Bitmap.CompressFormat.JPEG
            }
            val quality = if (format == android.graphics.Bitmap.CompressFormat.PNG) 100 else 94
            require(bitmap.compress(format, quality, output)) { "参考图压缩失败" }
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

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
