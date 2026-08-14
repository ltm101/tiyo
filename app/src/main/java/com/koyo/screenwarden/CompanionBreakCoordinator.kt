package com.koyo.screenwarden

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Offers one low-pressure break after a continuous eligible session
 *
 * It only launches apps the user explicitly accepts, never runs while the keyboard is open, and
 * limits invitations so a companion gesture cannot turn into a timer notification
 */
internal class CompanionBreakCoordinator(
    private val activity: AppCompatActivity,
    private val root: FrameLayout
) {
    enum class Surface { NONE, CHAT, DESK }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var surface = Surface.NONE
    private var foreground = true
    private var prompt: CompanionBreakInvitationView? = null
    private val invitationCheck = Runnable(::checkInvitation)

    fun setSurface(next: Surface) {
        if (surface == next) return
        surface = next
        prompt?.dismiss()
        prompt = null
        handler.removeCallbacks(invitationCheck)
        if (foreground && next != Surface.NONE) arm(DWELL_MS)
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        handler.removeCallbacks(invitationCheck)
        if (!value) {
            prompt?.dismiss()
            prompt = null
        } else if (surface != Surface.NONE) {
            // Background time is not desk/chat companionship time, so resume starts a fresh stay
            arm(DWELL_MS)
        }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        prompt?.dismiss()
        prompt = null
    }

    private fun arm(delayMs: Long) {
        handler.removeCallbacks(invitationCheck)
        handler.postDelayed(invitationCheck, delayMs.coerceAtLeast(1_000L))
    }

    private fun checkInvitation() {
        if (!foreground || surface == Surface.NONE || !root.isAttachedToWindow || activity.isFinishing) return
        if (!activity.hasWindowFocus() || keyboardVisible()) {
            arm(BUSY_RETRY_MS)
            return
        }
        if (surface == Surface.DESK && DeskPlanStore.hasUnfinished(activity)) {
            val recentlyCompleted = System.currentTimeMillis() - DeskPlanStore.lastCompletedAt(activity) < 30 * 60_000L
            if (!recentlyCompleted) {
                arm(10 * 60_000L)
                return
            }
        }
        val now = System.currentTimeMillis()
        val today = dayKey(now)
        val storedDay = prefs.getString(KEY_DAY, "")
        val count = if (storedDay == today) prefs.getInt(KEY_COUNT, 0) else 0
        if (count >= MAX_PER_DAY) return
        val last = prefs.getLong(KEY_LAST_AT, 0L)
        val remainingCooldown = COOLDOWN_MS - (now - last)
        if (remainingCooldown > 0L) {
            arm(remainingCooldown)
            return
        }

        val candidate = InstalledLeisureApps.pick(activity.packageManager, activity.packageName)
        if (candidate == null) {
            arm(NO_APP_RETRY_MS)
            return
        }
        prefs.edit()
            .putString(KEY_DAY, today)
            .putInt(KEY_COUNT, count + 1)
            .putLong(KEY_LAST_AT, now)
            .apply()
        showInvitation(candidate)
    }

    private fun keyboardVisible(): Boolean =
        ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun showInvitation(candidate: InstalledLeisureApps.Candidate) {
        val view = CompanionBreakInvitationView(
            activity,
            candidate,
            onAccept = {
                prompt = null
                launch(candidate)
                if (surface != Surface.NONE && foreground) arm(DWELL_MS)
            },
            onDismiss = {
                prompt = null
                if (surface != Surface.NONE && foreground) arm(DWELL_MS)
            }
        )
        prompt = view
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        view.reveal()
    }

    private fun launch(candidate: InstalledLeisureApps.Candidate) {
        val intent = activity.packageManager.getLaunchIntentForPackage(candidate.packageName) ?: return
        runCatching { activity.startActivity(intent) }
    }

    private fun dayKey(time: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date(time))

    private companion object {
        const val PREFS = "companion_break_invitation"
        const val KEY_DAY = "day"
        const val KEY_COUNT = "count"
        const val KEY_LAST_AT = "last_at"
        const val DWELL_MS = 20 * 60_000L
        const val BUSY_RETRY_MS = 2 * 60_000L
        const val NO_APP_RETRY_MS = 30 * 60_000L
        const val COOLDOWN_MS = 2 * 60 * 60_000L
        const val MAX_PER_DAY = 3
    }
}

internal object InstalledLeisureApps {
    enum class Kind { MUSIC, DOUYIN, GAME }
    data class Candidate(val kind: Kind, val label: String, val packageName: String)

