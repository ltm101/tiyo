package com.koyo.screenwarden

import org.junit.Assert.assertFalse
import org.junit.Test

class GreetingComposerTest {
    @Test
    fun todayCopyNeverPushesUserToResumeOldConversation() {
        val greeting = GreetingComposer.compose(
            GreetingComposer.Input(
                hour = 16,
                weather = null,
                recentChatTitle = "旧项目",
                recentChatAgeHours = 4.0
            )
        )
        val suggestion = SuggestionComposer.compose(
            SuggestionComposer.Input(
                hour = 16,
                recentChatTitle = "旧项目",
                recentChatAgeHours = 4.0
            )
        )
        val text = listOfNotNull(greeting.detail, suggestion).joinToString(" ")

        assertFalse(text.contains("上次说到"))
        assertFalse(text.contains("要不要继续"))
    }
}
