package com.koyo.screenwarden

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 无障碍：两条路。
 * 1) 收通知事件（TYPE_NOTIFICATION_STATE_CHANGED）：vivo 拦截 NotificationListenerService
 *    的通知派发时，用无障碍通道收微信/QQ 通知，触发Tiyo帮你回拟写 + 弹回复通知。
 *    无障碍事件是系统辅助能力，微信无法隐藏、vivo 也拦不掉，普通用户开无障碍权限即可。
 * 2) 自动填回复（骨架，微信对无障碍隐藏节点树，暂未启用）。
 */
class WechatAutoReplyService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        companionCapture.onAccessibilityEvent(event)
        // 通知事件：vivo 上Tiyo帮你回的兜底通道
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            handleNotificationEvent(event)
            return
        }
        // 原有微信窗口节点处理（自动填回复，骨架）
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.tencent.mm") return
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "wechat event=${event.eventType} root=null")
            return
        }
        val texts = mutableListOf<String>()
        collectTexts(root, texts, 0)
        Log.i(
            TAG,
            "wechat event=${event.eventType} nodes=${texts.size}"
        )
    }

    /** 无障碍收通知：提取微信/QQ 通知 → 触发Tiyo帮你回拟写 + 弹回复通知 */
    private fun handleNotificationEvent(event: AccessibilityEvent) {
        try {
            val pkg = event.packageName?.toString() ?: return
            if (pkg !in Config.REPLY_TARGET_PKGS.keys) return
            val notification = event.parcelableData as? Notification ?: return
            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString()?.trim().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)
                ?.toString()?.trim().orEmpty()
            if (title.isEmpty() || text.isEmpty()) return
            Log.i(TAG, "notify pkg=$pkg")
            val conversationTitle = extras
                .getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?.toString()?.trim().orEmpty()
            val identity = FriendshipIdentityResolver.resolve(pkg, title, text, conversationTitle) ?: return
            val canSuggest = AutoReplyManager.isOn(this) &&
                AutoReplyManager.canSuggest(this, identity.key)
            val observation = FriendshipProfileStore.observeIncoming(
                this,
                identity,
                text,
                claimAnalysis = !canSuggest
            )
            if (observation?.shouldAnalyze == true) {
                scope.launch {
                    FriendshipProfileAnalyzer.analyze(this@WechatAutoReplyService, identity, text)
                }
            }
            if (!canSuggest) return
            val contact = identity.displayName
            val message = text
            val companionScope = CompanionScope.capture(this)
            scope.launch {
                val reply = ReplyTextGenerator.generate(
                    this@WechatAutoReplyService,
                    contact,
                    message,
                    identity.key,
                    companionScope
                )
                if (reply != null) {
                    ActionExecutor.suggestReply(
                        this@WechatAutoReplyService,
                        contact,
                        reply,
                        pkg,
                        message,
                        "无障碍",
                        identity.key,
                        companionScope
                    )
                    Log.i(TAG, "suggested reply created")
                } else {
                    // 拟写失败（超时/没配key/模型报错）：清掉冷却，别占着 3 分钟
                    AutoReplyManager.resetCooldown(this@WechatAutoReplyService, identity.key)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "notification event failed", e)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        companionCapture.close()
        super.onDestroy()
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 10) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTexts(it, out, depth + 1) }
        }
    }

    companion object {
        private const val TAG = "WechatAutoReply"
    }

    private val companionCapture by lazy { CompanionCaptureController(this, scope) }
}
