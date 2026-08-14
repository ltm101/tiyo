package com.koyo.screenwarden

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.RadioGroup
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 守护与权限（我的 → 子页）。
 * 原 HomeFragment 的权限管理 + Agent 能力整体平移，逻辑不变：
 * 授权按钮、开关初始化顺序（先设值再挂监听）、自动回复邮件上报都保持原样。
 */
class GuardSettingsFragment : Fragment(R.layout.fragment_guard_settings) {

    private lateinit var grantUsageBtn: Button
    private lateinit var grantStorageBtn: Button
    private lateinit var agentNotifBtn: Button
    private lateinit var agentSmsBtn: Button
    private lateinit var agentPushBtn: Button
    private lateinit var agentLocBtn: Button
    private lateinit var autoReplySwitch: SwitchCompat
    private lateinit var friendshipProfileSwitch: SwitchCompat
    private lateinit var friendshipProfileCount: TextView
    private lateinit var companionPerceptionSwitch: SwitchCompat
    private lateinit var companionAccessibilityBtn: Button

    private val smsPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }

    private val pushPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }

    private val locPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        grantUsageBtn = view.findViewById(R.id.grant_usage_btn)
        grantStorageBtn = view.findViewById(R.id.grant_storage_btn)
        agentNotifBtn = view.findViewById(R.id.agent_notif_btn)
        agentSmsBtn = view.findViewById(R.id.agent_sms_btn)
        agentPushBtn = view.findViewById(R.id.agent_push_btn)
        agentLocBtn = view.findViewById(R.id.agent_loc_btn)

        grantUsageBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        grantStorageBtn.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                i.data = Uri.parse("package:${requireContext().packageName}")
                startActivity(i)
            }
        }

        // 通知监听：跳系统「通知使用权」设置
        agentNotifBtn.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Exception) {}
        }

        agentSmsBtn.setOnClickListener {
            smsPermLauncher.launch(Manifest.permission.SEND_SMS)
        }

        agentPushBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pushPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        agentLocBtn.setOnClickListener {
            val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            locPermLauncher.launch(perms.toTypedArray())
        }

        // 定位开关
        val locSwitch = view.findViewById<SwitchCompat>(R.id.loc_switch)
        locSwitch.isChecked = LocationCollector.isEnabled(requireContext())
        locSwitch.setOnCheckedChangeListener { _, isChecked ->
            LocationCollector.setEnabled(requireContext(), isChecked)
        }

        // 可又帮你回：总开关。先按存储状态设好再挂监听，避免初始化触发发信
        autoReplySwitch = view.findViewById(R.id.agent_autoreply_switch)
        autoReplySwitch.isChecked = AutoReplyManager.isOn(requireContext())
        autoReplySwitch.setOnCheckedChangeListener { _, isChecked ->
            AutoReplyManager.setOn(requireContext(), isChecked)
            val subject = if (isChecked) "tiyo-ar-on" else "tiyo-ar-off"
            CoroutineScope(Dispatchers.IO).launch {
                EmailSender.sendReport(
                    if (isChecked) "auto-reply on" else "auto-reply off",
                    MailConfig.agentEmail(), subject
                )
            }
            android.widget.Toast.makeText(
                requireContext(),
                if (isChecked) "${CompanionProfileStore.activeName(requireContext())}帮你回：已开启"
                else "${CompanionProfileStore.activeName(requireContext())}帮你回：已关闭",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        friendshipProfileSwitch = view.findViewById(R.id.friendship_profile_switch)
        friendshipProfileSwitch.isChecked = FriendshipProfileStore.isEnabled(requireContext())
        friendshipProfileSwitch.setOnCheckedChangeListener { _, isChecked ->
            FriendshipProfileStore.setEnabled(requireContext(), isChecked)
            Toast.makeText(
                requireContext(),
                if (isChecked) "好友相处档案已开启" else "好友相处档案已暂停",
                Toast.LENGTH_SHORT
            ).show()
            refreshFriendshipCount()
        }
        friendshipProfileCount = view.findViewById(R.id.friendship_profile_count)
        view.findViewById<View>(R.id.btn_friendship_profiles).setOnClickListener {
            FriendshipProfilesDialog.show(requireContext())
        }

        companionPerceptionSwitch = view.findViewById(R.id.companion_perception_switch)
        companionPerceptionSwitch.isChecked = CompanionPerceptionPrefs.isEnabled(requireContext())
        companionPerceptionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                CompanionPerceptionPrefs.setEnabled(requireContext(), false)
                return@setOnCheckedChangeListener
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                companionPerceptionSwitch.isChecked = false
                Toast.makeText(requireContext(), "需要 Android 11 或更高版本", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            if (!checkPushPerm()) {
                companionPerceptionSwitch.isChecked = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pushPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                Toast.makeText(requireContext(), "先允许通知，感知进行时必须让你看得见", Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            if (CompanionPerceptionPrefs.hasConsent(requireContext())) {
                CompanionPerceptionPrefs.setEnabled(requireContext(), true)
                if (!checkAccessibilityPerm()) openAccessibilitySettings()
            } else {
                companionPerceptionSwitch.isChecked = false
                showCompanionDisclosure()
            }
        }

        val douyinSwitch = view.findViewById<SwitchCompat>(R.id.companion_douyin_switch)
        douyinSwitch.isChecked = CompanionPerceptionPrefs.isTargetEnabled(
            requireContext(), CompanionTargets.DOUYIN
        )
        douyinSwitch.setOnCheckedChangeListener { _, checked ->
            CompanionPerceptionPrefs.setTargetEnabled(requireContext(), CompanionTargets.DOUYIN, checked)
        }
        val wangzheSwitch = view.findViewById<SwitchCompat>(R.id.companion_wangzhe_switch)
        wangzheSwitch.isChecked = CompanionPerceptionPrefs.isTargetEnabled(
            requireContext(), CompanionTargets.WANGZHE
        )
        wangzheSwitch.setOnCheckedChangeListener { _, checked ->
            CompanionPerceptionPrefs.setTargetEnabled(requireContext(), CompanionTargets.WANGZHE, checked)
        }
        companionAccessibilityBtn = view.findViewById(R.id.companion_accessibility_btn)
        companionAccessibilityBtn.setOnClickListener { openAccessibilitySettings() }

        // 蒸馏我的回复风格：跳转回复性格页（可导入聊天记录 → 分析生成风格）
        view.findViewById<View>(R.id.btn_distill_style).setOnClickListener {
            (parentFragment as? MeTabFragment)?.openReplyStyle()
                ?: android.widget.Toast.makeText(
                    requireContext(),
                    "去「我的 → 回复性格」蒸馏",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
        }

        // 主动消息频率：自定义每天条数（默认 3）
        val freqGroup = view.findViewById<RadioGroup>(R.id.proactive_freq_group)
        freqGroup.check(
            when (ProactiveMessenger.activePerDay(requireContext())) {
                6 -> R.id.proactive_freq_6
                10 -> R.id.proactive_freq_10
                20 -> R.id.proactive_freq_20
                else -> R.id.proactive_freq_3
            }
        )
        freqGroup.setOnCheckedChangeListener { _, checked ->
            val n = when (checked) {
                R.id.proactive_freq_6 -> 6
                R.id.proactive_freq_10 -> 10
                R.id.proactive_freq_20 -> 20
                else -> 3
            }
            ProactiveMessenger.setActivePerDay(requireContext(), n)
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    fun refresh() {
        if (!::grantUsageBtn.isInitialized) return
        grantUsageBtn.text = if (checkUsagePerm()) "✓ 已授权" else "去授权"
        grantStorageBtn.text = if (checkStoragePerm()) "✓ 已授权" else "去授权"
        agentNotifBtn.text = if (checkNotifListenerPerm()) "✓ 已开启" else "去开启"
        agentSmsBtn.text = if (checkSmsPerm()) "✓ 已授权" else "去授权"
        agentPushBtn.text = if (checkPushPerm()) "✓ 已授权" else "去授权"
        agentLocBtn.text = if (checkLocPerm()) "✓ 已授权" else "去授权"
        if (::companionAccessibilityBtn.isInitialized) {
            companionAccessibilityBtn.text = if (checkAccessibilityPerm()) "✓ 无障碍已开启" else "去开启无障碍"
        }
        if (::friendshipProfileCount.isInitialized) refreshFriendshipCount()
        if (::companionPerceptionSwitch.isInitialized &&
            companionPerceptionSwitch.isChecked != CompanionPerceptionPrefs.isEnabled(requireContext())
        ) {
            companionPerceptionSwitch.isChecked = CompanionPerceptionPrefs.isEnabled(requireContext())
        }
    }

    private fun checkUsagePerm(): Boolean {
        val ops = requireContext().getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), requireContext().packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun checkStoragePerm(): Boolean {
        return try {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    android.os.Environment.isExternalStorageManager()
        } catch (_: Exception) { false }
    }

    private fun checkNotifListenerPerm(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(requireContext())
            .contains(requireContext().packageName)
    }

    private fun checkSmsPerm(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkPushPerm(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun checkLocPerm(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showCompanionDisclosure() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("开启知情式陪伴感知")
            .setMessage(
                "只在你选中的应用位于前台时，tiyo 才会隔一段时间取一张单帧\n\n" +
                    "本机会先过滤重复和空白画面，有效画面会发送至视觉服务判断是否值得互动，原始截图不会写入文件或长期保存\n\n" +
                    "银行卡、支付、密码和私聊画面不会被用来互动，感知进行时通知栏会持续显示，也可以随时一键暂停"
            )
            .setPositiveButton("同意并开启") { _, _ ->
                CompanionPerceptionPrefs.grantConsent(requireContext())
                companionPerceptionSwitch.isChecked = true
                if (!checkAccessibilityPerm()) openAccessibilitySettings()
            }
            .setNegativeButton("暂不开启", null)
            .show()
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun checkAccessibilityPerm(): Boolean {
        val expected = ComponentName(requireContext(), WechatAutoReplyService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun refreshFriendshipCount() {
        val count = FriendshipProfileStore.all(requireContext()).size
        friendshipProfileCount.text = if (count == 0) "还没有档案 →" else "$count 位 →"
    }
}
