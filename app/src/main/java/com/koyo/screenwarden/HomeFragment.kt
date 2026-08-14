package com.koyo.screenwarden

import android.Manifest
import android.app.AppOpsManager
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import kotlinx.coroutines.*

class HomeFragment : Fragment(R.layout.fragment_home) {

    private lateinit var statusText: TextView
    private lateinit var grantUsageBtn: Button
    private lateinit var grantStorageBtn: Button
    private lateinit var agentNotifBtn: Button
    private lateinit var agentSmsBtn: Button
    private lateinit var agentPushBtn: Button
    private lateinit var autoReplySwitch: SwitchCompat
    private lateinit var quickSummaryCard: View
    private lateinit var quickTotalTime: TextView
    private lateinit var quickTopApp: TextView
    private lateinit var quickSteps: TextView
    private lateinit var weatherText: TextView
    private var loadJob: Job? = null

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

        statusText = view.findViewById(R.id.home_status_text)
        grantUsageBtn = view.findViewById(R.id.grant_usage_btn)
        grantStorageBtn = view.findViewById(R.id.grant_storage_btn)
        agentNotifBtn = view.findViewById(R.id.agent_notif_btn)
        agentSmsBtn = view.findViewById(R.id.agent_sms_btn)
        agentPushBtn = view.findViewById(R.id.agent_push_btn)
        quickSummaryCard = view.findViewById(R.id.quick_summary_card)
        quickTotalTime = view.findViewById(R.id.quick_total_time)
        quickTopApp = view.findViewById(R.id.quick_top_app)
        quickSteps = view.findViewById(R.id.quick_steps)
        weatherText = view.findViewById(R.id.home_weather)

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

        // 发短信：运行时权限
        agentSmsBtn.setOnClickListener {
            smsPermLauncher.launch(Manifest.permission.SEND_SMS)
        }

        // 推送通知：Android 13+ 才需运行时权限
        agentPushBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pushPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 定位
        val locBtn = view.findViewById<Button>(R.id.agent_loc_btn)
        locBtn.setOnClickListener {
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

        refresh()
    }

    fun refresh() {
        val usageOk = checkUsagePerm()
        val storageOk = checkStoragePerm()
        val count = listOf(usageOk, storageOk).count { it }

        statusText.text = when (count) {
            0 -> "请授权权限后开始使用"
            1 -> "还差一步"
            2 -> "tiyo 正在守护你"
            else -> ""
        }
        grantUsageBtn.text = if (usageOk) "✓ 已授权" else "去授权"
        grantStorageBtn.text = if (storageOk) "✓ 已授权" else "去授权"

        // Agent 能力授权状态
        agentNotifBtn.text = if (checkNotifListenerPerm()) "✓ 已开启" else "去开启"
        agentSmsBtn.text = if (checkSmsPerm()) "✓ 已授权" else "去授权"
        agentPushBtn.text = if (checkPushPerm()) "✓ 已授权" else "去授权"
        val locBtn = view?.findViewById<Button>(R.id.agent_loc_btn)
        locBtn?.text = if (checkLocPerm()) "✓ 已授权" else "去授权"

        if (usageOk) {
            loadQuickSummary()
            checkMailAndReport()
        } else {
            quickSummaryCard.visibility = View.GONE
        }
        loadWeather()
    }

