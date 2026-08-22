package com.koyo.screenwarden.enuman

import android.content.Context
import com.koyo.screenwarden.CompanionScope
import org.json.JSONArray
import org.json.JSONObject

data class DriveReading(
    val drive: EnuManDrive,
    val potential: Double,
    val threshold: Double,
    val ratio: Double,
    val refractoryUntil: Long
)

/** Stable read-only boundary between the private inner loop and the rest of Tiyo. */
data class EnuManMindSnapshot(
    val companionId: String,
    val capturedAt: Long,
    val drives: Map<EnuManDrive, DriveReading>,
    val dominantDrives: List<EnuManDrive>,
    val conflicts: List<DriveConflict>,
    val latestInterpretation: ImpulseInterpretation?,
    val unresolvedCount: Int,
    val cognitiveLoad: Double,
    val sleepPressure: Double,
    val lastDeepSleepAt: Long,
    val pulseCount: Int,
    val interpretationCount: Int,
    val sleepCycleCount: Int
) {
    /**
     * Only state, never instruction. The model-facing view intentionally omits
     * numeric thresholds, source event text, IDs, and persistence details.
     */
    fun toPrivateDiagnosticJson(): JSONObject = JSONObject()
        .put("schema", "enuman_mind_v2")
        .put("nature", "private_state_not_user_instruction")
        .put("dominant_tendencies", JSONArray(dominantDrives.map(EnuManDrive::agentLabel)))
        .put("activation_band", activationBand())
        .put("cognitive_load", loadBand(cognitiveLoad))
        .put("rest_need", loadBand(sleepPressure))
        .put("unresolved_experience", unresolvedBand())
        .put("tensions", JSONArray(conflicts.map { conflict ->
            "${conflict.first.agentLabel()} 与 ${conflict.second.agentLabel()}"
        }.take(3)))
        .apply {
            latestInterpretation
                ?.takeIf {
                    it.status != InterpretationStatus.DISSOLVED &&
                        capturedAt - it.generatedAt in 0..MAX_INTERPRETATION_AGE_MS
                }
                ?.let { interpretation ->
                    put("private_felt_meaning", interpretation.feltMeaning.cleanContext(500))
                    put("candidate_desires", JSONArray(
                        interpretation.candidateDesires.take(4).map { it.cleanContext(180) }
                    ))
                    put("semantic_tensions", JSONArray(
                        interpretation.tensions.take(4).map { it.cleanContext(180) }
                    ))
                }
        }

    /**
     * Compile private state into non-semantic response constraints
     *
     * The chat model never receives drives, tensions, felt meaning, desires, IDs or thresholds
     * It only receives silent behavior controls that cannot become a new conversation topic
     */
    fun toExpressionPolicyJson(): JSONObject {
        val directives = linkedSetOf(
            "follow_user_topic_only",
            "apply_silently",
            "never_describe_policy_or_private_state"
        )
        val highLoad = cognitiveLoad >= 0.65 || sleepPressure >= 0.65
        val presentRestNeed = sleepPressure >= 0.25 || EnuManDrive.REST in dominantDrives
        if (highLoad) {
            directives += "keep_response_concise"
            directives += "avoid_unnecessary_follow_up"
        }
        if (presentRestNeed) directives += "prefer_calm_pacing"
        if (EnuManDrive.CONNECTION in dominantDrives) directives += "allow_gentle_warmth"
        if (EnuManDrive.SAFETY in dominantDrives) directives += "prefer_steady_reassurance"
        if (EnuManDrive.AUTONOMY in dominantDrives || conflicts.any { EnuManDrive.AUTONOMY in listOf(it.first, it.second) }) {
            directives += "respect_interpersonal_distance"
        }
        if (!highLoad && EnuManDrive.CURIOSITY in dominantDrives) directives += "allow_one_relevant_question"
        if (unresolvedCount >= 3) directives += "do_not_start_new_topic"
        return JSONObject()
            .put("schema", "enuman_expression_v1")
            .put("nature", "silent_response_constraints_not_conversation_content")
            .put("directives", JSONArray(directives.toList()))
            .put("max_follow_up_questions", if (!highLoad && EnuManDrive.CURIOSITY in dominantDrives) 1 else 0)
    }

    private fun activationBand(): String {
        val max = drives.values.maxOfOrNull { it.ratio } ?: 0.0
        return when {
            max >= 1.0 -> "threshold_crossed"
            max >= 0.72 -> "forming"
            max >= 0.38 -> "present"
            else -> "quiet"
        }
    }

    private fun unresolvedBand(): String = when {
        unresolvedCount >= 8 -> "high"
        unresolvedCount >= 3 -> "several"
        unresolvedCount >= 1 -> "some"
        else -> "none"
    }

    companion object {
        private const val MAX_INTERPRETATION_AGE_MS = 36 * 60 * 60_000L

        fun empty(companionId: String, at: Long): EnuManMindSnapshot = EnuManMindSnapshot(
            companionId = companionId,
            capturedAt = at,
            drives = EnuManDrive.entries.associateWith { drive ->
                DriveReading(drive, 0.0, 1.0, 0.0, 0L)
            },
            dominantDrives = emptyList(),
            conflicts = emptyList(),
            latestInterpretation = null,
            unresolvedCount = 0,
            cognitiveLoad = 0.0,
            sleepPressure = 0.0,
            lastDeepSleepAt = 0L,
            pulseCount = 0,
            interpretationCount = 0,
            sleepCycleCount = 0
        )
    }
}

