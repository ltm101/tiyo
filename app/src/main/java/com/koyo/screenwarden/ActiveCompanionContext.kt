package com.koyo.screenwarden

import android.content.Context

/**
 * Immutable app-wide identity snapshot for the companion that owns an operation.
 *
 * UI can capture a fresh value while binding. Background work must capture once
 * and pass the contained [scope] through the whole operation so a later profile
 * switch cannot redirect writes, notifications, or memories to another role.
 */
class ActiveCompanionContext private constructor(
    val profile: CompanionProfile,
    val scope: CompanionScope
) {
    val companionId: String get() = scope.companionId
    val displayName: String get() = scope.displayName
    val isBuiltInCompanion: Boolean get() = scope.isBuiltInCompanion

    fun namespaced(base: String): String = scope.namespaced(base)

    companion object {
        fun capture(context: Context): ActiveCompanionContext {
            val profile = CompanionProfileStore.active(context.applicationContext)
            return ActiveCompanionContext(
                profile = profile,
                scope = CompanionScope.of(profile.id, profile.displayName)
            )
        }

        fun forScope(context: Context, scope: CompanionScope): ActiveCompanionContext {
            val profile = CompanionProfileStore.find(context.applicationContext, scope.companionId)
                ?: CompanionProfile(
                    id = scope.companionId,
                    displayName = scope.displayName,
                    origin = if (scope.isBuiltInCompanion) CompanionOrigin.BUILTIN else CompanionOrigin.CUSTOM,
                    status = CompanionStatus.READY,
                    createdAt = 0L,
                    updatedAt = 0L
                )
            return ActiveCompanionContext(profile, CompanionScope.of(profile.id, profile.displayName))
        }
    }
}
