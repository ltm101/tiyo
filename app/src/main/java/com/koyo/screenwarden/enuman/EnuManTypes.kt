package com.koyo.screenwarden.enuman

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class EnuManDrive {
    CONNECTION,
    CURIOSITY,
    SAFETY,
    AUTONOMY,
    COHERENCE,
    REST
}

data class DriveCell(
    val potential: Double = 0.0,
    val adaptiveThreshold: Double = 1.0,
    val lastUpdatedAt: Long,
    val refractoryUntil: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject()
        .put("potential", potential)
        .put("adaptive_threshold", adaptiveThreshold)
        .put("last_updated_at", lastUpdatedAt)
        .put("refractory_until", refractoryUntil)

    companion object {
        fun fromJson(json: JSONObject, fallbackAt: Long): DriveCell = DriveCell(
            potential = json.optDouble("potential", 0.0).finiteOr(0.0),
            adaptiveThreshold = json.optDouble("adaptive_threshold", 1.0)
                .finiteOr(1.0)
                .coerceIn(0.45, 2.5),
            lastUpdatedAt = json.optLong("last_updated_at", fallbackAt).coerceAtLeast(0L),
            refractoryUntil = json.optLong("refractory_until", 0L).coerceAtLeast(0L)
        )
    }
}

data class EnuManState(
    val schemaVersion: Int = SCHEMA_VERSION,
    val drives: Map<EnuManDrive, DriveCell>,
    val cognitiveLoad: Double = 0.0,
    val sleepPressure: Double = 0.0,
    val lastTickAt: Long,
    val lastDeepSleepAt: Long = 0L,
    val recentSignalIds: List<String> = emptyList(),
    val learnedAssociations: Map<String, Double> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", schemaVersion)
        .put("drives", JSONObject().apply {
            EnuManDrive.entries.forEach { drive ->
                put(drive.name, drives.getValue(drive).toJson())
            }
        })
        .put("cognitive_load", cognitiveLoad)
        .put("sleep_pressure", sleepPressure)
        .put("last_tick_at", lastTickAt)
        .put("last_deep_sleep_at", lastDeepSleepAt)
        .put("recent_signal_ids", JSONArray(recentSignalIds.takeLast(MAX_RECENT_SIGNALS)))
        .put("learned_associations", JSONObject().apply {
            learnedAssociations.toSortedMap().forEach { (key, value) ->
                put(key.take(MAX_ASSOCIATION_KEY_CHARS), value.coerceIn(-MAX_LEARNED_BIAS, MAX_LEARNED_BIAS))
            }
        })

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_RECENT_SIGNALS = 96
        const val MAX_ASSOCIATIONS = 128
        const val MAX_ASSOCIATION_KEY_CHARS = 120
        const val MAX_LEARNED_BIAS = 0.40

        fun initial(at: Long): EnuManState = EnuManState(
            drives = EnuManDrive.entries.associateWith { DriveCell(lastUpdatedAt = at) },
            lastTickAt = at
        )

        fun fromJson(json: JSONObject, fallbackAt: Long): EnuManState {
            val drivesJson = json.optJSONObject("drives") ?: JSONObject()
            val drives = EnuManDrive.entries.associateWith { drive ->
                drivesJson.optJSONObject(drive.name)?.let { DriveCell.fromJson(it, fallbackAt) }
                    ?: DriveCell(lastUpdatedAt = fallbackAt)
            }
            val associationsJson = json.optJSONObject("learned_associations") ?: JSONObject()
            val associations = associationsJson.keys().asSequence()
                .take(MAX_ASSOCIATIONS)
                .associateWith { key ->
                    associationsJson.optDouble(key, 0.0).finiteOr(0.0)
                        .coerceIn(-MAX_LEARNED_BIAS, MAX_LEARNED_BIAS)
                }
            return EnuManState(
                schemaVersion = SCHEMA_VERSION,
                drives = drives,
                cognitiveLoad = json.optDouble("cognitive_load", 0.0).finiteOr(0.0).coerceIn(0.0, 2.0),
                sleepPressure = json.optDouble("sleep_pressure", 0.0).finiteOr(0.0).coerceIn(0.0, 2.0),
                lastTickAt = json.optLong("last_tick_at", fallbackAt).coerceAtLeast(0L),
                lastDeepSleepAt = json.optLong("last_deep_sleep_at", 0L).coerceAtLeast(0L),
                recentSignalIds = json.optJSONArray("recent_signal_ids").toStringList(MAX_RECENT_SIGNALS),
                learnedAssociations = associations
            )
        }
    }
}

