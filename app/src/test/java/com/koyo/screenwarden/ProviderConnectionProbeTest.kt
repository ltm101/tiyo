package com.koyo.screenwarden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConnectionProbeTest {
    @Test
    fun endpointNormalizesTrailingSlashWithoutChangingPath() {
        assertEquals("https://example.com/v1/models", ProviderConnectionProbe.modelsEndpoint("https://example.com/v1/"))
        assertNull(ProviderConnectionProbe.modelsEndpoint("example.com/v1"))
    }

    @Test
    fun parserAcceptsOpenAiDataShapeAndDeduplicatesIds() {
        val models = ProviderConnectionProbe.parseModelIds(
            """{"data":[{"id":"model-b"},{"id":"model-a"},{"id":"model-a"}]}"""
        )
        assertEquals(listOf("model-a", "model-b"), models)
    }

    @Test
    fun parserFailsClosedOnMalformedResponse() {
        assertTrue(ProviderConnectionProbe.parseModelIds("not json").isEmpty())
    }
}
