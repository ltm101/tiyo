package com.koyo.screenwarden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

class TiyoMemoryPolicyTest {

    @Test
    fun companionScopeNormalizesIdentityAndKeepsBuiltInNamespace() {
        val builtIn = CompanionScope.of("KOYO", "可又")
        val migratedLegacy = CompanionScope.of("TIYO", "Tiyo")
        val custom = CompanionScope.of("../Tian", " 天 ")

        assertEquals("tiyo_memory", builtIn.namespaced("tiyo_memory"))
        assertEquals("koyo", migratedLegacy.companionId)
        assertEquals("tiyo_memory", migratedLegacy.namespaced("tiyo_memory"))
        assertEquals("tian", custom.companionId)
        assertEquals("天", custom.displayName)
        assertEquals("tiyo_memory_tian", custom.namespaced("tiyo_memory"))
    }

    @Test
    fun semanticKeyKeepsFilenameStableWhenFactChanges() {
        val at = OffsetDateTime.of(2026, 8, 14, 12, 0, 0, 0, ZoneOffset.UTC)
        val first = TiyoAtomicMemory.filenameFor(
            TiyoAtomicMemory.TYPE_PERSONA,
            "user.preference.drink",
            "用户喜欢咖啡",
            at
        )
        val changed = TiyoAtomicMemory.filenameFor(
            TiyoAtomicMemory.TYPE_PERSONA,
            "user.preference.drink",
            "用户现在更喜欢茶",
            at.plusDays(3)
        )

        assertEquals(first, changed)
        assertEquals("persona-user-preference-drink.md", first)
    }

    @Test
    fun parserAcceptsUpdatesAndDistinguishesMalformedOutput() {
        val parsed = TiyoMemoryExtractor.parseOrNull(
            """[{"key":"project.tiyo.status","content":"tiyo 正在修复记忆作用域","type":"episodic","priority":90}]"""
        )

        assertNotNull(parsed)
        assertEquals(1, parsed?.size)
        assertEquals("project.tiyo.status", parsed?.single()?.key)
        assertTrue(TiyoMemoryExtractor.parseOrNull("[]")?.isEmpty() == true)
        assertNull(TiyoMemoryExtractor.parseOrNull("不是 JSON"))
    }
}