data class EnuManSignal(
    val id: String = UUID.randomUUID().toString(),
    val occurredAt: Long,
    val source: String,
    val excitation: Map<EnuManDrive, Double> = emptyMap(),
    val inhibition: Map<EnuManDrive, Double> = emptyMap(),
    val salience: Double = 1.0,
    val causeRefs: List<String> = emptyList()
)

data class DriveConflict(
    val first: EnuManDrive,
    val second: EnuManDrive,
    val strength: Double
) {
    fun toJson(): JSONObject = JSONObject()
        .put("first", first.name)
        .put("second", second.name)
        .put("strength", strength)

    companion object {
        fun fromJson(json: JSONObject): DriveConflict? {
            val first = json.enumValue<EnuManDrive>("first") ?: return null
            val second = json.enumValue<EnuManDrive>("second") ?: return null
            return DriveConflict(
                first,
                second,
                json.optDouble("strength", 0.0).finiteOr(0.0).coerceIn(0.0, 2.0)
            )
        }
    }
}

data class PreSemanticPulse(
    val id: String = UUID.randomUUID().toString(),
    val occurredAt: Long,
    val activations: Map<EnuManDrive, Double>,
    val conflicts: List<DriveConflict>,
    val causeRefs: List<String>
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("occurred_at", occurredAt)
        .put("activations", activations.toDriveJson())
        .put("conflicts", JSONArray().apply { conflicts.forEach { put(it.toJson()) } })
        .put("cause_refs", JSONArray(causeRefs.takeLast(MAX_CAUSE_REFS)))

    companion object {
        const val MAX_CAUSE_REFS = 16

        fun fromJson(json: JSONObject): PreSemanticPulse? {
            val id = json.safeId("id") ?: return null
            val at = json.optLong("occurred_at", 0L).takeIf { it > 0L } ?: return null
            val activations = json.optJSONObject("activations").toDriveMap()
            if (activations.isEmpty()) return null
            val conflictsArray = json.optJSONArray("conflicts") ?: JSONArray()
            val conflicts = buildList {
                for (index in 0 until conflictsArray.length()) {
                    conflictsArray.optJSONObject(index)?.let(DriveConflict::fromJson)?.let(::add)
                }
            }
            return PreSemanticPulse(
                id = id,
                occurredAt = at,
                activations = activations,
                conflicts = conflicts,
                causeRefs = json.optJSONArray("cause_refs").toStringList(MAX_CAUSE_REFS)
            )
        }
    }
}

enum class InterpretationStatus {
    UNRESOLVED,
    REFLECTED,
    DISSOLVED
}

data class ImpulseInterpretation(
    val id: String = UUID.randomUUID().toString(),
    val pulseId: String,
    val parentInterpretationId: String? = null,
    val version: Int = 1,
    val generatedAt: Long,
    val feltMeaning: String,
    val candidateDesires: List<String>,
    val tensions: List<String>,
    val confidence: Double,
    val status: InterpretationStatus = InterpretationStatus.UNRESOLVED
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("pulse_id", pulseId)
        .putOpt("parent_interpretation_id", parentInterpretationId)
        .put("version", version)
        .put("generated_at", generatedAt)
        .put("felt_meaning", feltMeaning.take(MAX_MEANING_CHARS))
        .put("candidate_desires", JSONArray(candidateDesires.take(MAX_LIST_ITEMS).map { it.take(MAX_ITEM_CHARS) }))
        .put("tensions", JSONArray(tensions.take(MAX_LIST_ITEMS).map { it.take(MAX_ITEM_CHARS) }))
        .put("confidence", confidence.coerceIn(0.0, 1.0))
        .put("status", status.name)

    companion object {
        const val MAX_MEANING_CHARS = 1_200
        const val MAX_ITEM_CHARS = 320
        const val MAX_LIST_ITEMS = 8

        fun fromJson(json: JSONObject): ImpulseInterpretation? {
            val id = json.safeId("id") ?: return null
            val pulseId = json.safeId("pulse_id") ?: return null
            return ImpulseInterpretation(
                id = id,
                pulseId = pulseId,
                parentInterpretationId = json.optString("parent_interpretation_id")
                    .takeIf { it.matches(SAFE_ID_REGEX) },
                version = json.optInt("version", 1).coerceIn(1, 10_000),
                generatedAt = json.optLong("generated_at", 0L).takeIf { it > 0L } ?: return null,
                feltMeaning = json.optString("felt_meaning").take(MAX_MEANING_CHARS),
                candidateDesires = json.optJSONArray("candidate_desires").toStringList(MAX_LIST_ITEMS)
                    .map { it.take(MAX_ITEM_CHARS) },
                tensions = json.optJSONArray("tensions").toStringList(MAX_LIST_ITEMS)
                    .map { it.take(MAX_ITEM_CHARS) },
                confidence = json.optDouble("confidence", 0.0).finiteOr(0.0).coerceIn(0.0, 1.0),
                status = json.enumValue<InterpretationStatus>("status") ?: InterpretationStatus.UNRESOLVED
            )
        }
    }
}

