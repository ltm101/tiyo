package com.koyo.screenwarden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionGenerationPlanTest {
    @Test fun activationPackCoversEveryVisibleSurface() {
        val required = CompanionGenerationPlan.phaseOneAssets
            .filter(CompanionAssetSpec::requiredForActivation)
            .map(CompanionAssetSpec::role)
            .toSet()
        assertTrue(CompanionAssetRole.CHAT_PORTRAIT in required)
        assertTrue(CompanionAssetRole.TODAY_IDLE in required)
        assertTrue(CompanionAssetRole.TODAY_WAVE in required)
        assertTrue(CompanionAssetRole.CHAT_PRONE in required)
        assertTrue(CompanionAssetRole.ROOM_IDLE in required)
        assertTrue(CompanionAssetRole.ROOM_SPEAKING in required)
        assertTrue(CompanionAssetRole.DESK_IDLE in required)
        assertTrue(CompanionAssetRole.SHELF_IDLE in required)
        assertFalse(CompanionAssetRole.STICKER_SHEET in required)
    }

    @Test fun animatedSurfacesHaveSixFrames() {
        val animated = CompanionGenerationPlan.phaseOneAssets.filter { it.frameGrid != null }
        assertEquals(
            setOf(CompanionAssetRole.TODAY_IDLE, CompanionAssetRole.TODAY_WAVE, CompanionAssetRole.CHAT_PRONE, CompanionAssetRole.STICKER_SHEET),
            animated.map(CompanionAssetSpec::role).toSet()
        )
        animated.filterNot { it.role == CompanionAssetRole.STICKER_SHEET }
            .forEach { assertEquals(6, it.frameGrid?.frameCount) }
    }
}
