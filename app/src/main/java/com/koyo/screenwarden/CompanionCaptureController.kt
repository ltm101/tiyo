package com.koyo.screenwarden

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.koyo.screenwarden.events.EventBus
import com.koyo.screenwarden.events.TiyoEvent
import com.koyo.screenwarden.events.TiyoEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/**
 * 仅在用户明确开启且目标应用位于前台时取单帧
 * 原始像素只在内存中短暂存在，筛选和视觉分析完成后立即回收
 */
class CompanionCaptureController(
    private val service: AccessibilityService,
    private val scope: CoroutineScope
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var state = CompanionCaptureState()
    private var pendingTargetKey = ""
    private var generation = 0L

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!CompanionPerceptionPrefs.isEnabled(service)) {
            leaveTarget()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isBlank()) return
        val target = CompanionTargets.find(packageName)
        if (target == null) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                packageName !in ignoredWindowPackages
            ) leaveTarget()
            return
        }
        if (!CompanionPerceptionPrefs.isTargetEnabled(service, target)) {
            leaveTarget()
            return
        }
        val decision = CompanionCapturePolicy.onForegroundEvent(
            state = state,
            packageName = packageName,
            now = System.currentTimeMillis(),
            contentChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )
        state = decision.state
        CompanionPerceptionNotifier.show(service, target)
        if (decision.captureAfterMs >= 0L) scheduleCapture(target, decision.captureAfterMs)
    }

    fun close() {
        generation++
        pendingTargetKey = ""
        mainHandler.removeCallbacksAndMessages(null)
        CompanionPerceptionNotifier.hide(service)
    }

    private fun scheduleCapture(target: CompanionAppTarget, delayMs: Long) {
        if (pendingTargetKey == target.key) return
        pendingTargetKey = target.key
        val scheduledGeneration = ++generation
        mainHandler.postDelayed({
            pendingTargetKey = ""
            if (scheduledGeneration != generation || !CompanionPerceptionPrefs.isEnabled(service)) return@postDelayed
            if (state.activePackage !in target.packages ||
                !CompanionPerceptionPrefs.isTargetEnabled(service, target)
            ) return@postDelayed
            val locked = (service.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? KeyguardManager)
                ?.isKeyguardLocked == true
            val keyboardVisible = runCatching {
                service.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            }.getOrDefault(false)
            if (locked || keyboardVisible) return@postDelayed
            state = CompanionCapturePolicy.markCaptured(state, target, System.currentTimeMillis())
            capture(target)
        }, delayMs)
    }

    private fun capture(target: CompanionAppTarget) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val softwareBitmap = copyScreenshot(screenshot.hardwareBuffer, screenshot.colorSpace)
                        ?: return
                    scope.launch(Dispatchers.IO) {
                        try {
                            CompanionFrameAnalyzer.analyze(service.applicationContext, target, softwareBitmap)
                        } finally {
                            softwareBitmap.recycle()
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    // 安全窗口或厂商拒绝都静默降级，不尝试绕过系统限制
                }
            }
        )
    }

    private fun copyScreenshot(buffer: HardwareBuffer, colorSpace: android.graphics.ColorSpace): Bitmap? {
        return try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace) ?: return null
            try {
                hardware.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                hardware.recycle()
            }
        } catch (_: Exception) {
            null
        } finally {
            buffer.close()
        }
    }

    private fun leaveTarget() {
        if (state.activePackage.isBlank() && pendingTargetKey.isBlank()) return
        generation++
        pendingTargetKey = ""
        mainHandler.removeCallbacksAndMessages(null)
        state = state.copy(activePackage = "", enteredAt = 0L)
        CompanionPerceptionNotifier.hide(service)
    }

    companion object {
        private val ignoredWindowPackages = setOf(
            "com.android.systemui",
            "com.koyo.screenwarden",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.inputmethod.latin",
            "com.baidu.input"
        )
    }
}

data class CompanionVisionDecision(
    val useful: Boolean,
    val safeToDiscuss: Boolean,
    val shouldRespond: Boolean,
    val moment: String,
    val summary: String
)

object CompanionVisionDecisionParser {
    fun parse(raw: String): CompanionVisionDecision? {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { JSONObject(clean.substring(start, end + 1)) }.getOrNull() ?: return null
        return CompanionVisionDecision(
            useful = json.optBoolean("useful", false),
            safeToDiscuss = json.optBoolean("safe_to_discuss", false),
            shouldRespond = json.optBoolean("should_respond", false),
            moment = json.optString("moment", "other")
                .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_-]"), "_").take(24),
            summary = json.optString("summary").replace(Regex("[\r\n]+"), " ").trim().take(300)
        )
    }
}

