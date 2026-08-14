package com.koyo.screenwarden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionRelationshipStoreTest {
    @Test
    fun pairIdIsStableRegardlessOfOrder() {
        assertEquals(
            CompanionRelationshipStore.pairId("tiyo", "companion-z"),
            CompanionRelationshipStore.pairId("companion-z", "tiyo")
        )
    }

    @Test
    fun malformedRelationshipsFailClosed() {
        assertTrue(CompanionRelationshipStore.parseRelationships("not-json").isEmpty())
    }

    @Test
    fun customPersonaDoesNotCloneAnotherRoleRelationshipOrBiography() {
        val persona = PersonaFragment.customCompanionPersona(
            "阿澈",
            "用户甲",
            UserPrefs.AgeGroup.YOUTH
        )

        assertTrue(persona.contains("你是阿澈"))
        assertTrue(persona.contains("不是 Tiyo"))
        assertTrue(persona.contains("不要继承、冒领"))
        assertTrue(persona.contains("隔离记忆"))
    }

    @Test
    fun exactLegacyCloneMigratesButEditedPersonaIsPreserved() {
        val profile = CompanionProfile(
            id = "companion-a",
            displayName = "阿澈",
            origin = CompanionOrigin.CUSTOM,
            status = CompanionStatus.READY,
            createdAt = 1L,
            updatedAt = 1L
        )
        val legacy = PersonaFragment.personaFor("用户甲", UserPrefs.AgeGroup.YOUTH)
            .replace(CompanionProfileRules.DEFAULT_COMPANION_NAME, profile.displayName)
        val migrated = PersonaFragment.migrateLegacyCustomClone(
            legacy,
            profile,
            "用户甲",
            UserPrefs.AgeGroup.YOUTH
        )
        val edited = "$legacy\n这是我自己写的偏好"

        assertTrue(migrated.contains("不是 Tiyo"))
        assertEquals(
            edited,
            PersonaFragment.migrateLegacyCustomClone(
                edited,
                profile,
                "用户甲",
                UserPrefs.AgeGroup.YOUTH
            )
        )
    }
}