    private fun loadWeather() {
        CoroutineScope(Dispatchers.IO).launch {
            val w = WeatherFetcher.fetch()
            if (w.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    weatherText.text = w
                }
            }
        }
    }

    private var lastMailCheck = 0L

    /**
     * 前台触发邮件检查：打开 App 时主动查一次 IMAP，
     * 绕过 WorkManager 在 vivo 上被延迟的问题。
     * 冷却 60 秒，避免与 Worker 同时抢同一封邮件导致重复发送。
     */
    private fun checkMailAndReport() {
        val now = System.currentTimeMillis()
        if (now - lastMailCheck < 60_000) return
        lastMailCheck = now
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cmd = MailChecker.checkForCommand() ?: return@launch

                android.util.Log.i("HomeFragment", "Command from foreground: ${cmd::class.simpleName}")
                val collector = ScreenUsageCollector(requireContext())
                val app = requireContext().applicationContext

                when (cmd) {
                    is Command.Report -> {
                        val screenReport = collector.collectDailyUsage()
                        val steps = StepCounterCollector.refreshAndGetSteps(requireContext())
                        val stepSummary = if (steps >= 0) {
                            val km = StepCounterCollector.stepsToKm(steps)
                            val kcal = StepCounterCollector.stepsToKcal(steps)
                            "\n---\nSteps today: %,d  (≈%s km, ≈%,d kcal)".format(steps, km, kcal)
                        } else ""
                        EmailSender.sendReport(screenReport + stepSummary, MailConfig.agentEmail())
                    }
                    is Command.FileQuery -> {
                        if (!FileManager.isAllowed(cmd.path)) {
                            EmailSender.sendReport("拒绝访问: ${cmd.path}", MailConfig.agentEmail(), "tiyo-file")
                        } else {
                            EmailSender.sendReport(
                                FileManager.listDir(cmd.path), MailConfig.agentEmail(), "tiyo-file"
                            )
                        }
                    }
                    // 动作类：前台若先读到（并标记已读），必须在这里执行，否则动作丢失
                    is Command.Ring ->
                        EmailSender.sendReport(ActionExecutor.ring(app), MailConfig.agentEmail(), "tiyo-ack")
                    is Command.Notify ->
                        EmailSender.sendReport(ActionExecutor.notify(app, cmd.text), MailConfig.agentEmail(), "tiyo-ack")
                    is Command.LaunchApp ->
                        EmailSender.sendReport(ActionExecutor.launchApp(app, cmd.pkg), MailConfig.agentEmail(), "tiyo-ack")
                    is Command.SendSms ->
                        EmailSender.sendReport(ActionExecutor.sendSms(app, cmd.number, cmd.text), MailConfig.agentEmail(), "tiyo-ack")
                    is Command.Suggest ->
                        EmailSender.sendReport(ActionExecutor.suggestReply(app, cmd.contact, cmd.text), MailConfig.agentEmail(), "tiyo-ack")
                }
            } catch (_: Exception) {}
        }
    }

    private fun loadQuickSummary() {
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    ScreenUsageCollector(requireContext()).collectDailyUsage()
                }
                val lines = report.lines()

                val totalLine = lines.find { it.startsWith("Total:") }
                if (totalLine != null) {
                    quickTotalTime.text = totalLine.removePrefix("Total: ").trim()
                    quickSummaryCard.visibility = View.VISIBLE
                }

                val appLine = lines.find { it.contains(": ") && (it.contains("h ") || it.contains("min")) }
                if (appLine != null) {
                    val label = appLine.substringBefore(": ")
                    quickTopApp.text = "使用最多：$label"
                }

                // 步数：主动查询传感器，确保拿到最新值
                val steps = StepCounterCollector.refreshAndGetSteps(requireContext())
                if (steps > 0) {
                    val km = StepCounterCollector.stepsToKm(steps)
                    quickSteps.text = "🚶 今日步数：%,d 步（≈%s km）".format(steps, km)
                } else {
                    quickSteps.text = "🚶 今日步数：--"
                }
            } catch (_: Exception) {
                quickSummaryCard.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadJob?.cancel()
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
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                    && android.os.Environment.isExternalStorageManager()
        } catch (_: Exception) { false }
    }

    /** 通知使用权是否已开启 */
    private fun checkNotifListenerPerm(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(requireContext())
            .contains(requireContext().packageName)
    }

    /** 发短信权限 */
    private fun checkSmsPerm(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 推送通知权限（Android 13 以下默认有）*/
    private fun checkPushPerm(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    /** 定位权限 */
    private fun checkLocPerm(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
