package com.koyo.screenwarden

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TiyoRuntimeShellPolicyTest {
    @Test
    fun agentAlwaysUsesTrustedAndroidShell() {
        val env = TiyoRuntime.agentEnvironment(
            runtimePrefix = File("/data/user/0/com.koyo.screenwarden/files/usr"),
            inheritedPath = "/data/user/0/com.koyo.screenwarden/files/usr/bin:/system/bin:/apex/com.android.runtime/bin"
        )

        assertEquals("/system/bin/sh", env["SHELL"])
        assertEquals("/system/bin/sh", env["TIYO_SHELL"])
        assertEquals("/system/bin/sh", env["COOMI_SHELL"])
        assertEquals("/system/bin:/apex/com.android.runtime/bin", env["PATH"])
        assertFalse(env["PATH"].orEmpty().contains("/data/"))
        assertNull(env["LD_LIBRARY_PATH"])
    }

    @Test
    fun missingRuntimeStillHasCompleteShellEnvironment() {
        val env = TiyoRuntime.agentEnvironment(
            runtimePrefix = null,
            inheritedPath = null
        )

        assertEquals("/system/bin", env["PATH"])
        assertEquals("/system/bin/sh", env["SHELL"])
        assertEquals("/system/bin/sh", env["TIYO_SHELL"])
        assertEquals("/system/bin/sh", env["COOMI_SHELL"])
        assertNull(env["TIYO_RUNTIME_PREFIX"])
    }

    @Test
    fun runtimePrefixIsMetadataOnly() {
        val prefix = File("/data/user/0/com.koyo.screenwarden/files/usr")
        val env = TiyoRuntime.agentEnvironment(prefix, "/vendor/bin:/system/bin")

        assertEquals(prefix.absolutePath, env["TIYO_RUNTIME_PREFIX"])
        assertEquals("/vendor/bin:/system/bin", env["PATH"])
        assertFalse(env.containsKey("TERMUX_PREFIX"))
    }
}
