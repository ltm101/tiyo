package com.koyo.screenwarden

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.koyo.screenwarden.enuman.EnuManCalibrationRunner
import com.koyo.screenwarden.enuman.EnuManDrive
import com.koyo.screenwarden.enuman.EnuManMindSnapshot
import com.koyo.screenwarden.enuman.EnuManStore
import com.koyo.screenwarden.enuman.InterpretationStatus
import com.koyo.screenwarden.enuman.TiyoMindKernel
import com.koyo.screenwarden.enuman.agentLabel
import com.koyo.screenwarden.enuman.experience.ExperienceKind
import com.koyo.screenwarden.enuman.experience.ExperienceLedger
import java.util.Locale

/** Private diagnostics only. Reading this page never changes the inner state. */
class EnuManObserverFragment : Fragment(R.layout.fragment_enuman_observer) {
    private lateinit var scope: CompanionScope

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scope = CompanionScope.capture(requireContext())
        view.findViewById<View>(R.id.enuman_refresh).setOnClickListener { renderSnapshot() }
        view.findViewById<View>(R.id.enuman_run_replay).setOnClickListener { runCalibration() }
        renderSnapshot()
    }

    override fun onResume() {
        super.onResume()
        if (::scope.isInitialized) renderSnapshot()
    }

    private fun renderSnapshot() {
        if (!isAdded || view == null) return
        val snapshot = TiyoMindKernel.snapshot(requireContext(), scope)
        bindDrive(snapshot, EnuManDrive.CONNECTION, R.id.enuman_connection_bar, R.id.enuman_connection_value)
        bindDrive(snapshot, EnuManDrive.CURIOSITY, R.id.enuman_curiosity_bar, R.id.enuman_curiosity_value)
        bindDrive(snapshot, EnuManDrive.SAFETY, R.id.enuman_safety_bar, R.id.enuman_safety_value)
        bindDrive(snapshot, EnuManDrive.AUTONOMY, R.id.enuman_autonomy_bar, R.id.enuman_autonomy_value)
        bindDrive(snapshot, EnuManDrive.COHERENCE, R.id.enuman_coherence_bar, R.id.enuman_coherence_value)
        bindDrive(snapshot, EnuManDrive.REST, R.id.enuman_rest_bar, R.id.enuman_rest_value)

        view?.findViewById<TextView>(R.id.enuman_state_summary)?.text = stateSummary(snapshot)
        view?.findViewById<TextView>(R.id.enuman_counts)?.text =
            "脉冲 ${snapshot.pulseCount} · 解释 ${snapshot.interpretationCount} · " +
                "未解 ${snapshot.unresolvedCount} · 睡眠 ${snapshot.sleepCycleCount}"
        renderLedgerAndProtocol(snapshot)
        val latest = snapshot.latestInterpretation
        view?.findViewById<TextView>(R.id.enuman_latest_meaning)?.text =
            latest?.feltMeaning?.ifBlank { null } ?: "还没有形成语言，它现在只是在安静积累"
        view?.findViewById<TextView>(R.id.enuman_candidate_desires)?.text = when {
            latest == null -> "具体愿望会从经历里长出来，这里暂时还是空的"
            latest.candidateDesires.isEmpty() -> "这次理解没有形成具体愿望"
            else -> latest.candidateDesires.joinToString("\n") { "· $it" }
        }
        renderLineage()
    }

    private fun bindDrive(snapshot: EnuManMindSnapshot, drive: EnuManDrive, barId: Int, valueId: Int) {
        val reading = snapshot.drives.getValue(drive)
        view?.findViewById<ProgressBar>(barId)?.progress = (reading.ratio * 100).toInt().coerceIn(0, 125)
        view?.findViewById<TextView>(valueId)?.text = String.format(
            Locale.US,
            "%3.0f%%  %.2f / %.2f",
            reading.ratio * 100,
            reading.potential,
            reading.threshold
        )
    }

    private fun stateSummary(snapshot: EnuManMindSnapshot): String {
        val drives = snapshot.dominantDrives.joinToString("、") { it.agentLabel() }
        val maxRatio = snapshot.drives.values.maxOfOrNull { it.ratio } ?: 0.0
        val movement = when {
            maxRatio >= 1.0 -> "刚有一股冲动越过阈值，正在等待或接受理解"
            maxRatio >= 0.72 -> "有些东西已经接近成形"
            maxRatio >= 0.38 -> "内在活动正在缓慢积累"
            else -> "现在比较安静，但内源节律仍在继续"
        }
        return if (drives.isBlank()) movement else "$movement\n当前较明显的方向：$drives"
    }

    private fun renderLedgerAndProtocol(snapshot: EnuManMindSnapshot) {
        val app = requireContext().applicationContext
        val records = ExperienceLedger.records(app, scope)
        val counts = records.groupingBy { it.kind }.eachCount()
        val kindSummary = ExperienceKind.entries.joinToString(" ") { kind ->
            "${kind.name.lowercase()}=${counts[kind] ?: 0}"
        }
        val recent = records.takeLast(5).asReversed()
        val recentText = recent.joinToString(" · ") {
            "${it.kind.name.lowercase()}/${it.privacyClass.name.lowercase()}"
        }
        view?.findViewById<TextView>(R.id.enuman_experience_counts)?.text =
            "经历 ${records.size} · $kindSummary\n最近：${recentText.ifBlank { "无" }}"
        val ledgerRefs = records.flatMap { it.causalRefs }.toHashSet()
        val orphanPulses = EnuManStore.pulses(app, scope).count { pulse ->
            pulse.causeRefs.isNotEmpty() && pulse.causeRefs.none { it in ledgerRefs }
        }
        view?.findViewById<TextView>(R.id.enuman_orphan_pulses)?.text = "无经历来源脉冲 $orphanPulses"
        val info = TiyoAgentRuntime.currentInfo(scope)
        val protocol = when {
            info == null -> "原生 Agent 未启动 · 未知"
            MindContextCodec.CAPABILITY in info.capabilities -> "原生 expression_policy_v1 · 私有快照不进聊天"
            else -> "安全降级 · 不注入私有状态"
        }
        view?.findViewById<TextView>(R.id.enuman_protocol_status)?.text = "Private State Boundary：$protocol"
    }

    private fun renderLineage() {
        val interpretations = EnuManStore.interpretations(requireContext(), scope).takeLast(8).asReversed()
        view?.findViewById<TextView>(R.id.enuman_lineage)?.text = if (interpretations.isEmpty()) {
            "还没有解释谱系"
        } else {
            interpretations.joinToString("\n\n") { item ->
                val status = when (item.status) {
                    InterpretationStatus.UNRESOLVED -> "仍未解决"
                    InterpretationStatus.REFLECTED -> "反思后理解"
                    InterpretationStatus.DISSOLVED -> "已经消解"
                }
                "v${item.version} · $status\n${item.feltMeaning}"
            }
        }
    }

    private fun runCalibration() {
        val output = view?.findViewById<TextView>(R.id.enuman_replay_report) ?: return
        output.text = "正在用相同经历做两次纯本地回放…"
        view?.findViewById<View>(R.id.enuman_run_replay)?.isEnabled = false
        val app = requireContext().applicationContext
        Thread {
            val result = runCatching { EnuManCalibrationRunner.run(app, scope) }
            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread
                output.text = result.fold(
                    onSuccess = { it.displayText() },
                    onFailure = { "回放失败，没有改动真实内在状态" }
                )
                view?.findViewById<View>(R.id.enuman_run_replay)?.isEnabled = true
            }
        }.apply { name = "enuman-calibration" }.start()
    }
}