object CompanionFrameAnalyzer {
    private val lastHashes = HashMap<String, Long>()
    private val lock = Any()

    fun analyze(context: android.content.Context, target: CompanionAppTarget, bitmap: Bitmap) {
        if (!CompanionPerceptionPrefs.isEnabled(context) ||
            !CompanionPerceptionPrefs.isTargetEnabled(context, target)
        ) return
        val fingerprint = frameFingerprint(bitmap)
        val changedEnough = synchronized(lock) {
            val previous = lastHashes.put(target.key, fingerprint)
            previous == null || java.lang.Long.bitCount(previous xor fingerprint) >= 7
        }
        if (!changedEnough || looksBlank(bitmap)) return

        val top = (bitmap.height * 0.04f).toInt().coerceAtLeast(0)
        val bottom = (bitmap.height * 0.90f).toInt().coerceAtMost(bitmap.height)
        if (bottom <= top) return
        val cropped = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bottom - top)
        val dataUrl = try {
            BuiltinVision.bitmapToDataUrl(cropped)
        } finally {
            cropped.recycle()
        } ?: return
        val raw = BuiltinVision.recognize(context, dataUrl, promptFor(target))
        val decision = CompanionVisionDecisionParser.parse(raw) ?: return
        if (!decision.useful || !decision.safeToDiscuss || decision.summary.isBlank()) return
        if (!CompanionPerceptionPrefs.isEnabled(context) ||
            !CompanionPerceptionPrefs.isTargetEnabled(context, target)
        ) return

        val moment = decision.moment.ifBlank { "other" }
        val interaction = when {
            target.key == CompanionTargets.WANGZHE.key && moment.contains("result") -> "result"
            decision.shouldRespond -> "respond"
            else -> "observe"
        }
        EventBus.publish(
            context,
            TiyoEvent(
                type = TiyoEventType.COMPANION_CONTEXT,
                summary = "陪伴会话在${target.label}里遇到一个经过筛选的画面节点",
                topicKey = "companion:${target.key}:$interaction",
                expiresAt = System.currentTimeMillis() + 25 * 60_000L,
                sensitiveContext = "应用：${target.label}；画面类型：$moment；仅供本次判断的摘要：${decision.summary}"
            )
        )
    }

    private fun promptFor(target: CompanionAppTarget): String {
        val appRule = if (target.key == CompanionTargets.WANGZHE.key) {
            "只有清晰的对局结算、胜负、评分或战绩页才算值得互动；大厅、加载、游戏进行中一律 useful=false"
        } else {
            "判断当前抖音内容是否形成一个自然、具体、不会打扰用户的互动点；广告、直播带货、私信、评论区、个人资料或看不清的画面一律 useful=false"
        }
        return """
            这是用户明确开启的 tiyo 陪伴会话中的单帧
            $appRule
            如果画面包含支付、密码、私聊、通知、账号、住址、证件或其他敏感信息，safe_to_discuss=false 且 summary 留空
            不识别人脸身份，不猜用户情绪，不复述昵称、账号和评论原文
            should_respond 只有在此刻主动说一句确实自然时才为 true，默认 false
            只输出 JSON，不要代码框：
            {"useful":true,"safe_to_discuss":true,"should_respond":false,"moment":"feed|gameplay|result|lobby|ad|sensitive|other","summary":"不含隐私的简短场景摘要"}
        """.trimIndent()
    }

    private fun frameFingerprint(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 9, 8, false)
        return try {
            var hash = 0L
            var bit = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val left = luminance(small.getPixel(x, y))
                    val right = luminance(small.getPixel(x + 1, y))
                    if (left > right) hash = hash or (1L shl bit)
                    bit++
                }
            }
            hash
        } finally {
            small.recycle()
        }
    }

    private fun looksBlank(bitmap: Bitmap): Boolean {
        val sample = Bitmap.createScaledBitmap(bitmap, 16, 16, false)
        return try {
            var total = 0.0
            var totalSquared = 0.0
            for (y in 0 until 16) for (x in 0 until 16) {
                val value = luminance(sample.getPixel(x, y))
                total += value
                totalSquared += value * value
            }
            val mean = total / 256.0
            val variance = totalSquared / 256.0 - mean * mean
            mean < 8.0 || variance < 12.0
        } finally {
            sample.recycle()
        }
    }

    private fun luminance(pixel: Int): Int {
        val r = pixel shr 16 and 0xff
        val g = pixel shr 8 and 0xff
        val b = pixel and 0xff
        return (r * 30 + g * 59 + b * 11) / 100
    }
}
