package com.koyo.screenwarden

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Today 页 v2（样本融合版）：tiyo 的关系页。
 *
 * 结构：小问候 → 宠物 Hero → 天气行卡 → 双数据卡（步数/屏幕）
 * → 今日任务（本地待办）→ tiyo 建议 → 可又的时刻 → 权限引导卡。
 *
 * 保留原 HomeFragment 的前台查邮件逻辑（vivo 上 WorkManager 会被延迟，
 * 打开 App 时主动查一次 IMAP，60 秒冷却），不能丢。
 */
class TodayFragment : Fragment(R.layout.fragment_today) {

    private lateinit var greetingText: TextView
    private lateinit var detailText: TextView
    private lateinit var dateText: TextView
    /** 可又的占位槽（她本人挂在 Activity 上，只是跟着这个槽的位置走） */
    private var catSlot: android.view.View? = null
    private var chatInvite: TextView? = null
    private lateinit var weatherCard: View
    private lateinit var weatherTemp: TextView
    private lateinit var weatherCondition: TextView
    private lateinit var weatherDate: TextView
    private lateinit var stepValue: TextView
    private lateinit var stepSub: TextView
    private lateinit var screenValue: TextView
    private lateinit var screenSub: TextView
    private lateinit var suggestText: TextView
    private lateinit var feedTitle: TextView
    private lateinit var feedContainer: LinearLayout
    private lateinit var permContainer: LinearLayout
    private lateinit var replyContainer: LinearLayout

    private var loadJob: Job? = null
    private var lastWeather: String? = null
    private var lastWeatherAt = 0L
    private var lastMailCheck = 0L

