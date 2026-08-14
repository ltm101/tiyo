package com.koyo.screenwarden

import org.junit.Assert.assertEquals
import org.junit.Test

class TiyoExtensionStoreTest {
    @Test
    fun commandArgumentsKeepQuotedValuesTogether() {
        assertEquals(
            listOf("-y", "@scope/server", "--label", "Tiyo 的工具"),
            TiyoExtensionStore.parseCommandArgs("-y @scope/server --label \"Tiyo 的工具\"")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun commandArgumentsRejectOpenQuote() {
        TiyoExtensionStore.parseCommandArgs("--label \"没有结尾")
    }

    @Test
    fun ageBoundaryLabelsKeepFourGroupsAndMoveElderToSixty() {
        assertEquals("30-60岁", UserPrefs.AgeGroup.MIDDLE.label)
        assertEquals("60岁及以上", UserPrefs.AgeGroup.ELDER.label)
        assertEquals(4, UserPrefs.AgeGroup.entries.size)
    }
}
