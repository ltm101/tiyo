package com.koyo.screenwarden.enuman

import android.content.Context
import com.koyo.screenwarden.CompanionScope
import com.koyo.screenwarden.presence.PresenceEventStore
import java.util.Locale

data class EnuManReplayResult(
    val finalState: EnuManState,
    val pulses: List<PreSemanticPulse>,
    val processedSignalCount: Int,
    val simulatedFrom: Long,
    val simulatedUntil: Long
) {
    fun fingerprint(): String = buildString {
        append(processedSignalCount).append('|').append(simulatedFrom).append('|').append(simulatedUntil)
        pulses.forEach { pulse ->
            append('|').append(pulse.id).append('@').append(pulse.occurredAt)
            pulse.activations.toSortedMap().forEach { (drive, value) ->
                append(':').append(drive.name).append('=').append("%.6f".format(Locale.US, value))
            }
        }
        EnuManDrive.entries.forEach { drive ->
            val cell = finalState.drives.getValue(drive)
            append('|').append(drive.name).append('=')
                .append("%.6f".format(Locale.US, cell.potential))
                .append('/').append("%.6f".format(Locale.US, cell.adaptiveThreshold))
        }
    }
}

class EnuManReplayEngine(
    private val pulseEngine: EnuManPulseEngine = EnuManPulseEngine(),
    private val tickIntervalMs: Long = 15 * 60_000L
) {
    fun replay(
        initialState: EnuManState,
        signals: List<EnuManSignal>,
        until: Long
    ): EnuManReplayResult {
        var state = initialState
        val pulses = mutableListOf<PreSemanticPulse>()
        val ordered = signals.distinctBy { it.id }
            .filter { it.occurredAt >= initialState.lastTickAt && it.occurredAt <= until }
            .sortedWith(compareBy<EnuManSignal> { it.occurredAt }.thenBy { it.id })
        var steps = 0

        fun tick(at: Long, signal: EnuManSignal? = null) {
            if (steps++ >= MAX_REPLAY_STEPS) return
            val result = pulseEngine.step(state, signal, at)
            state = result.state
            result.pulse?.let(pulses::add)
        }

        ordered.forEach { signal ->
            while (state.lastTickAt + tickIntervalMs < signal.occurredAt && steps < MAX_REPLAY_STEPS) {
                tick(state.lastTickAt + tickIntervalMs)
            }
            tick(signal.occurredAt, signal)
        }
        while (state.lastTickAt + tickIntervalMs <= until && steps < MAX_REPLAY_STEPS) {
            tick(state.lastTickAt + tickIntervalMs)
        }
        if (state.lastTickAt < until && steps < MAX_REPLAY_STEPS) tick(until)

        return EnuManReplayResult(
            finalState = state,
            pulses = pulses,
            processedSignalCount = ordered.size,
            simulatedFrom = initialState.lastTickAt,
            simulatedUntil = until
        )
    }

    companion object {
        private const val MAX_REPLAY_STEPS = 10_000
    }
}

data class EnuManCalibrationReport(
    val companionId: String,
    val eventCount: Int,
    val explicitEventCount: Int,
    val pulseCount: Int,
    val quietPulseCount: Int,
    val pulsesByDrive: Map<EnuManDrive, Int>,
    val deterministic: Boolean,
    val warnings: List<String>
) {
    fun displayText(): String = buildString {
        append("回放事件 ").append(eventCount)
            .append(" · 明确分享 ").append(explicitEventCount)
            .append(" · 产生脉冲 ").append(pulseCount).append('\n')
        append("companion ").append(companionId).append(" · 隔离过滤按当前 EnuMan 状态").append('\n')
        append("静默 48 小时内源脉冲 ").append(quietPulseCount).append('\n')
        append("确定性复跑 ").append(if (deterministic) "一致" else "不一致").append('\n')
        if (pulsesByDrive.isNotEmpty()) {
            append("驱力分布 ")
            append(pulsesByDrive.entries.sortedByDescending { it.value }
                .joinToString(" · ") { "${it.key.agentLabel()} ${it.value}" })
            append('\n')
        }
        if (warnings.isEmpty()) append("校准判断 暂未发现过敏或迟钝")
        else append("校准提醒\n").append(warnings.joinToString("\n"))
    }
}

object EnuManCalibrationRunner {
    fun run(
        context: Context,
        scope: CompanionScope = CompanionScope.capture(context),
        now: Long = System.currentTimeMillis()
    ): EnuManCalibrationReport {
        val persistedState = EnuManStore.loadState(context.applicationContext, scope, now)
        val acceptedSignalIds = persistedState.recentSignalIds.toHashSet()
        val events = PresenceEventStore.recent(context.applicationContext, 100)
            .filter { "presence_${it.id}".take(120) in acceptedSignalIds }
            .filter { it.occurredAt <= now }
            .sortedBy { it.occurredAt }
        val start = events.firstOrNull()?.occurredAt?.coerceAtLeast(now - MAX_REAL_REPLAY_MS)
            ?: now - 24 * 60 * 60_000L
        val signals = events.filter { it.occurredAt >= start }.map(EnuManSignalMapper::fromPresence)
        val replayEngine = EnuManReplayEngine()
        val first = replayEngine.replay(EnuManState.initial(start), signals, now)
        val second = replayEngine.replay(EnuManState.initial(start), signals, now)
        val quietStart = now - 48 * 60 * 60_000L
        val quiet = replayEngine.replay(EnuManState.initial(quietStart), emptyList(), now)
        val byDrive = EnuManDrive.entries.associateWith { drive ->
            first.pulses.count { drive in it.activations }
        }.filterValues { it > 0 }
        val durationDays = ((now - start).coerceAtLeast(1L) / 86_400_000.0).coerceAtLeast(0.25)
        val pulsesPerDay = first.pulses.size / durationDays
        val explicitCount = events.count { it.explicitUserAction && it.occurredAt >= start }
        val warnings = buildList {
            if (pulsesPerDay > 12.0) add("脉冲密度偏高，需要提高适应阈值或加强抑制")
            if (signals.size >= 8 && explicitCount >= 2 && first.pulses.isEmpty()) {
                add("经历不少但没有脉冲，内核可能过于迟钝")
            }
            if (quiet.pulses.size !in 1..6) add("纯内源节律异常，48 小时产生 ${quiet.pulses.size} 次")
            if (first.fingerprint() != second.fingerprint()) add("相同经历复跑结果不一致")
        }
        return EnuManCalibrationReport(
            companionId = scope.companionId,
            eventCount = signals.size,
            explicitEventCount = explicitCount,
            pulseCount = first.pulses.size,
            quietPulseCount = quiet.pulses.size,
            pulsesByDrive = byDrive,
            deterministic = first.fingerprint() == second.fingerprint(),
            warnings = warnings
        )
    }

    private const val MAX_REAL_REPLAY_MS = 7 * 24 * 60 * 60_000L
}
