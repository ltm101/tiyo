package com.koyo.screenwarden

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persistent registry and active-companion switch. */
object CompanionProfileStore {
    private const val PREFS = "tiyo_companion_profiles"
    private const val KEY_ACTIVE = "active_companion_id"
    private const val KEY_PROFILES = "profiles_v1"

    fun activeId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val requested = CompanionProfileRules.normalizeId(
            prefs.getString(KEY_ACTIVE, CompanionProfileRules.DEFAULT_COMPANION_ID).orEmpty()
        )
        val active = profiles(context).firstOrNull { it.id == requested }
        return active?.takeIf(CompanionProfileRules::canActivate)?.id
            ?: CompanionProfileRules.DEFAULT_COMPANION_ID
    }

    fun active(context: Context): CompanionProfile =
        profiles(context).firstOrNull { it.id == activeId(context) }
            ?: CompanionProfileRules.defaultCompanion()

    fun activeName(context: Context): String = active(context).displayName

    fun profiles(context: Context): List<CompanionProfile> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val parsed = parseProfiles(prefs.getString(KEY_PROFILES, null))
        val custom = parsed.filterNot { it.id == CompanionProfileRules.DEFAULT_COMPANION_ID }
        val default = CompanionProfileRules.defaultCompanion(
            parsed.firstOrNull { it.id == CompanionProfileRules.DEFAULT_COMPANION_ID }?.createdAt ?: 0L
        )
        return listOf(default) + custom.sortedBy { it.createdAt }
    }

    fun find(context: Context, id: String): CompanionProfile? {
        val safeId = CompanionProfileRules.normalizeId(id)
        return profiles(context).firstOrNull { it.id == safeId }
    }

    fun createDraft(
        context: Context,
        displayName: String,
        now: Long = System.currentTimeMillis()
    ): CompanionProfile {
        val name = CompanionProfileRules.normalizeName(displayName)
        require(CompanionProfileRules.canUseCustomName(name)) {
            "The built-in Koyo name is reserved"
        }
        val id = CompanionProfileRules.normalizeId("companion-${UUID.randomUUID()}")
        val profile = CompanionProfile(
            id = id,
            displayName = name,
            origin = CompanionOrigin.CUSTOM,
            status = CompanionStatus.DRAFT,
            createdAt = now,
            updatedAt = now
        )
        writeProfiles(context, profiles(context) + profile)
        CompanionWorkspace.privateRoot(context, id)
        CompanionWorkspace.publicRoot(context, id)
        return profile
    }

    fun rename(context: Context, id: String, newName: String): Boolean {
        val name = CompanionProfileRules.normalizeName(newName)
        if (!CompanionProfileRules.canUseCustomName(name)) return false
        return update(context, id) { it.copy(displayName = name) }
    }

    fun setStatus(context: Context, id: String, status: CompanionStatus): Boolean =
        update(context, id) { it.copy(status = status) }

    fun activate(context: Context, id: String): Boolean {
        val profile = find(context, id) ?: return false
        if (!CompanionProfileRules.canActivate(profile)) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE, profile.id)
            .apply()
        return true
    }

    fun delete(context: Context, id: String): Boolean {
        val profile = find(context, id) ?: return false
        if (!CompanionProfileRules.canDelete(profile)) return false
        val wasActive = activeId(context) == profile.id
        val remaining = profiles(context).filterNot { it.id == profile.id }
        writeProfiles(context, remaining)
        if (wasActive) {
            activate(context, CompanionProfileRules.DEFAULT_COMPANION_ID)
        }
        // Deliberately retain profile files for recoverability. A separate explicit
        // erase action can remove private photos after the user confirms deletion.
        return true
    }

    fun erase(context: Context, id: String): Boolean {
        val profile = find(context, id) ?: return false
        if (!CompanionProfileRules.canDelete(profile)) return false
        if (activeId(context) == profile.id) {
            activate(context, CompanionProfileRules.DEFAULT_COMPANION_ID)
        }
        val erased = runCatching { CompanionWorkspace.eraseCustomData(context, profile.id) }
            .getOrDefault(false)
        if (!erased) return false
        CompanionRelationshipStore.eraseProfile(context, profile.id)
        return delete(context, profile.id)
    }

    private fun update(
        context: Context,
        id: String,
        transform: (CompanionProfile) -> CompanionProfile
    ): Boolean {
        val safeId = CompanionProfileRules.normalizeId(id)
        val current = profiles(context)
        val index = current.indexOfFirst { it.id == safeId }
        if (index < 0 || !CompanionProfileRules.canMutate(current[index])) return false
        val updated = transform(current[index]).copy(
            id = current[index].id,
            origin = current[index].origin,
            createdAt = current[index].createdAt,
            updatedAt = System.currentTimeMillis()
        )
        writeProfiles(context, current.toMutableList().apply { set(index, updated) })
        return true
    }

    private fun writeProfiles(context: Context, profiles: List<CompanionProfile>) {
        val unique = profiles.distinctBy { it.id }
        val array = JSONArray()
        unique.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("display_name", profile.displayName)
                    .put("origin", profile.origin.key)
                    .put("status", profile.status.key)
                    .put("created_at", profile.createdAt)
                    .put("updated_at", profile.updatedAt)
                    .put("visual_style", profile.visualStyle)
                    .put("schema_version", profile.schemaVersion)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES, array.toString())
            .apply()
    }

    internal fun parseProfiles(raw: String?): List<CompanionProfile> {
        if (raw.isNullOrBlank()) return listOf(CompanionProfileRules.defaultCompanion())
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = CompanionProfileRules.normalizeId(item.optString("id"))
                    val origin = CompanionOrigin.fromKey(item.optString("origin"))
                    val storedName = CompanionProfileRules.normalizeName(item.optString("display_name"))
                    val name = if (
                        id == CompanionProfileRules.DEFAULT_COMPANION_ID &&
                        origin == CompanionOrigin.BUILTIN
                    ) {
                        CompanionProfileRules.DEFAULT_COMPANION_NAME
                    } else {
                        storedName
                    }
                    if (name.isBlank()) continue
                    add(
                        CompanionProfile(
                            id = id,
                            displayName = name,
                            origin = origin,
                            status = CompanionStatus.fromKey(item.optString("status")),
                            createdAt = item.optLong("created_at"),
                            updatedAt = item.optLong("updated_at"),
                            visualStyle = item.optString("visual_style")
                                .ifBlank { CompanionProfileRules.DEFAULT_VISUAL_STYLE },
                            schemaVersion = item.optInt(
                                "schema_version",
                                CompanionProfileRules.SCHEMA_VERSION
                            )
                        )
                    )
                }
            }
        }.getOrElse { listOf(CompanionProfileRules.defaultCompanion()) }
    }
}
