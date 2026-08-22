package com.koyo.screenwarden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TiyoAgentRuntimeCommandTest {
    @Test
    fun runtimeCommandPassesRandomTokenAsDedicatedArgument() {
        val command = buildTiyoAgentRuntimeCommand(
            binary = File("/app/libtiyo_agent.so"),
            home = File("/private/home"),
            workspace = File("/public/workspace"),
            port = 34177,
            authToken = "random-test-token",
            staticDir = File("/private/home/static")
        )

        val tokenIndex = command.indexOf("--token")
        assertTrue(tokenIndex >= 0)
        assertEquals("random-test-token", command[tokenIndex + 1])
        assertFalse(command.joinToString(" ").contains("--token  --static-dir"))
    }
}
