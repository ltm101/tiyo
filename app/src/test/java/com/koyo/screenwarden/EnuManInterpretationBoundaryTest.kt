package com.koyo.screenwarden

import com.koyo.screenwarden.enuman.EnuManContextSanitizer
import com.koyo.screenwarden.enuman.EnuManInterpretationParser
import com.koyo.screenwarden.presence.PresenceChannel
import com.koyo.screenwarden.presence.PresenceDirection
import com.koyo.screenwarden.presence.PresenceEvent
import com.koyo.screenwarden.presence.PresenceModality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnuManInterpretationBoundaryTest {
    @Test
    fun enumanPackageHasNoDirectExpressionDependency() {
        val roots = listOf(
            File("src/main/java/com/koyo/screenwarden/enuman"),
            File("app/src/main/java/com/koyo/screenwarden/enuman")
        )
        val root = roots.firstOrNull(File::isDirectory)
        assertNotNull("EnuMan source directory must be visible to the architecture test", root)
        val source = root!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        val forbiddenDependencies = listOf(
            "import com.koyo.screenwarden.ProactiveMessenger",
            "import com.koyo.screenwarden.ActionExecutor",
            "import android.app.Notification",
            "import android.accessibilityservice.AccessibilityService",
            ".startActivity("
        )

        forbiddenDependencies.forEach { forbidden ->
            assertFalse("EnuMan must not depend on outward capability: $forbidden", source.contains(forbidden))
        }
    }

    @Test
    fun passiveNotificationTextIsRedactedBeforeModelContext() {
        val event = PresenceEvent(
            id = "notification_1",
            channel = PresenceChannel.NOTIFICATION,
            direction = PresenceDirection.OBSERVED,
            modality = PresenceModality.TEXT,
            text = "private notification body",
            explicitUserAction = false,
            occurredAt = 1_800_000_000_000L
        )

        val json = EnuManContextSanitizer.causeJson(event)

        assertFalse(json.has("shared_text"))
        assertFalse(json.toString().contains("private notification body"))
    }

    @Test
    fun explicitShareMayContributeText() {
        val event = PresenceEvent(
            id = "share_1",
            channel = PresenceChannel.SYSTEM_SHARE,
            direction = PresenceDirection.TO_COMPANION,
            modality = PresenceModality.TEXT,
            text = "look at this",
            explicitUserAction = true,
            occurredAt = 1_800_000_000_000L
        )

        assertEquals("look at this", EnuManContextSanitizer.causeJson(event).getString("shared_text"))
    }

    @Test
    fun parserRejectsActionBearingOutput() {
        val raw = """{"felt_meaning":"想靠近","action":"send","confidence":0.8}"""

        assertNull(EnuManInterpretationParser.parse(raw, "pulse_1", null, 1L, false))
    }

    @Test
    fun deepSleepCreatesVersionedPrivateDescendantAndClampsPlasticity() {
        val first = EnuManInterpretationParser.parse(
            """{"felt_meaning":"有一点没理解","candidate_desires":[],"tensions":["靠近与安静"],"confidence":0.4,"resolution":"unresolved","plasticity":{}}""",
            "pulse_1",
            null,
            10L,
            false
        )!!.interpretation
        val second = EnuManInterpretationParser.parse(
            """{"felt_meaning":"也许需要先安静地理解","candidate_desires":["留出一点安静"],"tensions":[],"confidence":0.7,"resolution":"reflected","plasticity":{"drive:REST":9,"invalid:key":1}}""",
            "pulse_1",
            first,
            20L,
            true
        )

        assertNotNull(second)
        assertEquals(first.id, second!!.interpretation.parentInterpretationId)
        assertEquals(2, second.interpretation.version)
        assertEquals(0.03, second.proposedPlasticity.getValue("drive:REST"), 0.000001)
        assertTrue("invalid:key" !in second.proposedPlasticity)
    }
}
