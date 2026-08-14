package com.koyo.screenwarden

import android.content.Context

/**
 * Immutable identity captured when a task starts.
 *
 * Background work must never look up the currently active companion again: the
 * user may switch profiles while extraction, sync, or a tool call is still in
 * flight. Passing this value through the whole operation keeps every write in
 * the namespace that originally started it.
 */
class CompanionScope private constructor(
    val companionId: String,
    val displayName: String
) {
    val isBuiltInCompanion: Boolean
        get() = companionId == CompanionProfileRules.DEFAULT_COMPANION_ID

    fun namespaced(base: String): String =
        if (isBuiltInCompanion) base else "${base}_$companionId"

    companion object {
        fun capture(context: Context): CompanionScope {
            val profile = CompanionProfileStore.active(context)
            return of(profile.id, profile.displayName)
        }

        fun of(companionId: String, displayName: String = ""): CompanionScope {
            val normalizedId = CompanionProfileRules.normalizeId(companionId)
            val normalizedName = CompanionProfileRules.normalizeName(displayName).ifBlank {
                if (normalizedId == CompanionProfileRules.DEFAULT_COMPANION_ID) {
                    CompanionProfileRules.DEFAULT_COMPANION_NAME
                } else {
                    normalizedId
                }
            }
            return CompanionScope(normalizedId, normalizedName)
        }
    }
}