    private val musicPackages = listOf(
        "com.netease.cloudmusic" to "网易云音乐",
        "com.tencent.qqmusic" to "QQ音乐",
        "com.kugou.android" to "酷狗音乐",
        "com.luna.music" to "汽水音乐"
    )
    private const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"

    fun pick(packageManager: PackageManager, ownPackage: String): Candidate? {
        val groups = mutableListOf<List<Candidate>>()
        val music = musicPackages.mapNotNull { (packageName, fallback) ->
            launchable(packageManager, packageName)?.let { Candidate(Kind.MUSIC, label(packageManager, packageName, fallback), packageName) }
        }
        if (music.isNotEmpty()) groups += music
        launchable(packageManager, DOUYIN_PACKAGE)?.let {
            groups += listOf(Candidate(Kind.DOUYIN, label(packageManager, DOUYIN_PACKAGE, "抖音"), DOUYIN_PACKAGE))
        }
        val games = installedGames(packageManager, ownPackage)
        if (games.isNotEmpty()) groups += games
        val selectedGroup = groups.randomOrNull(Random.Default) ?: return null
        return selectedGroup.random(Random.Default)
    }

    private fun installedGames(packageManager: PackageManager, ownPackage: String): List<Candidate> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val activities = packageManager.queryIntentActivities(launcher, 0)
        val excluded = musicPackages.mapTo(mutableSetOf(DOUYIN_PACKAGE, ownPackage)) { it.first }
        return activities.asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName !in excluded }
            .filter { info ->
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && info.category == ApplicationInfo.CATEGORY_GAME
            }
            .distinctBy(ApplicationInfo::packageName)
            .map { info -> Candidate(Kind.GAME, packageManager.getApplicationLabel(info).toString(), info.packageName) }
            .take(24)
            .toList()
    }

    private fun launchable(packageManager: PackageManager, packageName: String): Intent? =
        packageManager.getLaunchIntentForPackage(packageName)

    @Suppress("DEPRECATION")
    private fun label(packageManager: PackageManager, packageName: String, fallback: String): String =
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(fallback)
}

private class CompanionBreakInvitationView(
    context: Context,
    candidate: InstalledLeisureApps.Candidate,
    private val onAccept: () -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val panel = LinearLayout(context)
    private var closing = false

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(0x24050A0F)
        setOnClickListener { dismiss() }

        panel.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(19), dp(22), dp(15))
            isClickable = true
            background = GradientDrawable().apply {
                setColor(0xF2EEE2CF.toInt())
                cornerRadius = dp(28).toFloat()
            }
            elevation = dp(12).toFloat()
        }
        val (message, acceptLabel) = when (candidate.kind) {
            InstalledLeisureApps.Kind.MUSIC ->
                "我想陪你听会儿歌，要打开${candidate.label}吗" to "陪我听会儿"
            InstalledLeisureApps.Kind.DOUYIN ->
                "坐了有一会儿了，要不要刷会儿抖音，我陪你看" to "打开抖音"
            InstalledLeisureApps.Kind.GAME ->
                "要不要玩一局${candidate.label}，我在旁边陪你" to "玩一局"
        }
        panel.addView(TextView(context).apply {
            text = message
            textSize = 16f
            setTextColor(0xFF33271F.toInt())
            typeface = Typeface.create("serif", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        actions.addView(action("先不用", 0xFF7C6B5D.toInt()) { dismiss() })
        actions.addView(action(acceptLabel, 0xFF315D75.toInt()) {
            if (closing) return@action
            closing = true
            animateAway(onAccept)
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(10) })
        panel.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(panel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            marginStart = dp(14)
            marginEnd = dp(14)
            bottomMargin = dp(18)
        })
    }

    fun reveal() {
        alpha = 0f
        panel.translationY = dp(32).toFloat()
        animate().alpha(1f).setDuration(180L).start()
        panel.animate().translationY(0f).setDuration(280L).start()
    }

    fun dismiss() {
        if (closing) return
        closing = true
        animateAway(onDismiss)
    }

    private fun animateAway(finished: () -> Unit) {
        panel.animate().translationY(dp(26).toFloat()).setDuration(180L).start()
        animate().alpha(0f).setDuration(190L).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            finished()
        }.start()
    }

    private fun action(label: String, color: Int, click: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        setTextColor(color)
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        background = null
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
    }

    private fun dp(value: Int) = (value * density).toInt()
}
