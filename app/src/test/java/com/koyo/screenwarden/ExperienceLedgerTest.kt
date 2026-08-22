package com.koyo.screenwarden

import com.koyo.screenwarden.enuman.experience.ExperienceKind
import com.koyo.screenwarden.enuman.experience.ExperienceLedgerStore
import com.koyo.screenwarden.enuman.experience.ExperiencePrivacy
import com.koyo.screenwarden.enuman.experience.ExperienceRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExperienceLedgerTest {
    private val now = 1_800_000_000_000L

    @Test
    fun duplicateAppendIsIdempotent() {
        val dir = File.createTempFile("ledger", "").apply { delete() }
        try {
            val store = ExperienceLedgerStore(dir)
            val record = record("exp_1", "koyo", "PRESENCE", "notification")
            assertTrue(store.append(record))
            assertFalse(store.append(record))
            assertEquals(1, store.count())
            assertEquals(listOf("exp_1"), store.records().map { it.id })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun companionRecordsAreIsolatedByDirectory() {
        val a = File.createTempFile("ledger_a", "").apply { delete() }
        val b = File.createTempFile("ledger_b", "").apply { delete() }
        try {
            val storeA = ExperienceLedgerStore(a)
            val storeB = ExperienceLedgerStore(b)
            storeA.append(record("exp_a", "koyo", "PRESENCE", "share"))
            storeB.append(record("exp_b", "custom", "USER_MESSAGE", "chat"))
            assertEquals(listOf("exp_a"), storeA.records().map { it.id })
            assertEquals(listOf("exp_b"), storeB.records().map { it.id })
        } finally {
            a.deleteRecursively()
            b.deleteRecursively()
        }
    }

    @Test
    fun correctionIsAppendedNotInPlace() {
        val dir = File.createTempFile("ledger", "").apply { delete() }
        try {
            val store = ExperienceLedgerStore(dir)
            val original = record("exp_1", "koyo", "PRESENCE", "share")
            store.append(original)
            val corrected = original.copy(
                id = "exp_1_correction",
                kind = ExperienceKind.CORRECTION,
                correctionOf = original.id
            )
            store.append(corrected)
            val records = store.records()
            assertEquals(2, records.size)
            assertEquals("exp_1", records[0].id)
            assertEquals("exp_1_correction", records[1].id)
            assertEquals("exp_1", records[1].correctionOf)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun boundedRetentionKeepsNewestRecords() {
        val dir = File.createTempFile("ledger", "").apply { delete() }
        try {
            val store = ExperienceLedgerStore(dir, maxRecords = 3)
            repeat(5) { index ->
                store.append(record("exp_$index", "koyo", "PRESENCE", "share"))
            }
            val ids = store.records().map { it.id }
            assertEquals(listOf("exp_2", "exp_3", "exp_4"), ids)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptedLedgerFallsBackToEmptyWithoutCrash() {
        val dir = File.createTempFile("ledger", "").apply { delete(); mkdirs() }
        try {
            File(dir, "experience.json").writeText("{ not valid json !!!")
            val store = ExperienceLedgerStore(dir)
            assertTrue(store.records().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun jsonRoundTripKeepsCoreFields() {
        val original = record("exp_rt", "koyo", "DEEP_SLEEP", "enuman")
        val restored = ExperienceRecord.fromJson(original.toJson())
        assertTrue(restored != null)
        assertEquals(original.id, restored!!.id)
        assertEquals(original.companionId, restored.companionId)
        assertEquals(original.kind, restored.kind)
        assertEquals(original.privacyClass, restored.privacyClass)
        assertEquals(original.causalRefs, restored.causalRefs)
        assertEquals(original.payloadDigest, restored.payloadDigest)
        assertEquals(original.summary, restored.summary)
    }

    @Test
    fun passiveRecordNeverSerializesTextBody() {
        val passive = ExperienceRecord(
            id = "exp_passive",
            companionId = "koyo",
            kind = ExperienceKind.PRESENCE,
            occurredAt = now,
            recordedAt = now,
            sourceChannel = "NOTIFICATION",
            modality = "TEXT",
            explicitUserAction = false,
            privacyClass = ExperiencePrivacy.PASSIVE,
            summary = null
        )
        val json = passive.toJson().toString()
        assertFalse(json.contains("private notification body"))
        assertFalse(JSONObject(json).has("summary"))
    }

    private fun record(id: String, companionId: String, kind: String, sourceChannel: String): ExperienceRecord =
        ExperienceRecord(
            id = id,
            companionId = companionId,
            kind = ExperienceKind.valueOf(kind),
            occurredAt = now,
            recordedAt = now,
            sourceChannel = sourceChannel,
            modality = "text",
            explicitUserAction = true,
            privacyClass = ExperiencePrivacy.EXPLICIT,
            causalRefs = listOf("cause_$id"),
            payloadDigest = "abc123",
            summary = if (sourceChannel == "notification") null else "limited summary"
        )
}
