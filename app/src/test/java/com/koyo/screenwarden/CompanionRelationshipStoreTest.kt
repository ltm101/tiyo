package com.koyo.screenwarden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionRelationshipStoreTest {
    @Test
    fun pairIdIsStableRegardlessOfOrder() {
        assertEquals(
            CompanionRelationshipStore.pairId("koyo", "companion-z"),
            CompanionRelationshipStore.pairId("companion-z", "koyo")
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
        assertTrue(persona.contains("不是可又"))
        assertTrue(persona.contains("Tiyo 是承载角色和工具的应用"))
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

        assertTrue(migrated.contains("不是可又"))
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

    @Test
    fun publicKoyoGuideKnowsTiyo() {
        val persona = PersonaFragment.personaFor("用户甲", UserPrefs.AgeGroup.YOUTH)

        assertTrue(persona.contains("你是可又"))
        assertTrue(persona.contains("Tiyo 是承载角色、记忆、本机能力和协作工具的应用"))
        assertTrue(persona.contains("出生证"))
        assertTrue(persona.contains("图片理解"))
        assertTrue(persona.contains("按 companion ID 隔离"))
        assertTrue(persona.contains("开放版不使用 Live2D"))
    }

    @Test
    fun legacyPublicTiyoPersonaMigratesWithoutDroppingUserEdits() {
        val migrated = PersonaFragment.ensurePublicKoyoGuide(
            "你是 Tiyo，是应用内置的本地陪伴引导角色。\n保留这条用户自定义偏好"
        )

        assertTrue(migrated.contains("你是可又，是 Tiyo 应用内置的本地陪伴引导角色"))
        assertTrue(migrated.contains("保留这条用户自定义偏好"))
        assertTrue(migrated.contains("## Tiyo 产品知识"))
    }
}
