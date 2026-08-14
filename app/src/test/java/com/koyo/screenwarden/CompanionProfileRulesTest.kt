package com.koyo.screenwarden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionProfileRulesTest {
    @Test
    fun builtInKoyoIsAlwaysReadyAndImmutable() {
        val koyo = CompanionProfileRules.defaultCompanion(100L)

        assertEquals("tiyo", koyo.id)
        assertEquals("Tiyo", koyo.displayName)
        assertEquals(CompanionStatus.READY, koyo.status)
        assertTrue(CompanionProfileRules.canActivate(koyo))
        assertFalse(CompanionProfileRules.canMutate(koyo))
        assertFalse(CompanionProfileRules.canDelete(koyo))
        assertFalse(CompanionProfileRules.canUseCustomName("Tiyo"))
        assertTrue(CompanionProfileRules.canUseCustomName("小满"))
    }

    @Test
    fun draftCannotBecomeActiveBeforeItsPackIsReady() {
        val draft = CompanionProfile(
            id = "companion-demo",
            displayName = "小满",
            origin = CompanionOrigin.CUSTOM,
            status = CompanionStatus.DRAFT,
            createdAt = 1L,
            updatedAt = 1L
        )

        assertFalse(CompanionProfileRules.canActivate(draft))
        assertTrue(CompanionProfileRules.canActivate(draft.copy(status = CompanionStatus.READY)))
    }

    @Test
    fun customSessionNamespaceCannotCollideWithKoyo() {
        assertEquals("tiyo_sessions", CompanionWorkspace.sessionsPrefsName("tiyo"))
        assertEquals(
            "tiyo_sessions_companion-demo",
            CompanionWorkspace.sessionsPrefsName("../Companion Demo")
        )
        assertEquals("tts_voice", CompanionScope.of("tiyo", "Tiyo").namespaced("tts_voice"))
        assertEquals(
            "tts_voice_companion-demo",
            CompanionScope.of("../Companion Demo", "小满").namespaced("tts_voice")
        )
    }

    @Test
    fun profileParserRestoresKoyoEvenWhenRegistryIsMalformed() {
        val parsed = CompanionProfileStore.parseProfiles("not-json")

        assertEquals(1, parsed.size)
        assertTrue(parsed.single().isBuiltInCompanion)
    }

    @Test
    fun birthCertificateRoundTripKeepsConsentAndReferenceHashes() {
        val certificate = CompanionBirthCertificate(
            companionId = "companion-demo",
            approvedName = "小满",
            consent = CompanionPhotoConsent.SELF,
            status = CompanionBirthStatus.REFERENCES_READY,
            references = listOf(
                CompanionReferenceAsset("reference-a.png", "image/png", 123L, "abc123")
            ),
            identityNotes = listOf("深棕色长发"),
            createdAt = 10L,
            updatedAt = 20L
        )

        val decoded = CompanionBirthCertificateCodec.decode(
            CompanionBirthCertificateCodec.encode(certificate)
        )

        assertNotNull(decoded)
        assertEquals(certificate, decoded)
    }

    @Test
    fun phaseOnePlanRequiresAnchorAndCoreScreenPoses() {
        val required = CompanionGenerationPlan.phaseOneAssets
            .filter { it.requiredForActivation }
            .map { it.role }

        assertTrue(CompanionAssetRole.IDENTITY_ANCHOR in required)
        assertTrue(CompanionAssetRole.CHAT_PORTRAIT in required)
        assertTrue(CompanionAssetRole.TODAY_IDLE in required)
        assertTrue(CompanionAssetRole.CHAT_PRONE in required)
        assertTrue(CompanionAssetRole.ROOM_IDLE in required)
        assertTrue(CompanionAssetRole.ROOM_SPEAKING in required)
        assertTrue(CompanionAssetRole.DESK_IDLE in required)
        assertTrue(CompanionAssetRole.SHELF_IDLE in required)
        assertTrue(CompanionGenerationPlan.anchorPrompt("小满").contains("same person"))
        assertTrue(
            CompanionGenerationPlan.assetPrompt("小满", CompanionAssetRole.ROOM_IDLE)
                .contains("preserve the room")
        )
        val animated = CompanionGenerationPlan.phaseOneAssets
            .filter { it.frameGrid != null }
            .associate { it.role to it.frameGrid!!.frameCount }
        assertEquals(6, animated[CompanionAssetRole.TODAY_IDLE])
        assertEquals(6, animated[CompanionAssetRole.TODAY_WAVE])
        assertEquals(6, animated[CompanionAssetRole.CHAT_PRONE])
        assertEquals(9, animated[CompanionAssetRole.STICKER_SHEET])
        assertTrue(
            CompanionGenerationPlan.assetPrompt("小满", CompanionAssetRole.TODAY_WAVE)
                .contains("3 by 2 sprite sheet")
        )
    }

    @Test
    fun customRendererMapsEveryLegacyActionToOwnedAssets() {
        assertEquals(
            CompanionAssetRole.TODAY_WAVE,
            CompanionAssetPack.roleForAction("invite_chat")
        )
        assertEquals(
            CompanionAssetRole.CHAT_PRONE,
            CompanionAssetPack.roleForAction("blink_prone")
        )
        assertEquals(
            CompanionAssetRole.CHAT_PORTRAIT,
            CompanionAssetPack.roleForAction("look_left")
        )
        assertEquals(
            CompanionAssetRole.TODAY_IDLE,
            CompanionAssetPack.roleForAction("stretch")
        )
        assertEquals(
            CompanionAssetRole.CHAT_PORTRAIT,
            CompanionAssetPack.roleForAction("future_action")
        )
    }

    @Test
    fun refinementSnapshotRoundTripKeepsFramesAndQualityScore() {
        val entry = CompanionAssetEntry(
            role = CompanionAssetRole.TODAY_WAVE,
            fileName = "today_wave_frame_01.png",
            sha256 = "abc123",
            width = 512,
            height = 512,
            frameFileNames = (1..6).map { "today_wave_frame_%02d.png".format(it) },
            qualityScore = 88
        )

        val decoded = CompanionAssetPackSnapshot.decodeEntry(
            CompanionAssetPackSnapshot.encodeEntry(entry)
        )

        assertEquals(entry, decoded)
        assertFalse(CompanionAssetPackSnapshot.canCapture(CompanionAssetRole.IDENTITY_ANCHOR))
        assertTrue(CompanionAssetPackSnapshot.canCapture(CompanionAssetRole.TODAY_WAVE))
    }
}
