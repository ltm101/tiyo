package com.koyo.screenwarden

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.io.File

/**
 * Deliberately staged creation flow: profile -> private references -> identity
 * anchor -> explicit user approval. The active companion is not touched
 * until a complete visual pack passes the later activation gate.
 */
class CompanionStudioFragment : Fragment(R.layout.fragment_companion_studio) {
    private lateinit var profileList: LinearLayout
    private lateinit var birthCard: View
    private lateinit var birthTitle: TextView
    private lateinit var birthStatus: TextView
    private lateinit var capabilityStatus: TextView
    private lateinit var generateButton: Button
    private lateinit var anchorPreview: ImageView
    private lateinit var anchorActions: View
    private lateinit var buildPackButton: Button
    private lateinit var generationCost: TextView
    private lateinit var packPreview: ImageView
    private lateinit var qualityReport: TextView
    private lateinit var refineActions: View
    private lateinit var refineButton: Button
    private lateinit var rollbackButton: Button
    private lateinit var activateButton: Button
    private lateinit var eraseButton: Button
    private var selectedCompanionId: String? = null

    private val pickReferences = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val id = selectedCompanionId ?: return@registerForActivityResult
        val ctx = context?.applicationContext ?: return@registerForActivityResult
        if (uris.isEmpty()) return@registerForActivityResult
        birthStatus.text = "正在安全导入参考图…"
        Thread {
            val result = CompanionReferenceImporter.import(ctx, id, uris)
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val message = when {
                    result.imported.isEmpty() -> "没有导入成功，请换一张清晰图片"
                    result.rejectedCount > 0 -> "已保存 ${result.imported.size} 张，另有 ${result.rejectedCount} 张未采用"
                    else -> "已安全保存 ${result.imported.size} 张参考图"
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                render()
            }
        }.start()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        profileList = view.findViewById(R.id.companion_profile_list)
        birthCard = view.findViewById(R.id.companion_birth_card)
        birthTitle = view.findViewById(R.id.companion_birth_title)
        birthStatus = view.findViewById(R.id.companion_birth_status)
        capabilityStatus = view.findViewById(R.id.companion_image_capability)
        generateButton = view.findViewById(R.id.companion_generate_anchor)
        anchorPreview = view.findViewById(R.id.companion_anchor_preview)
        anchorActions = view.findViewById(R.id.companion_anchor_actions)
        buildPackButton = view.findViewById(R.id.companion_build_pack)
        generationCost = view.findViewById(R.id.companion_generation_cost)
        packPreview = view.findViewById(R.id.companion_pack_preview)
        qualityReport = view.findViewById(R.id.companion_quality_report)
        refineActions = view.findViewById(R.id.companion_refine_actions)
        refineButton = view.findViewById(R.id.companion_refine)
        rollbackButton = view.findViewById(R.id.companion_rollback)
        activateButton = view.findViewById(R.id.companion_activate)
        eraseButton = view.findViewById(R.id.companion_erase)

        view.findViewById<View>(R.id.companion_studio_back).setOnClickListener {
            activity?.onBackPressed()
        }
        view.findViewById<View>(R.id.companion_koyo_row).setOnClickListener {
            switchActiveCompanion(CompanionProfileRules.DEFAULT_COMPANION_ID)
        }
        view.findViewById<Button>(R.id.companion_create_draft).setOnClickListener {
            createDraft(view)
        }
        view.findViewById<Button>(R.id.companion_pick_references).setOnClickListener {
            pickReferences.launch(arrayOf("image/*"))
        }
        generateButton.setOnClickListener { generateAnchor() }
        buildPackButton.setOnClickListener { confirmBuildAssetPack() }
        refineButton.setOnClickListener { showRefineMenu() }
        rollbackButton.setOnClickListener { confirmRollback() }
        activateButton.setOnClickListener {
            selectedCompanionId?.let(::switchActiveCompanion)
        }
        eraseButton.setOnClickListener { confirmEraseSelected() }
        view.findViewById<Button>(R.id.companion_approve_anchor).setOnClickListener {
            val id = selectedCompanionId ?: return@setOnClickListener
            if (CompanionBirthEngine.approveIdentityAnchor(requireContext(), id)) {
                Toast.makeText(requireContext(), "出生证已经确认，启用前仍保留当前角色", Toast.LENGTH_SHORT).show()
                render()
            }
        }
        view.findViewById<Button>(R.id.companion_reject_anchor).setOnClickListener {
            val id = selectedCompanionId ?: return@setOnClickListener
            if (CompanionBirthEngine.rejectIdentityAnchor(requireContext(), id)) {
                render()
                generateAnchor()
            }
        }

