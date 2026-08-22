package com.koyo.screenwarden

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import com.koyo.screenwarden.presence.PresenceAdapterRegistry
import com.koyo.screenwarden.presence.PresenceAvailability
import com.koyo.screenwarden.presence.PresenceChannel
import com.koyo.screenwarden.presence.PresenceChannelRegistry
import com.koyo.screenwarden.presence.MobilePresenceConfig
import com.koyo.screenwarden.presence.TiyoPresenceService
import com.koyo.screenwarden.presence.WeixinMobileOnboarding
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

class PresenceSettingsFragment : Fragment(R.layout.fragment_presence_settings) {
    private lateinit var botStatus: TextView
    private val botSwitches = linkedMapOf<PresenceChannel, SwitchCompat>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        botStatus = view.findViewById(R.id.presence_bot_status)
        botSwitches[PresenceChannel.FEISHU] = view.findViewById(R.id.presence_feishu_enabled)
        botSwitches[PresenceChannel.WECOM] = view.findViewById(R.id.presence_wecom_enabled)
        botSwitches[PresenceChannel.QQ] = view.findViewById(R.id.presence_qq_enabled)
        botSwitches[PresenceChannel.WECHAT] = view.findViewById(R.id.presence_wechat_enabled)
        botSwitches.forEach { (channel, switch) ->
            switch.setOnClickListener { handleChannelSwitch(channel, switch) }
            switch.setOnLongClickListener {
                showChannelCredentials(channel)
                true
            }
        }

