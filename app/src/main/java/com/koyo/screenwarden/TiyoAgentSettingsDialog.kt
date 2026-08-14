package com.koyo.screenwarden

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

/** Agent 设置弹窗：多 provider 切换 / 另存 + baseUrl/model/key/权限。 */
class TiyoAgentSettingsDialog(
    private val context: Context,
    private val onSaved: () -> Unit
) {
    fun show() {
        val dialog = Dialog(context)
        val content = LayoutInflater.from(context)
            .inflate(R.layout.dialog_agent_settings, null, false)
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.34f }
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val current = TiyoAgentConfig.load(context)
        val providerList = content.findViewById<LinearLayout>(R.id.agent_provider_list)
        val providerId = content.findViewById<EditText>(R.id.agent_provider_id)
        val baseUrl = content.findViewById<EditText>(R.id.agent_base_url)
        val model = content.findViewById<EditText>(R.id.agent_model)
        val apiKey = content.findViewById<EditText>(R.id.agent_api_key)
        val minimaxKey = content.findViewById<EditText>(R.id.agent_minimax_key)
        val imageGenProvider = content.findViewById<RadioGroup>(R.id.agent_image_gen_provider)
        val imageGenKey = content.findViewById<EditText>(R.id.agent_image_gen_key)
        val imageGenBaseUrl = content.findViewById<EditText>(R.id.agent_image_gen_base_url)
        val imageGenModel = content.findViewById<EditText>(R.id.agent_image_gen_model)
        val permissions = content.findViewById<RadioGroup>(R.id.agent_permission_group)

        providerId.setText(TiyoAgentConfig.activeId(context))
        baseUrl.setText(current.baseUrl)
        model.setText(current.model)
        if (TiyoAgentConfig.providerKey(context).isNotBlank()) {
            apiKey.hint = "已安全保存，留空保持不变"
        }
        if (TiyoAgentConfig.ttsApiKey(context).isNotBlank()) {
            minimaxKey.hint = "已安全保存，留空保持不变"
        }
        imageGenProvider.check(
            when (TiyoAgentConfig.imageGenProvider(context)) {
                "gpt-image" -> R.id.agent_img_gpt
                "glm" -> R.id.agent_img_glm
                "gemini" -> R.id.agent_img_gemini
                else -> R.id.agent_img_seedream
            }
        )
        if (TiyoAgentConfig.imageGenKey(context).isNotBlank()) {
            imageGenKey.hint = "已安全保存，留空保持不变"
        }
        imageGenBaseUrl.setText(TiyoAgentConfig.imageGenBaseUrl(context))
        imageGenModel.setText(TiyoAgentConfig.imageGenModel(context))
        permissions.check(
            when (current.permissionMode) {
                "auto" -> R.id.agent_permission_auto
                "full" -> R.id.agent_permission_full
                else -> R.id.agent_permission_ask
            }
        )

        // 快捷项只填服务地址和模型，密钥始终由用户自己提供
        content.findViewById<View>(R.id.agent_vision_preset).setOnClickListener {
            baseUrl.setText("https://apihub.agnes-ai.com/v1")
            model.setText("agnes-2.0-flash")
            Toast.makeText(
                content.context,
                "已填入 Agnes 识图模型，请填写自己的 API Key",
                Toast.LENGTH_SHORT
            ).show()
        }

        fun renderProviderList() {
            providerList.removeAllViews()
            val providers = TiyoAgentConfig.loadAll(context)
            val activeId = TiyoAgentConfig.activeId(context)
            providers.forEach { p ->
                val row = TextView(context).apply {
                    text = buildString {
                        append(if (p.id == activeId) "● " else "  ")
                        append(p.id)
                        append("  ·  ")
                        append(p.model)
                    }
                    setTextColor(Color.parseColor(if (p.id == activeId) "#E8894A" else "#55514B"))
                    textSize = 13f
                    setPadding(0, 12, 0, 12)
                    setOnClickListener {
                        if (p.id != activeId && TiyoAgentConfig.setActive(context, p.id)) {
                            providerId.setText(p.id)
                            renderProviderList()
                            dialog.dismiss()
                            onSaved()
                        }
                    }
                }
                providerList.addView(row)
            }
        }
        renderProviderList()

        content.findViewById<TextView>(R.id.agent_settings_cancel)
            .setOnClickListener { dialog.dismiss() }
        content.findViewById<TextView>(R.id.agent_settings_save)
            .setOnClickListener {
                val normalizedBase = baseUrl.text.toString().trim().trimEnd('/')
                val normalizedModel = model.text.toString().trim()
                val enteredKey = apiKey.text.toString().trim()
                if (normalizedBase.isBlank()) {
                    baseUrl.error = "需要 API 地址"
                    return@setOnClickListener
                }
                if (normalizedModel.isBlank()) {
                    model.error = "需要模型名称"
                    return@setOnClickListener
                }
                if (enteredKey.isBlank() && TiyoAgentConfig.providerKey(context).isBlank()) {
                    apiKey.error = "第一次使用需要 API Key"
                    return@setOnClickListener
                }
                val permissionMode = when (permissions.checkedRadioButtonId) {
                    R.id.agent_permission_auto -> "auto"
                    R.id.agent_permission_full -> "full"
                    else -> "ask"
                }
                TiyoAgentConfig.saveAs(
                    context,
                    providerId.text.toString().trim().ifBlank { TiyoAgentConfig.PROVIDER_ID },
                    normalizedBase,
                    normalizedModel,
                    enteredKey.takeIf { it.isNotBlank() },
                    permissionMode
                )
                val enteredMinimax = minimaxKey.text.toString().trim()
                if (enteredMinimax.isNotBlank()) {
                    TiyoAgentConfig.saveTtsApiKey(context, enteredMinimax)
                }
                TiyoAgentConfig.saveImageGenProvider(
                    context,
                    when (imageGenProvider.checkedRadioButtonId) {
                        R.id.agent_img_gpt -> "gpt-image"
                        R.id.agent_img_glm -> "glm"
                        R.id.agent_img_gemini -> "gemini"
                        else -> "seedream"
                    }
                )
                val enteredImageKey = imageGenKey.text.toString().trim()
                if (enteredImageKey.isNotBlank()) {
                    TiyoAgentConfig.saveImageGenKey(context, enteredImageKey)
                }
                TiyoAgentConfig.saveImageGenBaseUrl(context, imageGenBaseUrl.text.toString().trim())
                TiyoAgentConfig.saveImageGenModel(context, imageGenModel.text.toString().trim())
                dialog.dismiss()
                onSaved()
            }

        dialog.setOnShowListener {
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }
}
