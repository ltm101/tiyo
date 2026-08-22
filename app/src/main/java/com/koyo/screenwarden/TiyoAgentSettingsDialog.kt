package com.koyo.screenwarden

import android.app.Dialog
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
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
        val ownerPreset = content.findViewById<TextView>(R.id.agent_owner_preset)
        val testConnection = content.findViewById<TextView>(R.id.agent_test_connection)
        val discoverModels = content.findViewById<TextView>(R.id.agent_discover_models)
        val connectionStatus = content.findViewById<TextView>(R.id.agent_connection_status)

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

        val hasOwnerPreset = BuildConfig.TIYO_PRESET_DEEPSEEK_KEY.isNotBlank() &&
            BuildConfig.TIYO_PRESET_MINIMAX_KEY.isNotBlank() &&
            BuildConfig.TIYO_PRESET_IMAGE_KEY.isNotBlank() &&
            BuildConfig.TIYO_PRESET_IMAGE_BASE_URL.isNotBlank() &&
            BuildConfig.TIYO_PRESET_IMAGE_MODEL.isNotBlank()
        ownerPreset.visibility = if (hasOwnerPreset) View.VISIBLE else View.GONE
        ownerPreset.setOnClickListener {
            providerId.setText(TiyoAgentConfig.PROVIDER_ID)
            baseUrl.setText(BuildConfig.TIYO_PRESET_DEEPSEEK_BASE_URL)
            model.setText(BuildConfig.TIYO_PRESET_DEEPSEEK_MODEL)
            apiKey.setText(BuildConfig.TIYO_PRESET_DEEPSEEK_KEY)
            minimaxKey.setText(BuildConfig.TIYO_PRESET_MINIMAX_KEY)
            imageGenProvider.check(R.id.agent_img_gpt)
            imageGenKey.setText(BuildConfig.TIYO_PRESET_IMAGE_KEY)
            imageGenBaseUrl.setText(BuildConfig.TIYO_PRESET_IMAGE_BASE_URL)
            imageGenModel.setText(BuildConfig.TIYO_PRESET_IMAGE_MODEL)
            Toast.makeText(content.context, "模型、语音和生图配置已填好，点保存生效", Toast.LENGTH_SHORT).show()
        }
        // 内置识图模型：配置从未纳入源码的 BuildConfig 本地私密字段读取
        content.findViewById<View>(R.id.agent_vision_preset).setOnClickListener {
            baseUrl.setText("https://apihub.agnes-ai.com/v1")
            model.setText("agnes-2.0-flash")
            if (BuildConfig.TIYO_AGNES_API_KEY.isNotBlank()) {
                apiKey.setText(BuildConfig.TIYO_AGNES_API_KEY)
                Toast.makeText(content.context, "已填入 Agnes 模型配置，保存即可", Toast.LENGTH_SHORT).show()
            } else {
                apiKey.text.clear()
                apiKey.hint = "请输入 Agnes API Key，将安全保存在本机"
                Toast.makeText(content.context, "Agnes 模型已选择，请填写 API Key", Toast.LENGTH_SHORT).show()
            }
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
                    setTextColor(ContextCompat.getColor(context, if (p.id == activeId) R.color.d_accent_deep else R.color.d_ink_2))
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

        fun runProviderProbe(showModels: Boolean) {
            val base = baseUrl.text.toString().trim()
            val key = apiKey.text.toString().trim().ifBlank { TiyoAgentConfig.providerKey(context) }
            if (base.isBlank()) {
                baseUrl.error = "需要 API 地址"
                return
            }
            if (key.isBlank()) {
                apiKey.error = "需要 API Key"
                return
            }
            testConnection.isEnabled = false
            discoverModels.isEnabled = false
            connectionStatus.text = if (showModels) "正在发现模型…" else "正在测试连接…"
            Thread {
                val result = ProviderConnectionProbe.discover(base, key)
                Handler(Looper.getMainLooper()).post {
                    testConnection.isEnabled = true
                    discoverModels.isEnabled = true
                    connectionStatus.text = if (result.latencyMs > 0) {
                        "${result.message} · ${result.latencyMs}ms"
                    } else {
                        result.message
                    }
                    if (showModels && result.models.isNotEmpty()) {
                        AlertDialog.Builder(context)
                            .setTitle("选择模型")
                            .setItems(result.models.toTypedArray()) { _, index ->
                                model.setText(result.models[index])
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }.apply { name = "tiyo-provider-probe" }.start()
        }

        testConnection.setOnClickListener { runProviderProbe(showModels = false) }
        discoverModels.setOnClickListener { runProviderProbe(showModels = true) }

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
