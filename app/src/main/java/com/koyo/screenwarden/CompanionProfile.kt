package com.koyo.screenwarden

import java.util.Locale

/**
 * A companion is more than an avatar. Every profile owns an isolated persona,
 * chat registry, memory namespace and visual asset pack.
 *
 * Koyo is Tiyo's immutable built-in guide and is always available as a safe fallback.
 */
data class CompanionProfile(
    val id: String,
    val displayName: String,
    val origin: CompanionOrigin,
    val status: CompanionStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val visualStyle: String = CompanionProfileRules.DEFAULT_VISUAL_STYLE,
    val schemaVersion: Int = CompanionProfileRules.SCHEMA_VERSION
) {
    val isBuiltInCompanion: Boolean
        get() = id == CompanionProfileRules.DEFAULT_COMPANION_ID
}

enum class CompanionOrigin(val key: String) {
    BUILTIN("builtin"),
    CUSTOM("custom");

    companion object {
        fun fromKey(value: String?): CompanionOrigin =
            entries.firstOrNull { it.key == value } ?: CUSTOM
    }
}

enum class CompanionStatus(val key: String) {
    DRAFT("draft"),
    ANCHOR_PENDING("anchor_pending"),
    ANCHOR_REVIEW("anchor_review"),
    PACK_BUILDING("pack_building"),
    READY("ready"),
    FAILED("failed");

    companion object {
        fun fromKey(value: String?): CompanionStatus =
            entries.firstOrNull { it.key == value } ?: DRAFT
    }
}

object CompanionProfileRules {
    const val SCHEMA_VERSION = 1
    const val DEFAULT_COMPANION_ID = "koyo"
    const val DEFAULT_COMPANION_NAME = "可又"
    private const val LEGACY_DEFAULT_COMPANION_ID = "tiyo"
    const val DEFAULT_VISUAL_STYLE = "tiyo_illustrated_2d"
    const val MAX_NAME_LENGTH = 16
    private const val MAX_ID_LENGTH = 48

    fun defaultCompanion(now: Long = 0L): CompanionProfile = CompanionProfile(
        id = DEFAULT_COMPANION_ID,
        displayName = DEFAULT_COMPANION_NAME,
        origin = CompanionOrigin.BUILTIN,
        status = CompanionStatus.READY,
        createdAt = now,
        updatedAt = now
    )

    fun normalizeName(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_NAME_LENGTH)

    /** IDs are used only as local namespace components and never shown to users. */
    fun normalizeId(value: String): String {
        val normalized = value.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-', '_')
            .take(MAX_ID_LENGTH)
        val safeId = normalized.ifBlank { "companion" }
        return if (safeId == LEGACY_DEFAULT_COMPANION_ID) DEFAULT_COMPANION_ID else safeId
    }

    fun canActivate(profile: CompanionProfile): Boolean =
        profile.isBuiltInCompanion || profile.status == CompanionStatus.READY

    fun canMutate(profile: CompanionProfile): Boolean = !profile.isBuiltInCompanion

    fun canDelete(profile: CompanionProfile): Boolean = !profile.isBuiltInCompanion

    fun canUseCustomName(value: String): Boolean {
        val name = normalizeName(value)
        return name.isNotBlank() && name != DEFAULT_COMPANION_NAME
    }
}
