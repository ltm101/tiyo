package com.koyo.screenwarden

import android.content.Context
import java.io.File

/**
 * Filesystem namespaces for companion-owned data.
 *
 * Koyo deliberately maps to the legacy paths so an upgrade cannot move or lose
 * any of the user's current persona and memories. Custom companions live under
 * their own directory and never read Koyo's private state by accident.
 */
object CompanionWorkspace {

    fun publicRoot(context: Context, companionId: String = CompanionProfileStore.activeId(context)): File {
        val id = safeId(companionId)
        return if (id == CompanionProfileRules.DEFAULT_COMPANION_ID) {
            TiyoWorkspace.root(context)
        } else {
            File(TiyoWorkspace.root(context), "companions/$id").apply { mkdirs() }
        }
    }

    fun privateRoot(context: Context, companionId: String = CompanionProfileStore.activeId(context)): File {
        val id = safeId(companionId)
        return File(context.filesDir, "companions/$id").apply { mkdirs() }
    }

    fun agentHome(context: Context, companionId: String = CompanionProfileStore.activeId(context)): File {
        val id = safeId(companionId)
        val name = if (id == CompanionProfileRules.DEFAULT_COMPANION_ID) {
            "tiyo-agent"
        } else {
            "tiyo-agent-$id"
        }
        return File(context.filesDir, name).apply { mkdirs() }
    }

    fun personaFile(context: Context, companionId: String = CompanionProfileStore.activeId(context)): File =
        File(publicRoot(context, companionId), "TIYO.md")

    fun memoryRoot(context: Context, companionId: String = CompanionProfileStore.activeId(context)): File =
        File(publicRoot(context, companionId), "memory").apply { mkdirs() }

    fun assetPackRoot(context: Context, companionId: String = CompanionProfileStore.activeId(context)): File =
        File(privateRoot(context, companionId), "asset-pack").apply { mkdirs() }

    fun referenceRoot(context: Context, companionId: String): File =
        File(privateRoot(context, companionId), "references").apply { mkdirs() }

    fun birthCertificateFile(context: Context, companionId: String): File =
        File(privateRoot(context, companionId), "birth-certificate.json")

    fun sessionsPrefsName(companionId: String): String {
        val id = safeId(companionId)
        return if (id == CompanionProfileRules.DEFAULT_COMPANION_ID) {
            "tiyo_sessions"
        } else {
            "tiyo_sessions_$id"
        }
    }

    fun eraseCustomData(context: Context, companionId: String): Boolean {
        val id = safeId(companionId)
        require(id != CompanionProfileRules.DEFAULT_COMPANION_ID) {
            "The built-in Koyo workspace cannot be erased"
        }
        val privateParent = File(context.filesDir, "companions")
        val publicParent = File(TiyoWorkspace.root(context), "companions")
        val agentParent = context.filesDir
        val targets = listOf(
            File(privateParent, id),
            File(publicParent, id),
            File(agentParent, "tiyo-agent-$id")
        )
        val filesRemoved = targets.map { target ->
            deleteWithin(target, target.parentFile ?: return@map false)
        }.all { it }
        val preferencesRemoved = listOf(
            sessionsPrefsName(id),
            "tiyo_memory_$id",
            "tiyo_memory_care_$id"
        ).map { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }.all { it }
        TiyoSecureStore.remove(context, "memory_sync_token_$id")
        return filesRemoved && preferencesRemoved
    }

    private fun deleteWithin(target: File, parent: File): Boolean {
        val canonicalParent = parent.canonicalFile
        val canonicalTarget = target.canonicalFile
        if (canonicalTarget.parentFile != canonicalParent) return false
        return !canonicalTarget.exists() || canonicalTarget.deleteRecursively()
    }

    private fun safeId(value: String): String = CompanionProfileRules.normalizeId(value)
}
