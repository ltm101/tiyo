package com.koyo.screenwarden

import android.content.Context
import java.io.File

object CompanionBirthCertificateStore {

    fun create(
        context: Context,
        profile: CompanionProfile,
        consent: CompanionPhotoConsent,
        now: Long = System.currentTimeMillis()
    ): CompanionBirthCertificate {
        require(!profile.isBuiltInCompanion) { "The built-in Koyo profile cannot be replaced" }
        val certificate = CompanionBirthCertificate(
            companionId = profile.id,
            approvedName = profile.displayName,
            consent = consent,
            status = CompanionBirthStatus.DRAFT,
            createdAt = now,
            updatedAt = now
        )
        save(context, certificate)
        return certificate
    }

    fun load(context: Context, companionId: String): CompanionBirthCertificate? {
        val file = CompanionWorkspace.birthCertificateFile(context, companionId)
        if (!file.isFile) return null
        return runCatching {
            CompanionBirthCertificateCodec.decode(file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    fun update(
        context: Context,
        companionId: String,
        transform: (CompanionBirthCertificate) -> CompanionBirthCertificate
    ): CompanionBirthCertificate? {
        val current = load(context, companionId) ?: return null
        val updated = transform(current).copy(
            companionId = current.companionId,
            consent = current.consent,
            createdAt = current.createdAt,
            updatedAt = System.currentTimeMillis()
        )
        save(context, updated)
        return updated
    }

    fun save(context: Context, certificate: CompanionBirthCertificate) {
        val target = CompanionWorkspace.birthCertificateFile(context, certificate.companionId)
        atomicWrite(target, CompanionBirthCertificateCodec.encode(certificate))
    }

    private fun atomicWrite(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            temp.delete()
        }
    }
}