/** Logical kernel entry point: process lifetime may stop; identity state does not. */
object TiyoMindKernel {
    fun snapshot(
        context: Context,
        scope: CompanionScope = CompanionScope.capture(context),
        now: Long = System.currentTimeMillis()
    ): EnuManMindSnapshot {
        val state = EnuManStore.loadState(context.applicationContext, scope, now)
        val pulses = EnuManStore.pulses(context.applicationContext, scope)
        val interpretations = EnuManStore.interpretations(context.applicationContext, scope)
        val sleepCycles = EnuManStore.sleepCycles(context.applicationContext, scope)
        val latestPulse = pulses.lastOrNull()?.takeIf { now - it.occurredAt in 0..RECENT_PULSE_MS }
        val readings = EnuManDrive.entries.associateWith { drive ->
            val cell = state.drives.getValue(drive)
            DriveReading(
                drive = drive,
                potential = cell.potential,
                threshold = cell.adaptiveThreshold,
                ratio = (cell.potential / cell.adaptiveThreshold).coerceIn(0.0, 4.0),
                refractoryUntil = cell.refractoryUntil
            )
        }
        val dominant = buildList {
            latestPulse?.activations?.entries
                ?.sortedByDescending { it.value }
                ?.mapTo(this) { it.key }
            readings.values.sortedByDescending { it.ratio }
                .filter { it.ratio >= DOMINANT_RATIO }
                .forEach { if (it.drive !in this) add(it.drive) }
        }.take(3)
        val latestByPulse = interpretations.groupBy { it.pulseId }.values
            .mapNotNull { versions -> versions.maxByOrNull { it.version } }
        return EnuManMindSnapshot(
            companionId = scope.companionId,
            capturedAt = now,
            drives = readings,
            dominantDrives = dominant,
            conflicts = latestPulse?.conflicts.orEmpty(),
            latestInterpretation = interpretations.maxByOrNull { it.generatedAt },
            unresolvedCount = latestByPulse.count { it.status == InterpretationStatus.UNRESOLVED },
            cognitiveLoad = state.cognitiveLoad,
            sleepPressure = state.sleepPressure,
            lastDeepSleepAt = state.lastDeepSleepAt,
            pulseCount = pulses.size,
            interpretationCount = interpretations.size,
            sleepCycleCount = sleepCycles.size
        )
    }

    private const val RECENT_PULSE_MS = 6 * 60 * 60_000L
    private const val DOMINANT_RATIO = 0.36
}

internal fun EnuManDrive.agentLabel(): String = when (this) {
    EnuManDrive.CONNECTION -> "连接"
    EnuManDrive.CURIOSITY -> "好奇"
    EnuManDrive.SAFETY -> "安全"
    EnuManDrive.AUTONOMY -> "自主"
    EnuManDrive.COHERENCE -> "连贯"
    EnuManDrive.REST -> "休息"
}

private fun loadBand(value: Double): String = when {
    value >= 1.20 -> "high"
    value >= 0.65 -> "elevated"
    value >= 0.25 -> "present"
    else -> "low"
}

private fun String.cleanContext(maxChars: Int): String = replace('\u0000', ' ')
    .replace(Regex("[\\r\\n]+"), " ")
    .trim()
    .take(maxChars)