    private val activityPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }

    private val defaultSessionTitles = setOf("最近的对话", "新对话", "未命名对话")

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        greetingText = view.findViewById(R.id.today_greeting)
        detailText = view.findViewById(R.id.today_detail)
        dateText = view.findViewById(R.id.today_date)
        catSlot = view.findViewById(R.id.today_cat_slot)
        chatInvite = view.findViewById<TextView>(R.id.today_chat_invite).also { invite ->
            invite.setOnClickListener {
                (activity as? MainActivity)?.koyo?.view?.performClick()
            }
            playChatInviteOnce(invite)
        }
        weatherCard = view.findViewById(R.id.weather_card)
        weatherTemp = view.findViewById(R.id.weather_temp)
        weatherCondition = view.findViewById(R.id.weather_condition)
        weatherDate = view.findViewById(R.id.weather_date)
        stepValue = view.findViewById(R.id.glance_step_value)
        stepSub = view.findViewById(R.id.glance_step_sub)
        screenValue = view.findViewById(R.id.glance_screen_value)
        screenSub = view.findViewById(R.id.glance_screen_sub)
        suggestText = view.findViewById(R.id.suggest_text)
        feedTitle = view.findViewById(R.id.today_feed_title)
        feedContainer = view.findViewById(R.id.today_feed_container)
        permContainer = view.findViewById(R.id.today_perm_container)
        replyContainer = view.findViewById(R.id.today_reply_container)
        view.findViewById<Button>(R.id.today_autoreply_btn).setOnClickListener {
            showAutoReplyHistory()
        }

        // 可又本人在 MainActivity 的 dock 上,这里只把占位槽交给她跟随。
        // 点击进聊天的推屏转场由 MainActivity.onKoyoTapped 处理。
        (activity as? MainActivity)?.koyo?.setHeroSlot(catSlot)

        // 下拉拉宠物：拉伸系数同时给宠物和一点点头部位移
        val heroShift = resources.displayMetrics.density * 18f
        (view.findViewById<TodayScrollView>(R.id.today_scroll)).onStretch = { fraction ->
            greetingText.translationY = fraction * heroShift
            detailText.translationY = fraction * heroShift
        }

        refresh()
    }

    /** tab 切换是 show/hide：可见时把占位槽交回给她，隐藏时撤掉跟随 */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        (activity as? MainActivity)?.koyo?.setHeroSlot(if (hidden) null else catSlot)
        if (hidden) {
            chatInvite?.animate()?.cancel()
            chatInvite?.visibility = View.GONE
        }
    }

    private fun playChatInviteOnce(invite: TextView) {
        if (invitePlayedThisProcess) {
            invite.visibility = View.GONE
            return
        }
        invitePlayedThisProcess = true
        invite.visibility = View.VISIBLE
        invite.alpha = 0f
        invite.translationY = dp(6).toFloat()
        invite.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(280L)
            .setDuration(360L)
            .withEndAction {
                invite.animate()
                    .alpha(0f)
                    .translationY(-dp(4).toFloat())
                    .setStartDelay(2_000L)
                    .setDuration(320L)
                    .withEndAction { invite.visibility = View.GONE }
                    .start()
            }
            .start()
    }

    fun refresh() {
        val ctx = context ?: return
        renderGreeting(ctx)
        renderWeather()
        renderPermGuide(ctx)
        loadGlance(ctx)
        loadWeather()
        if (checkUsagePerm()) checkMailAndReport()
    }

    // ---------- 可又帮你回：追溯 ----------

    /** 弹窗展示"可又帮你回"的拟写历史，点一条可复制那条回复 */
    private fun showAutoReplyHistory() {
        val ctx = context ?: return
        val records = AutoReplyHistory.load(ctx)

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        if (records.isEmpty()) {
            content.addView(
                TextView(ctx).apply {
                    text = "还没有自动回复记录"
                    textSize = 14f
                    setTextColor(ctx.getColor(R.color.d_ink_3))
                    setPadding(dp(6), dp(20), dp(6), dp(20))
                }
            )
        } else {
            records.forEach { r ->
                val statusColor = when (r.status) {
                    AutoReplyHistory.STATUS_OPENED -> ctx.getColor(R.color.tiyo_accent)
                    AutoReplyHistory.STATUS_COPIED -> ctx.getColor(R.color.d_ink)
                    else -> ctx.getColor(R.color.d_ink_3)
                }
                val row = TextView(ctx).apply {
                    text = buildString {
                        append(statusMark(r.status))
                        append("  ")
                        append(r.contact)
                        append("  ")
                        append(timeLabel(r.timeMillis))
                        append('\n')
                        if (r.message.isNotBlank()) append("收到：").append(r.message).append('\n')
                        append("拟：").append(r.reply)
                    }
                    textSize = 14f
                    setTextColor(statusColor)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setBackgroundResource(R.drawable.btn_secondary_bg)
                    setOnClickListener {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("reply", r.reply))
                        Toast.makeText(ctx, "已复制：${r.reply}", Toast.LENGTH_SHORT).show()
                    }
                }
                content.addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                )
            }
        }

        val scroll = ScrollView(ctx).apply { addView(content) }
        scroll.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            dp(if (records.size > 6) 420 else 320)
        )

        AlertDialog.Builder(ctx)
            .setTitle("${CompanionProfileStore.activeName(requireContext())}帮你回")
            .setView(scroll)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun statusMark(status: Int): String = when (status) {
        AutoReplyHistory.STATUS_OPENED -> "✓已用"
        AutoReplyHistory.STATUS_COPIED -> "✓已复制"
        else -> "·已弹"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------- 问候语 ----------

    private fun renderGreeting(context: Context) {
        val now = Calendar.getInstance()
        val recent = TiyoSessionStore.sessions(context).maxByOrNull { it.updatedAt }
        val ageHours = recent?.let {
            (System.currentTimeMillis() - it.updatedAt).toDouble() / 3_600_000.0
        }
        val result = GreetingComposer.compose(
            GreetingComposer.Input(
                hour = now.get(Calendar.HOUR_OF_DAY),
                weather = lastWeather,
                recentChatTitle = recent?.title,
                recentChatAgeHours = ageHours,
                userName = UserPrefs.displayName(context)
            )
        )
        greetingText.text = result.greeting
        if (result.detail.isNullOrBlank()) {
            detailText.visibility = View.GONE
        } else {
            detailText.text = result.detail
            detailText.visibility = View.VISIBLE
        }
        dateText.text = dateLabel(now)
    }

    private fun dateLabel(now: Calendar): String = "%d月%d日 %s".format(
        now.get(Calendar.MONTH) + 1,
        now.get(Calendar.DAY_OF_MONTH),
        arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[now.get(Calendar.DAY_OF_WEEK) - 1]
    )

    // ---------- 天气行卡 ----------

    private fun renderWeather() {
        val raw = lastWeather?.takeIf { it.isNotBlank() }
        if (raw == null) {
            weatherCard.visibility = View.GONE
            return
        }
        // WeatherFetcher 格式："多云 28°C"
        val condition = raw.substringBefore(" ")
        val temp = raw.substringAfter(" ", "")
        weatherTemp.text = temp
        weatherCondition.text = condition
        weatherDate.text = dateLabel(Calendar.getInstance())
        weatherCard.visibility = View.VISIBLE
    }

    // ---------- 双数据卡 + 建议 + 时刻流 ----------

    private fun loadGlance(context: Context) {
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            val steps = try {
                StepCounterCollector.refreshAndGetSteps(context)
            } catch (_: Exception) { -1 }

            val (screenMinutes, topApp) = if (checkUsagePerm()) {
                withContext(Dispatchers.IO) { queryScreenStats() }
            } else (-1 to null)

            stepValue.text = if (steps > 0) "%,d 步".format(steps) else "--"
            stepSub.text = if (steps > 0) "%s km".format(StepCounterCollector.stepsToKm(steps)) else ""
            screenValue.text = when {
                screenMinutes < 0 -> "--"
                screenMinutes >= 60 -> "%dh %02dm".format(screenMinutes / 60, screenMinutes % 60)
                else -> "${screenMinutes}min"
            }
            screenSub.text = screenSubText(context, screenMinutes, topApp)

            renderSuggestion(steps, screenMinutes)
            loadFeed(steps, screenMinutes)
        }
    }

    /** 与昨日对比（攒够历史才有），否则显示最常用 App */
    private fun screenSubText(context: Context, todayMinutes: Int, topApp: String?): String {
        if (todayMinutes < 0) return ""
        val prefs = context.getSharedPreferences("tiyo_screen_history", Context.MODE_PRIVATE)
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val yesterdayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(
            Date(System.currentTimeMillis() - 86_400_000L)
        )
        val yesterday = prefs.getInt(yesterdayKey, -1)
        if (prefs.getInt(todayKey, -1) != todayMinutes) {
            prefs.edit().putInt(todayKey, todayMinutes).apply()
        }
        return if (yesterday > 0) {
            val delta = (todayMinutes - yesterday) * 100 / yesterday
            "比昨日 %+d%%".format(delta)
        } else if (!topApp.isNullOrBlank()) {
            "最常用：$topApp"
        } else ""
    }

    /** 与 ScreenUsageCollector 同口径：今日前台总时长（分钟）+ 最常用 App 名 */
    private fun queryScreenStats(): Pair<Int, String?> {
        return try {
            val usm = requireContext().getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, cal.timeInMillis, System.currentTimeMillis()
            ).filter { it.totalTimeInForeground > 0 }
                .sortedByDescending { it.totalTimeInForeground }
            val total = stats.take(15).sumOf { it.totalTimeInForeground }
            val top = stats.firstOrNull()?.let {
                try {
                    val pm = requireContext().packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(it.packageName, 0)).toString()
                } catch (_: Exception) { null }
            }
            ((total / 60_000L).toInt() to top)
        } catch (_: Exception) { (-1 to null) }
    }

    private fun renderSuggestion(steps: Int, screenMinutes: Int) {
        val ctx = context ?: return
        val recent = TiyoSessionStore.sessions(ctx).maxByOrNull { it.updatedAt }
        suggestText.text = SuggestionComposer.compose(
            SuggestionComposer.Input(
                hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                recentChatTitle = recent?.title,
                recentChatAgeHours = recent?.let {
                    (System.currentTimeMillis() - it.updatedAt).toDouble() / 3_600_000.0
                },
                stepsToday = steps,
                screenMinutesToday = screenMinutes,
                weather = lastWeather
            )
        )
    }

    // ---------- 温暖引导卡（权限缺失才出现） ----------

    private data class PermGuide(
        val granted: Boolean,
        val title: String,
        val reason: String,
        val action: () -> Unit
    )

    private fun renderPermGuide(context: Context) {
        permContainer.removeAllViews()
        val guides = listOf(
            PermGuide(
                checkUsagePerm(),
                "看看你的一天",
                "屏幕时间统计需要它"
            ) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
            PermGuide(
                checkStoragePerm(),
                "帮你整理文件",
                "读写手机里的文件需要它"
            ) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                    val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    i.data = Uri.parse("package:${context.packageName}")
                    startActivity(i)
                }
            },
            PermGuide(
                checkActivityPerm(),
                "陪你数每天的步数",
                "步数统计需要它"
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    activityPermLauncher.launch(android.Manifest.permission.ACTIVITY_RECOGNITION)
                }
            },
            PermGuide(
                checkNotifListenerPerm(),
                "知道谁在找你",
                "帮你留意和回复消息需要它"
            ) {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Exception) {}
            }
        ).filter { !it.granted }

        if (guides.isEmpty()) return

        val card = layoutInflater.inflate(R.layout.item_today_perm_card, permContainer, false)
        val rows = card.findViewById<LinearLayout>(R.id.perm_rows)
        guides.forEach { guide ->
            val row = layoutInflater.inflate(R.layout.item_today_perm_row, rows, false)
            row.findViewById<TextView>(R.id.perm_row_title).text = guide.title
            row.findViewById<TextView>(R.id.perm_row_reason).text = guide.reason
            row.findViewById<Button>(R.id.perm_row_button).setOnClickListener { guide.action() }
            rows.addView(row)
        }
        permContainer.addView(card)
    }

    private fun checkUsagePerm(): Boolean {
        val ctx = context ?: return false
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        return ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), ctx.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun checkStoragePerm(): Boolean {
        return try {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    android.os.Environment.isExternalStorageManager()
        } catch (_: Exception) { false }
    }

    private fun checkActivityPerm(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                requireContext(), android.Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun checkNotifListenerPerm(): Boolean {
        val ctx = context ?: return false
        return NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }

    // ---------- 时刻流：tiyo 记得的事（差异化核心） ----------

    private data class Moment(
        val timeMillis: Long,
        val text: String,
        val memory: MemoryTimelineLoader.MemoryItem? = null,
        val important: Boolean = false
    )

    private fun loadFeed(steps: Int, screenMinutes: Int) {
        CoroutineScope(Dispatchers.Main).launch {
            val moments = withContext(Dispatchers.IO) { buildMoments(steps, screenMinutes) }
            renderFeed(moments)
        }
    }

    private fun buildMoments(steps: Int, screenMinutes: Int): List<Moment> {
        val ctx = context ?: return emptyList()
        val now = System.currentTimeMillis()
        val out = mutableListOf<Moment>()

        // 记忆：3 天内更新的，最多 5 条
        MemoryTimelineLoader.scan(ctx)
            .filter { now - it.updatedMillis < 3 * 86_400_000L }
            .sortedByDescending { it.updatedMillis }
            .take(5)
            .forEach { m ->
                val imp = MemoryTimelineLoader.isImportant(ctx, m.name)
                val prefix = if (imp) "⭐ " else ""
                out += Moment(m.updatedMillis, prefix + "我记下了：${m.description}", m, imp)
            }

        // 聊天：2 天内有标题的会话，最多 3 条
        TiyoSessionStore.sessions(ctx)
            .filter { it.title !in defaultSessionTitles && now - it.updatedAt < 2 * 86_400_000L }
            .take(3)
            .forEach { out += Moment(it.updatedAt, "我们聊了「${it.title}」") }

        // 观察：此刻的数据变成关心，不报数字报表
        if (steps >= 10000) out += Moment(now, "你今天走了 %,d 步，有点厉害".format(steps))
        if (screenMinutes >= 360) {
            out += Moment(now, "今天你盯着屏幕 %dh 了，我没吵你".format(screenMinutes / 60))
        }

        return out.sortedWith(
            compareByDescending<Moment> { it.important }.thenByDescending { it.timeMillis }
        ).take(8)
    }

    private fun renderFeed(moments: List<Moment>) {
        val ctx = context ?: return
        feedContainer.removeAllViews()
        feedTitle.visibility = View.VISIBLE

        if (moments.isEmpty()) {
            // 空态：Agent 还没攒下记忆时，不失联，邀请他来说话
            val row = layoutInflater.inflate(R.layout.item_today_moment, feedContainer, false)
            row.findViewById<TextView>(R.id.moment_time).text = "现在"
            row.findViewById<TextView>(R.id.moment_text).apply {
                text = "${CompanionProfileStore.activeName(requireContext())}还在认识你。来聊聊天吧"
                setTextColor(ctx.getColor(R.color.d_ink_3))
            }
            row.setOnClickListener { (activity as? MainActivity)?.openChat() }
            feedContainer.addView(row)
            return
        }

        moments.forEach { moment ->
            val row = layoutInflater.inflate(R.layout.item_today_moment, feedContainer, false)
            row.findViewById<TextView>(R.id.moment_time).text = timeLabel(moment.timeMillis)
            row.findViewById<TextView>(R.id.moment_text).text = moment.text

            // 记忆管教：长按 → 重要 / 忘掉
            moment.memory?.let { memory ->
                row.setOnLongClickListener {
                    val toggle = if (moment.important) "不再重要" else "这个很重要"
                    AlertDialog.Builder(ctx)
                        .setTitle("这条记忆")
                        .setItems(arrayOf(toggle, "忘掉这个")) { _, which ->
                            when (which) {
                                0 -> MemoryTimelineLoader.setImportant(ctx, memory.name, !moment.important)
                                1 -> {
                                    MemoryTimelineLoader.forget(memory)
                                    Toast.makeText(ctx, "好，我忘掉它了", Toast.LENGTH_SHORT).show()
                                }
                            }
                            refresh()
                        }
                        .show()
                    true
                }
            }
            feedContainer.addView(row)
        }
    }

    private fun timeLabel(millis: Long): String {
        if (System.currentTimeMillis() - millis < 2 * 60_000L) return "现在"
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = millis }
        return when {
            now.get(Calendar.DATE) == then.get(Calendar.DATE) &&
                now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ->
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
            now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) == 1 &&
                now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> "昨天"
            else -> SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(millis))
        }
    }

    // ---------- 天气（10 分钟缓存） ----------

    private fun loadWeather() {
        val now = System.currentTimeMillis()
        if (now - lastWeatherAt < 600_000) return
        lastWeatherAt = now
        CoroutineScope(Dispatchers.IO).launch {
            val w = WeatherFetcher.fetch()
            if (w.isNotEmpty()) {
                lastWeather = w
                withContext(Dispatchers.Main) {
                    renderWeather()
                    context?.let { renderGreeting(it) }
                }
            }
        }
    }

    // ---------- 前台查邮件（从 HomeFragment 保留，勿删） ----------

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
                android.util.Log.i("TodayFragment", "Command from foreground: ${cmd::class.simpleName}")
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

    override fun onDestroyView() {
        loadJob?.cancel()
        chatInvite?.animate()?.cancel()
        chatInvite = null
        catSlot = null
        // 视图没了就别再让停靠层跟随这个占位槽
        (activity as? MainActivity)?.koyo?.setHeroSlot(null)
        super.onDestroyView()
    }

    private companion object {
        var invitePlayedThisProcess = false
    }
}
