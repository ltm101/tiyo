package com.koyo.screenwarden

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TiyoAgentVisionCapabilityTest {
    @Test
    fun detectsNativeVisionModelsWithoutMarkingTextModels() {
        assertTrue(TiyoAgentConfig.supportsVision("deepseek-v4-flash-vision-exp"))
        assertTrue(TiyoAgentConfig.supportsVision("some-vl-model"))
        assertTrue(TiyoAgentConfig.supportsVision("gpt-4o-mini"))
        assertFalse(TiyoAgentConfig.supportsVision("deepseek-v4-flash"))
    }
}
