package com.koyo.screenwarden

import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment

/** 本机 Skill 能力包管理：导入一个包含 SKILL.md 的 zip 后由 Agent 按需选择 */
class SkillSettingsFragment : Fragment(R.layout.fragment_skill_settings) {

    private lateinit var nameInput: EditText
    private lateinit var status: TextView
    private lateinit var skillList: LinearLayout

    private val skillPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importSkill(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        nameInput = view.findViewById(R.id.skill_name)
        status = view.findViewById(R.id.skill_status)
        skillList = view.findViewById(R.id.skill_list)

        view.findViewById<View>(R.id.skill_back_btn).setOnClickListener { activity?.onBackPressed() }
        view.findViewById<View>(R.id.btn_skill_import).setOnClickListener {
            skillPicker.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        renderSkills()
    }

    private fun importSkill(uri: Uri) {
        val requested = nameInput.text.toString().trim().ifBlank { suggestedName(uri) }
        val appContext = requireContext().applicationContext
        status.text = "正在检查能力包…"
        view?.findViewById<View>(R.id.btn_skill_import)?.isEnabled = false
        Thread {
            val result = TiyoExtensionStore.installSkillZip(appContext, uri, requested)
            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread
                view?.findViewById<View>(R.id.btn_skill_import)?.isEnabled = true
                result.onSuccess { name ->
                    nameInput.setText("")
                    status.text = "已安装 $name，Agent 会按任务自动选择"
                    renderSkills()
                    (activity as? MainActivity)?.reloadAgentRuntime()
                    Toast.makeText(requireContext(), "Skill 已安装", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    status.text = it.message ?: "Skill 导入失败"
                }
            }
        }.apply { name = "tiyo-skill-import" }.start()
    }

    private fun renderSkills() {
        skillList.removeAllViews()
        val skills = TiyoExtensionStore.installedSkills(requireContext())
        if (skills.isEmpty()) {
            skillList.addView(rowText("还没有安装 Skill", 14f, R.color.d_ink_3))
            return
        }
        skills.forEach { skill ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val copy = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(rowText(skill.name, 14f, R.color.d_ink))
                addView(rowText(skill.summary, 12f, R.color.d_ink_3))
            }
            val remove = rowText("移除", 13f, R.color.d_accent).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), 0, dp(8))
                setOnClickListener { confirmRemove(skill.name) }
            }
            row.addView(copy)
            row.addView(remove)
            skillList.addView(row)
        }
    }

    private fun confirmRemove(name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("移除 $name？")
            .setMessage("这会删除手机里的这个能力包，需要时可以重新导入")
            .setNegativeButton("取消", null)
            .setPositiveButton("移除") { _, _ ->
                runCatching { TiyoExtensionStore.removeSkill(requireContext(), name) }
                    .onSuccess {
                        renderSkills()
                        (activity as? MainActivity)?.reloadAgentRuntime()
                    }
                    .onFailure { status.text = it.message ?: "移除失败" }
            }
            .show()
    }

    private fun suggestedName(uri: Uri): String {
        var displayName = ""
        val cursor: Cursor? = runCatching {
            requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        }.getOrNull()
        cursor?.use {
            if (it.moveToFirst()) displayName = it.getString(0).orEmpty()
        }
        return displayName.substringBeforeLast('.').ifBlank { "custom-skill" }
    }

    private fun rowText(value: String, size: Float, color: Int) = TextView(requireContext()).apply {
        text = value
        textSize = size
        setTextColor(requireContext().getColor(color))
        maxLines = 3
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