        view.findViewById<View>(R.id.presence_back).setOnClickListener { activity?.onBackPressed() }
        view.findViewById<TextView>(R.id.presence_capability_summary).text = capabilitySummary()
        view.findViewById<Button>(R.id.presence_bot_save).setOnClickListener { saveBotChannels() }
        view.findViewById<Button>(R.id.presence_bot_health).setOnClickListener { renderBotHealth() }
        renderConfig()
    }

    override fun onResume() {
        super.onResume()
        if (::botStatus.isInitialized) renderBotHealth()
    }

    private fun renderConfig() {
        val channels = MobilePresenceConfig.enabledChannels(requireContext())
        botSwitches.forEach { (channel, switch) -> switch.isChecked = channel in channels }
        renderBotHealth()
    }

    private fun saveBotChannels() {
        val missing = botSwitches.entries.firstOrNull { (channel, switch) ->
            switch.isChecked && !MobilePresenceConfig.credentials(requireContext(), channel).configured
        }
        if (missing != null) {
            showChannelCredentials(missing.key)
            return
        }
        botSwitches.forEach { (channel, switch) ->
            MobilePresenceConfig.setEnabled(requireContext(), channel, switch.isChecked)
            PresenceAdapterRegistry.get(channel)?.let { adapter ->
                adapter.stop(requireContext())
                if (switch.isChecked) adapter.start(requireContext())
            }
        }
        TiyoPresenceService.refresh(requireContext())
        Toast.makeText(requireContext(), "手机通道已保存", Toast.LENGTH_SHORT).show()
        botStatus.postDelayed({ if (isAdded) renderBotHealth() }, 900L)
    }

    private fun renderBotHealth() {
        val selected = MobilePresenceConfig.enabledChannels(requireContext())
        if (selected.isEmpty()) {
            botStatus.text = "全部关闭，不会读取或发送平台消息"
            return
        }
        botStatus.text = selected.sortedBy { it.name }.joinToString("\n") { channel ->
            val health = PresenceAdapterRegistry.get(channel)?.health()
            val name = PresenceChannelRegistry.forChannel(channel)?.displayName ?: channel.name
            "$name · ${if (health?.healthy == true) "手机已连接" else health?.detail.orEmpty().ifBlank { "等待手机连接" }}"
        }
    }

    private fun handleChannelSwitch(channel: PresenceChannel, switch: SwitchCompat) {
        if (!switch.isChecked) {
            MobilePresenceConfig.setEnabled(requireContext(), channel, false)
            PresenceAdapterRegistry.get(channel)?.stop(requireContext())
            TiyoPresenceService.refresh(requireContext())
            renderBotHealth()
            return
        }
        if (!MobilePresenceConfig.credentials(requireContext(), channel).configured) {
            switch.isChecked = false
            showChannelCredentials(channel)
        } else {
            MobilePresenceConfig.setEnabled(requireContext(), channel, true)
            PresenceAdapterRegistry.get(channel)?.start(requireContext())
            TiyoPresenceService.refresh(requireContext())
            renderBotHealth()
        }
    }

    private fun showChannelCredentials(channel: PresenceChannel) {
        val current = MobilePresenceConfig.credentials(requireContext(), channel)
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val primary = EditText(requireContext()).apply {
            hint = when (channel) {
                PresenceChannel.FEISHU -> "飞书 App ID"
                PresenceChannel.WECOM -> "企业微信 Bot ID"
                PresenceChannel.QQ -> "QQ 机器人 App ID"
                PresenceChannel.WECHAT -> "微信机器人账户标识（可填 default）"
                else -> "应用 ID"
            }
            setText(current.primaryId)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val secret = EditText(requireContext()).apply {
            hint = when (channel) {
                PresenceChannel.WECHAT -> "微信 iLink Token（留空保持原值）"
                else -> "App Secret / Bot Secret（留空保持原值）"
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        val allow = EditText(requireContext()).apply {
            hint = "允许的用户 ID；留空会绑定第一个联系者"
            setText(current.allowFrom.joinToString(","))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        container.addView(primary)
        container.addView(secret)
        container.addView(allow)
        val qqSandbox = SwitchCompat(requireContext()).apply {
            text = getString(R.string.presence_qq_sandbox)
            isChecked = current.secondaryId.equals("sandbox", true)
            visibility = if (channel == PresenceChannel.QQ) View.VISIBLE else View.GONE
        }
        if (channel == PresenceChannel.QQ) container.addView(qqSandbox)
        val name = PresenceChannelRegistry.forChannel(channel)?.displayName ?: channel.name
        val builder = AlertDialog.Builder(requireContext())
            .setTitle("配置$name")
            .setMessage("凭据只加密保存在这台手机，不经过电脑")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存并连接") { _, _ ->
                val primaryId = primary.text.toString().trim().ifBlank {
                    if (channel == PresenceChannel.WECHAT) "default" else current.primaryId
                }
                val newSecret = secret.text.toString().trim()
                if (primaryId.isBlank() || (newSecret.isBlank() && current.secret.isBlank())) {
                    Toast.makeText(requireContext(), "ID 和密钥都要填写", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val allowIds = allow.text.toString().split(Regex("[\\s,，;；]+"))
                    .map(String::trim).filter(String::isNotBlank).toSet()
                MobilePresenceConfig.saveCredentials(
                    requireContext(),
                    channel,
                    primaryId,
                    newSecret,
                    allowIds,
                    if (channel == PresenceChannel.QQ) {
                        if (qqSandbox.isChecked) "sandbox" else "production"
                    } else current.secondaryId
                )
                MobilePresenceConfig.setEnabled(requireContext(), channel, true)
                botSwitches[channel]?.isChecked = true
                PresenceAdapterRegistry.get(channel)?.let { adapter ->
                    adapter.stop(requireContext())
                    adapter.start(requireContext())
                }
                TiyoPresenceService.refresh(requireContext())
                botStatus.postDelayed({ if (isAdded) renderBotHealth() }, 1_000L)
            }
        if (channel == PresenceChannel.WECHAT) {
            builder.setNeutralButton("微信扫码登录") { _, _ -> startWeixinOnboarding() }
        }
        builder.show()
    }

    private fun startWeixinOnboarding() {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val qr = ImageView(requireContext()).apply {
            val size = (260 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            contentDescription = "微信连接二维码"
        }
        val statusView = TextView(requireContext()).apply {
            text = "正在获取微信二维码"
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        container.addView(qr)
        container.addView(statusView)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("用微信连接 Tiyo")
            .setView(container)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        var latestContent = ""
        qr.setOnClickListener {
            latestContent.takeIf(String::isNotBlank)?.let { content ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(content))) }
            }
        }
        val onboarding = WeixinMobileOnboarding.start(
            requireContext(),
            onQrReady = { content ->
                if (!isAdded || !dialog.isShowing) return@start
                latestContent = content
                qr.setImageBitmap(qrBitmap(content, 720))
                statusView.text = "请用微信扫一扫，或点二维码尝试在微信打开"
            },
            onStatus = { value -> if (isAdded && dialog.isShowing) statusView.text = value },
            onSuccess = { result ->
                if (!isAdded) return@start
                MobilePresenceConfig.saveCredentials(
                    requireContext(),
                    PresenceChannel.WECHAT,
                    result.botId,
                    result.botToken,
                    result.userId.takeIf(String::isNotBlank)?.let(::setOf).orEmpty(),
                    result.baseUrl.takeIf { it.startsWith("https://") }.orEmpty()
                )
                MobilePresenceConfig.setEnabled(requireContext(), PresenceChannel.WECHAT, true)
                botSwitches[PresenceChannel.WECHAT]?.isChecked = true
                PresenceAdapterRegistry.get(PresenceChannel.WECHAT)?.let { adapter ->
                    adapter.stop(requireContext())
                    adapter.start(requireContext())
                }
                TiyoPresenceService.refresh(requireContext())
                if (dialog.isShowing) dialog.dismiss()
                Toast.makeText(requireContext(), "微信已经由 Tiyo 手机端连接", Toast.LENGTH_LONG).show()
                renderBotHealth()
            },
            onError = { message ->
                if (isAdded && dialog.isShowing) statusView.text = message
            }
        )
        dialog.setOnDismissListener { onboarding.cancel() }
    }

    private fun qrBitmap(content: String, size: Int): Bitmap {
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun capabilitySummary(): String = PresenceChannelRegistry.adapters.joinToString("\n") { adapter ->
        val state = when (adapter.availability) {
            PresenceAvailability.AVAILABLE -> "已可用"
            PresenceAvailability.BRIDGE_READY -> "可配置"
            PresenceAvailability.PLANNED -> "等待官方接口"
        }
        "${adapter.displayName}  ·  $state"
    }
}
