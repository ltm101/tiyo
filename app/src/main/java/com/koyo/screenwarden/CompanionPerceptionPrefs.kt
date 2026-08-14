package com.koyo.screenwarden

import android.content.Context

data class CompanionAppTarget(
    val key: String,
    val label: String,
    val packages: Set<String>,
    val settleDelayMs: Long,
    val captureIntervalMs: Long
)

object CompanionTargets {
    val DOUYIN = CompanionAppTarget(
        key = "douyin",
        label = "抖音",
        packages = setOf("com.ss.android.ugc.aweme"),
        settleDelayMs = 3_500L,
        captureIntervalMs = 3 * 60_000L
    )
    val WANGZHE = CompanionAppTarget(
        key = "wangzhe",
        label = "王者荣耀",
        packages = setOf("com.tencent.tmgp.sgame", "com.tencent.smoba"),
        settleDelayMs = 5_000L,
        captureIntervalMs = 90_000L
    )
    val ALL = listOf(DOUYIN, WANGZHE)

    fun find(packageName: String): CompanionAppTarget? = ALL.firstOrNull {
        packageName in it.packages
    }
}

object CompanionPerceptionPrefs {
    private const val PREFS = "tiyo_companion_perception"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CONSENT_VERSION = "consent_version"
    private const val CONSENT_VERSION = 1

    fun isEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, false) &&
            prefs.getInt(KEY_CONSENT_VERSION, 0) == CONSENT_VERSION
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) CompanionPerceptionNotifier.hide(context)
    }

    fun grantConsent(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_CONSENT_VERSION, CONSENT_VERSION).putBoolean(KEY_ENABLED, true).apply()
    }

    fun hasConsent(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(KEY_CONSENT_VERSION, 0) == CONSENT_VERSION

    fun isTargetEnabled(context: Context, target: CompanionAppTarget): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("target_${target.key}", true)

    fun setTargetEnabled(context: Context, target: CompanionAppTarget, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("target_${target.key}", enabled).apply()
    }
}

data class CompanionCaptureState(
    val activePackage: String = "",
    val enteredAt: Long = 0L,
    val lastCaptureAt: Map<String, Long> = emptyMap()
)

data class CompanionCaptureDecision(
    val state: CompanionCaptureState,
    val target: CompanionAppTarget? = null,
    val captureAfterMs: Long = -1L,
    val leftTarget: Boolean = false
)

/** 纯策略，避免高频无障碍事件把截图和视觉调用刷爆 */
object CompanionCapturePolicy {
    private const val MIN_REENTRY_CAPTURE_MS = 30_000L

    fun onForegroundEvent(
        state: CompanionCaptureState,
        packageName: String,
        now: Long,
        contentChanged: Boolean
    ): CompanionCaptureDecision {
        val target = CompanionTargets.find(packageName)
        if (target == null) {
            return CompanionCaptureDecision(
                state = state.copy(activePackage = "", enteredAt = 0L),
                leftTarget = state.activePackage.isNotBlank()
            )
        }
        val entered = state.activePackage != packageName
        val enteredAt = if (entered) now else state.enteredAt
        val lastCapture = state.lastCaptureAt[target.key] ?: 0L
        val intervalReady = now - lastCapture >= target.captureIntervalMs
        val shouldCapture = (entered && (lastCapture <= 0L || now - lastCapture >= MIN_REENTRY_CAPTURE_MS)) ||
            (contentChanged && intervalReady)
        return CompanionCaptureDecision(
            state = state.copy(activePackage = packageName, enteredAt = enteredAt),
            target = target,
            captureAfterMs = if (shouldCapture) target.settleDelayMs else -1L
        )
    }

    fun markCaptured(
        state: CompanionCaptureState,
        target: CompanionAppTarget,
        now: Long
    ): CompanionCaptureState = state.copy(
        lastCaptureAt = state.lastCaptureAt + (target.key to now)
    )
}