        if (selectedCompanionId == null) {
            selectedCompanionId = CompanionProfileStore.profiles(requireContext())
                .filterNot(CompanionProfile::isBuiltInCompanion)
                .maxByOrNull { it.updatedAt }
                ?.id
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::profileList.isInitialized) render()
    }

    private fun createDraft(root: View) {
        val nameInput = root.findViewById<EditText>(R.id.companion_name_input)
        val name = CompanionProfileRules.normalizeName(nameInput.text.toString())
        if (name.isBlank()) {
            Toast.makeText(requireContext(), "先给她或他取个名字", Toast.LENGTH_SHORT).show()
            return
        }
        if (!CompanionProfileRules.canUseCustomName(name)) {
            Toast.makeText(requireContext(), "“可又”留给 Tiyo 引导者，换一个只属于新角色的名字", Toast.LENGTH_LONG).show()
            return
        }
        val consent = when (root.findViewById<RadioGroup>(R.id.companion_consent_group).checkedRadioButtonId) {
            R.id.companion_consent_authorized -> CompanionPhotoConsent.AUTHORIZED_ADULT
            R.id.companion_consent_original -> CompanionPhotoConsent.ORIGINAL_CHARACTER
            else -> CompanionPhotoConsent.SELF
        }
        val profile = CompanionBirthEngine.createDraft(requireContext(), name, consent)
        selectedCompanionId = profile.id
        nameInput.setText("")
        Toast.makeText(requireContext(), "${profile.displayName}的角色草稿建好了", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun generateAnchor() {
        val id = selectedCompanionId ?: return
        generateButton.isEnabled = false
        birthStatus.text = "正在生成角色出生证，完成前不会替换当前角色…"
        CompanionBirthEngine.generateIdentityAnchor(requireContext(), id) { result ->
            if (!isAdded) return@generateIdentityAnchor
            when (result) {
                is CompanionBirthEngine.Result.AnchorReady -> {
                    Toast.makeText(requireContext(), "出生证生成好了，请仔细确认是不是同一个人", Toast.LENGTH_LONG).show()
                }
                is CompanionBirthEngine.Result.Failed -> {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            }
            render()
        }
    }

    private fun buildAssetPack() {
        runAssetBuild(emptySet())
    }

    private fun runAssetBuild(forceRoles: Set<CompanionAssetRole>) {
        val id = selectedCompanionId ?: return
        val refining = forceRoles.isNotEmpty()
        buildPackButton.isEnabled = false
        refineButton.isEnabled = false
        rollbackButton.isEnabled = false
        eraseButton.isEnabled = false
        birthStatus.text = if (refining) "正在保存上一版并精修单项资源…"
            else "准备生成角色资源包，已经完成的图片会自动续用…"
        CompanionAssetPackBuilder.build(requireContext(), id, forceRoles) { event ->
            if (!isAdded) return@build
            when (event) {
                is CompanionAssetPackBuilder.Event.Progress -> {
                    birthStatus.text = "${event.label} · ${event.completed}/${event.total}"
                }
                is CompanionAssetPackBuilder.Event.Complete -> {
                    Toast.makeText(
                        requireContext(),
                        if (refining) "精修完成，不满意可以撤回上一版"
                        else "角色资源包完整了，现在才允许启用",
                        Toast.LENGTH_LONG
                    ).show()
                    if (refining && CompanionProfileStore.activeId(requireContext()) == id) {
                        activity?.recreate()
                    } else {
                        render()
                    }
                }
                is CompanionAssetPackBuilder.Event.Failed -> {
                    Toast.makeText(requireContext(), event.message, Toast.LENGTH_LONG).show()
                    render()
                }
            }
        }
    }

    private fun showRefineMenu() {
        val id = selectedCompanionId ?: return
        if (CompanionAssetPackBuilder.isBuilding(id)) return
        val candidates = CompanionGenerationPlan.phaseOneAssets.filter { spec ->
            spec.role != CompanionAssetRole.IDENTITY_ANCHOR &&
                CompanionAssetPack.isComplete(requireContext(), id, spec)
        }
        if (candidates.isEmpty()) {
            Toast.makeText(requireContext(), "还没有可以单独精修的资源", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = candidates.map { spec ->
            if (spec.frameGrid != null) "${spec.role.label} · ${spec.frameGrid.frameCount}帧"
            else spec.role.label
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("想精修哪一项")
            .setItems(labels) { _, index -> confirmRefine(candidates[index].role) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmRefine(role: CompanionAssetRole) {
        AlertDialog.Builder(requireContext())
            .setTitle("精修${role.label}")
            .setMessage("预计调用生图模型 1 次，质量不合格时最多再修复 1 次\n\n旧版会先保存，完成后可以一键撤回")
            .setNegativeButton("先不改", null)
            .setPositiveButton("开始精修") { _, _ -> runAssetBuild(setOf(role)) }
            .show()
    }

    private fun confirmRollback() {
        val id = selectedCompanionId ?: return
        val snapshot = CompanionAssetPackSnapshot.latest(requireContext(), id) ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("撤回${snapshot.role.label}的精修？")
            .setMessage("会恢复精修前保存的那一版，不会影响其他动作和场景")
            .setNegativeButton("保留新版", null)
            .setPositiveButton("恢复上一版") { _, _ -> rollbackLatest(id) }
            .show()
    }

    private fun rollbackLatest(companionId: String) {
        val role = CompanionAssetPackSnapshot.restoreLatest(requireContext(), companionId)
        if (role == null) {
            Toast.makeText(requireContext(), "上一版不完整，无法安全恢复", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(requireContext(), "${role.label}已经恢复到上一版", Toast.LENGTH_SHORT).show()
        if (CompanionProfileStore.activeId(requireContext()) == companionId) {
            activity?.recreate()
        } else {
            render()
        }
    }

    private fun confirmBuildAssetPack() {
        val id = selectedCompanionId ?: return
        val ctx = context ?: return
        val remaining = CompanionGenerationPlan.phaseOneAssets.count { spec ->
            spec.role != CompanionAssetRole.IDENTITY_ANCHOR &&
                !CompanionAssetPack.isComplete(ctx, id, spec)
        }
        if (remaining <= 0) {
            buildAssetPack()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("生成完整角色资源包")
            .setMessage(
                "预计调用生图模型 $remaining 次，每个失败项目最多自动修复一次\n\n" +
                    "已经完成的项目会保留，下次可以从断点继续"
            )
            .setNegativeButton("先等等", null)
            .setPositiveButton("开始生成") { _, _ -> buildAssetPack() }
            .show()
    }

    private fun confirmEraseSelected() {
        val id = selectedCompanionId ?: return
        val profile = CompanionProfileStore.find(requireContext(), id) ?: return
        val certificate = CompanionBirthCertificateStore.load(requireContext(), id)
        if (certificate?.status in setOf(
                CompanionBirthStatus.GENERATING_ANCHOR,
                CompanionBirthStatus.BUILDING_PACK
            )
        ) {
            Toast.makeText(requireContext(), "生成结束后才能删除，避免留下半份文件", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("删除${profile.displayName}？")
            .setMessage("会删除这个角色的参考图、出生证、对话和记忆，引导角色不会受影响")
            .setNegativeButton("保留", null)
            .setPositiveButton("彻底删除") { _, _ -> eraseSelected(id) }
            .show()
    }

    private fun eraseSelected(companionId: String) {
        val wasActive = CompanionProfileStore.activeId(requireContext()) == companionId
        if (!CompanionProfileStore.erase(requireContext(), companionId)) {
            Toast.makeText(requireContext(), "没有完全删除，请稍后重试", Toast.LENGTH_LONG).show()
            return
        }
        selectedCompanionId = null
        Toast.makeText(requireContext(), "角色与参考图已经删除", Toast.LENGTH_SHORT).show()
        if (wasActive && TiyoAgentConfig.isConfigured(requireContext())) {
            TiyoAgentRuntime.restart(
                requireContext(),
                { if (isAdded) activity?.recreate() },
                { if (isAdded) activity?.recreate() }
            )
        } else if (wasActive) {
            activity?.recreate()
        } else {
            render()
        }
    }

    private fun switchActiveCompanion(companionId: String) {
        val previousId = CompanionProfileStore.activeId(requireContext())
        if (!CompanionProfileStore.activate(requireContext(), companionId)) {
            Toast.makeText(requireContext(), "资源包还不完整，暂时不能启用", Toast.LENGTH_SHORT).show()
            return
        }
        val name = CompanionProfileStore.activeName(requireContext())
        val newScope = CompanionScope.capture(requireContext())
        PersonaFragment.ensureRuntimeRules(requireContext())
        TiyoMemoryBridge.ensureMemoryGuidance(requireContext(), newScope)
        val finishSwitch = {
            if (isAdded) {
                Toast.makeText(requireContext(), "已经切到$name", Toast.LENGTH_SHORT).show()
                activity?.recreate()
            }
        }
        if (TiyoAgentConfig.isConfigured(requireContext())) {
            TiyoAgentRuntime.restart(
                requireContext(),
                { finishSwitch() },
                { error ->
                    CompanionProfileStore.activate(requireContext(), previousId)
                    TiyoAgentRuntime.restart(requireContext(), {}, {})
                    if (isAdded) {
                        Toast.makeText(
                            requireContext(),
                            "切换没有完成：$error",
                            Toast.LENGTH_LONG
                        ).show()
                        render()
                    }
                }
            )
        } else {
            finishSwitch()
        }
    }

    private fun render() {
        val ctx = context ?: return
        val profiles = CompanionProfileStore.profiles(ctx)
        val activeId = CompanionProfileStore.activeId(ctx)
        view?.findViewById<TextView>(R.id.companion_koyo_state)?.text =
            if (activeId == CompanionProfileRules.DEFAULT_COMPANION_ID) "正在使用" else "随时可以回来找可又"
        renderProfileRows(profiles.filterNot(CompanionProfile::isBuiltInCompanion))

        val selected = selectedCompanionId?.let { CompanionProfileStore.find(ctx, it) }
        val certificate = selected?.let { CompanionBirthCertificateStore.load(ctx, it.id) }
        birthCard.visibility = if (selected == null || certificate == null) View.GONE else View.VISIBLE
        if (selected == null || certificate == null) return

        birthTitle.text = "${selected.displayName}的角色出生证"
        val capability = ImageGenClient.capability()
        capabilityStatus.text = when {
            !capability.configured -> "尚未配置生图模型，可以先保存草稿和参考图"
            !capability.canEditReference -> "当前生图模型不支持参考图编辑，请切换到 GPT Image"
            else -> "${capability.model.ifBlank { "GPT Image" }} 已就绪 · 资源包完成后自动删除原参考图"
        }

        val referenceCount = certificate.references.size
        val packBusy = CompanionAssetPackBuilder.isBuilding(selected.id)
        birthStatus.text = statusText(certificate, referenceCount)
        val isGenerating = certificate.status == CompanionBirthStatus.GENERATING_ANCHOR
        val isApproved = certificate.status in setOf(
            CompanionBirthStatus.ANCHOR_APPROVED,
            CompanionBirthStatus.BUILDING_PACK,
            CompanionBirthStatus.READY
        )
        val anchor = certificate.anchorFileName?.let {
            File(CompanionWorkspace.assetPackRoot(ctx, selected.id), it)
        }
        val failedPackCanResume = certificate.status == CompanionBirthStatus.FAILED &&
            anchor?.isFile == true
        generateButton.isEnabled = referenceCount > 0 &&
            capability.canEditReference && !isGenerating && !isApproved && !failedPackCanResume
        if (anchor?.isFile == true) {
            anchorPreview.visibility = View.VISIBLE
            anchorPreview.setImageBitmap(BitmapFactory.decodeFile(anchor.absolutePath))
        } else {
            anchorPreview.visibility = View.GONE
            anchorPreview.setImageDrawable(null)
        }
        anchorActions.visibility = if (
            certificate.status == CompanionBirthStatus.AWAITING_APPROVAL && anchor?.isFile == true
        ) View.VISIBLE else View.GONE
        val remainingCalls = CompanionGenerationPlan.phaseOneAssets.count { spec ->
            spec.role != CompanionAssetRole.IDENTITY_ANCHOR &&
                !CompanionAssetPack.isComplete(ctx, selected.id, spec)
        }
        val canBuild = anchor?.isFile == true && certificate.status in setOf(
            CompanionBirthStatus.ANCHOR_APPROVED,
            CompanionBirthStatus.BUILDING_PACK,
            CompanionBirthStatus.FAILED,
            CompanionBirthStatus.READY
        ) && remainingCalls > 0
        buildPackButton.visibility = if (canBuild) View.VISIBLE else View.GONE
        buildPackButton.isEnabled = canBuild && capability.canEditReference && !packBusy
        generationCost.visibility = if (canBuild) View.VISIBLE else View.GONE
        generationCost.text = when {
            remainingCalls <= 0 -> "核心资源已齐全，不会重复调用生图模型"
            else -> "预计还需 $remainingCalls 次生图调用，失败项目最多自动修复一次"
        }
        val previewSheet = File(
            CompanionWorkspace.assetPackRoot(ctx, selected.id),
            "${CompanionAssetRole.TODAY_WAVE.key}_sheet.png"
        )
        if (previewSheet.isFile) {
            packPreview.visibility = View.VISIBLE
            val previewKey = "${previewSheet.absolutePath}:${previewSheet.lastModified()}"
            if (packPreview.tag != previewKey) {
                packPreview.setImageBitmap(BitmapFactory.decodeFile(previewSheet.absolutePath))
                packPreview.tag = previewKey
            }
        } else {
            packPreview.visibility = View.GONE
            packPreview.setImageDrawable(null)
            packPreview.tag = null
        }
        val entriesByRole = CompanionAssetPack.entries(ctx, selected.id).associateBy { it.role }
        val reportLines = CompanionGenerationPlan.phaseOneAssets.mapNotNull { spec ->
            val entry = entriesByRole[spec.role] ?: return@mapNotNull null
            val frameText = entry.frameFileNames.takeIf { it.isNotEmpty() }
                ?.let { " · ${it.size}帧" }.orEmpty()
            "${spec.role.label} · 本地检查 ${entry.qualityScore}$frameText"
        }
        qualityReport.visibility = if (reportLines.isEmpty()) View.GONE else View.VISIBLE
        qualityReport.text = if (reportLines.isEmpty()) "" else
            "技术检查不替代身份确认\n${reportLines.joinToString("\n")}"
        val canRefine = selected.status == CompanionStatus.READY &&
            CompanionAssetPack.hasRequiredAssets(ctx, selected.id)
        refineActions.visibility = if (canRefine) View.VISIBLE else View.GONE
        refineButton.isEnabled = canRefine && capability.canEditReference && !packBusy
        val snapshot = CompanionAssetPackSnapshot.latest(ctx, selected.id)
        rollbackButton.visibility = if (snapshot != null) View.VISIBLE else View.GONE
        rollbackButton.text = snapshot?.let { "撤回${it.role.label}" } ?: "撤回精修"
        rollbackButton.isEnabled = snapshot != null && !packBusy
        activateButton.visibility = if (
            selected.status == CompanionStatus.READY &&
            CompanionAssetPack.hasRequiredAssets(ctx, selected.id)
        ) View.VISIBLE else View.GONE
        activateButton.text = if (activeId == selected.id) "正在使用这个角色" else "启用这个角色"
        activateButton.isEnabled = activeId != selected.id && !packBusy
        eraseButton.isEnabled = certificate.status !in setOf(
            CompanionBirthStatus.GENERATING_ANCHOR,
            CompanionBirthStatus.BUILDING_PACK
        ) && !packBusy
    }

    private fun renderProfileRows(customProfiles: List<CompanionProfile>) {
        val ctx = context ?: return
        profileList.removeAllViews()
        customProfiles.forEach { profile ->
            val divider = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1)
                ).apply { topMargin = dp(12); bottomMargin = dp(12) }
                setBackgroundColor(ContextCompat.getColor(ctx, R.color.d_line))
            }
            profileList.addView(divider)

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener {
                    selectedCompanionId = profile.id
                    render()
                }
            }
            val name = TextView(ctx).apply {
                text = profile.displayName
                textSize = 15f
                setTextColor(ContextCompat.getColor(ctx, R.color.d_ink))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val state = TextView(ctx).apply {
                text = profileStatusLabel(profile.status)
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.d_ink_2))
            }
            row.addView(name)
            row.addView(state)
            profileList.addView(row)
        }
    }

    private fun statusText(certificate: CompanionBirthCertificate, referenceCount: Int): String = when (certificate.status) {
        CompanionBirthStatus.DRAFT -> "还没有参考图，建议正脸、四分之三侧脸和全身各一张"
        CompanionBirthStatus.REFERENCES_READY -> "已保存 $referenceCount 张参考图，可以生成出生证"
        CompanionBirthStatus.GENERATING_ANCHOR -> "正在生成出生证，请稍等"
        CompanionBirthStatus.AWAITING_APPROVAL -> "请确认五个视角都是同一个人，脸和发型没有漂移"
        CompanionBirthStatus.ANCHOR_APPROVED -> "出生证已确认，等待生成动作与场景资源包"
        CompanionBirthStatus.BUILDING_PACK -> "出生证已确认，动作与场景资源包施工中"
        CompanionBirthStatus.READY -> "角色资源包完整，可以安全启用"
        CompanionBirthStatus.FAILED -> certificate.failureReason ?: "生成失败，可以保留参考图重试"
    }

    private fun profileStatusLabel(status: CompanionStatus): String = when (status) {
        CompanionStatus.DRAFT -> "草稿"
        CompanionStatus.ANCHOR_PENDING -> "待生成"
        CompanionStatus.ANCHOR_REVIEW -> "待确认"
        CompanionStatus.PACK_BUILDING -> "出生证已确认"
        CompanionStatus.READY -> "可启用"
        CompanionStatus.FAILED -> "可重试"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
