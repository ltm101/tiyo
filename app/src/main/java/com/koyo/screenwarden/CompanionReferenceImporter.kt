package com.koyo.screenwarden

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Copies approved reference photos into app-private storage without retaining gallery URIs. */
object CompanionReferenceImporter {
    private const val MAX_FILE_BYTES = 16L * 1024L * 1024L

    data class ImportResult(
        val imported: List<CompanionReferenceAsset>,
        val rejectedCount: Int
    )

    fun import(
        context: Context,
        companionId: String,
        uris: List<Uri>
    ): ImportResult {
        val certificate = CompanionBirthCertificateStore.load(context, companionId)
            ?: return ImportResult(emptyList(), uris.size)
        val remaining = CompanionBirthCertificate.MAX_REFERENCES - certificate.references.size
        if (remaining <= 0) return ImportResult(emptyList(), uris.size)

        val imported = mutableListOf<CompanionReferenceAsset>()
        var rejected = (uris.size - remaining).coerceAtLeast(0)
        uris.take(remaining).forEach { uri ->
            val asset = runCatching { copyOne(context, companionId, uri) }.getOrNull()
            if (asset == null) rejected++ else imported += asset
        }
        if (imported.isNotEmpty()) {
            CompanionBirthCertificateStore.update(context, companionId) { current ->
                val merged = (current.references + imported)
                    .distinctBy { it.sha256 }
                    .take(CompanionBirthCertificate.MAX_REFERENCES)
                current.copy(
                    references = merged,
                    status = CompanionBirthStatus.REFERENCES_READY,
                    failureReason = null
                )
            }
            CompanionProfileStore.setStatus(context, companionId, CompanionStatus.ANCHOR_PENDING)
        }
        return ImportResult(imported, rejected)
    }

    private fun copyOne(context: Context, companionId: String, uri: Uri): CompanionReferenceAsset {
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        require(mimeType.startsWith("image/")) { "Reference must be an image" }
        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val root = CompanionWorkspace.referenceRoot(context, companionId)
        val temp = File(root, "reference-${System.nanoTime()}.tmp")
        try {
            var total = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_FILE_BYTES) { "Reference image is too large" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Unable to open reference image")
            require(total > 0) { "Reference image is empty" }
            val sha = sha256(temp)
            val target = File(root, "reference-${sha.take(16)}.$extension")
            if (target.isFile) {
                temp.delete()
            } else if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = false)
                temp.delete()
            }
            return CompanionReferenceAsset(
                fileName = target.name,
                mimeType = mimeType,
                length = target.length(),
                sha256 = sha
            )
        } catch (error: Exception) {
            temp.delete()
            throw error
        }
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun purgeAfterPackReady(context: Context, companionId: String): Boolean {
        val certificate = CompanionBirthCertificateStore.load(context, companionId) ?: return false
        if (certificate.keepOriginalReferences) return true
        val root = CompanionWorkspace.referenceRoot(context, companionId).canonicalFile
        val removed = certificate.references.map { reference ->
            val target = File(root, reference.fileName).canonicalFile
            target.parentFile == root && (!target.exists() || target.delete())
        }.all { it }
        if (!removed) return false
        CompanionBirthCertificateStore.update(context, companionId) {
            it.copy(references = emptyList())
        }
        return true
    }
}