enum class ReflectionKind {
    SHORT_REFLECTION,
    DEEP_SLEEP
}

data class SleepCycle(
    val id: String = UUID.randomUUID().toString(),
    val kind: ReflectionKind,
    val startedAt: Long,
    val completedAt: Long,
    val replayedPulseIds: List<String>,
    val producedInterpretationIds: List<String>,
    val appliedPlasticity: Map<String, Double>
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind.name)
        .put("started_at", startedAt)
        .put("completed_at", completedAt)
        .put("replayed_pulse_ids", JSONArray(replayedPulseIds.take(24)))
        .put("produced_interpretation_ids", JSONArray(producedInterpretationIds.take(24)))
        .put("applied_plasticity", JSONObject(appliedPlasticity))

    companion object {
        fun fromJson(json: JSONObject): SleepCycle? {
            val id = json.safeId("id") ?: return null
            return SleepCycle(
                id = id,
                kind = json.enumValue<ReflectionKind>("kind") ?: return null,
                startedAt = json.optLong("started_at", 0L).takeIf { it > 0L } ?: return null,
                completedAt = json.optLong("completed_at", 0L).takeIf { it > 0L } ?: return null,
                replayedPulseIds = json.optJSONArray("replayed_pulse_ids").toStringList(24),
                producedInterpretationIds = json.optJSONArray("produced_interpretation_ids").toStringList(24),
                appliedPlasticity = json.optJSONObject("applied_plasticity").toStringDoubleMap()
            )
        }
    }
}

internal val SAFE_ID_REGEX = Regex("[A-Za-z0-9_-]{1,120}")

internal inline fun <reified T : Enum<T>> JSONObject.enumValue(key: String): T? =
    runCatching { enumValueOf<T>(optString(key)) }.getOrNull()

internal fun JSONObject.safeId(key: String): String? =
    optString(key).takeIf { it.matches(SAFE_ID_REGEX) }

internal fun JSONArray?.toStringList(limit: Int): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until minOf(length(), limit)) {
            optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
}

internal fun JSONObject?.toDriveMap(): Map<EnuManDrive, Double> {
    if (this == null) return emptyMap()
    return EnuManDrive.entries.mapNotNull { drive ->
        if (!has(drive.name)) null
        else drive to optDouble(drive.name, 0.0).finiteOr(0.0).coerceIn(0.0, 4.0)
    }.toMap()
}

internal fun Map<EnuManDrive, Double>.toDriveJson(): JSONObject = JSONObject().apply {
    forEach { (drive, value) -> put(drive.name, value.finiteOr(0.0).coerceIn(0.0, 4.0)) }
}

internal fun JSONObject?.toStringDoubleMap(limit: Int = EnuManState.MAX_ASSOCIATIONS): Map<String, Double> {
    if (this == null) return emptyMap()
    return keys().asSequence().take(limit).associateWith { key ->
        optDouble(key, 0.0).finiteOr(0.0)
    }
}

internal fun Double.finiteOr(fallback: Double): Double = if (isFinite()) this else fallback
