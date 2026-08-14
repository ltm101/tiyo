package com.koyo.screenwarden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserNameRulesTest {
    @Test
    fun normalizeNameCollapsesWhitespaceAndLimitsLength() {
        assertEquals("小 天", UserPrefs.normalizeName("  小\n\t天  "))
        assertEquals(20, UserPrefs.normalizeName("一".repeat(30)).length)
    }

    @Test
    fun runtimeRulesCarryCurrentNameWithoutOverCallingIt() {
        val rules = PersonaFragment.runtimeRulesFor(UserPrefs.AgeGroup.YOUTH, "小天")

        assertTrue(rules.contains("用户希望被称呼为「小天」"))
        assertTrue(rules.contains("不要改回旧称呼"))
        assertTrue(rules.contains("不要每句话都刻意喊名字"))
    }
}
