package com.koyo.screenwarden

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.koyo.screenwarden.events.EventBus
import com.koyo.screenwarden.events.TiyoEvent
import com.koyo.screenwarden.events.TiyoEventType

/**
 * 通知监听转发。系统绑定的服务，需用户在「通知使用权」里手动授权。
 * 只转发 Config.FORWARD_PACKAGES 白名单里的通知（微信 + 短信），
 * 30 秒内同内容去重，避免群消息刷屏。
 */
class NotificationForwardService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 去重：内容 hash -> 上次转发时间戳
    private val recent = HashMap<Int, Long>()

    override fun onListenerConnected() {
        Log.i(TAG, "notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName ?: return
            Log.i(TAG, "posted pkg=$pkg flags=${sbn.notification?.flags?.toLong()}")
            if (pkg !in Config.FORWARD_PACKAGES) return   // 白名单外直接丢
            if (pkg == packageName) return                 // 跳过自己

            val n = sbn.notification ?: return
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return  // 常驻/进行中
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return  // 群组摘要

            val extras = n.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            if (title.isEmpty() && text.isEmpty()) {
                Log.i(TAG, "skip: title/text empty")
                return
            }
            Log.i(TAG, "matched notification pkg=$pkg")

            // 同内容 30 秒内不重发
            val key = (pkg + title + text).hashCode()
            val now = System.currentTimeMillis()
            recent[key]?.let { if (now - it < 30_000) return }
            recent[key] = now
            cleanup(now)

            val conversationTitle = extras
                .getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?.toString()?.trim().orEmpty()
            val friendshipIdentity = if (pkg in Config.REPLY_TARGET_PKGS.keys) {
                FriendshipIdentityResolver.resolve(pkg, title, text, conversationTitle)
            } else null
            val canSuggest = friendshipIdentity != null && title.isNotEmpty() && text.isNotEmpty() &&
                AutoReplyManager.isOn(applicationContext) &&
                AutoReplyManager.canSuggest(applicationContext, friendshipIdentity.key)
            val observation = friendshipIdentity?.let { identity ->
                FriendshipProfileStore.observeIncoming(
                    applicationContext,
                    identity,
                    text,
                    claimAnalysis = !canSuggest
                )
            }
            friendshipIdentity?.takeIf { observation?.shouldAnalyze == true }?.let { identity ->
                scope.launch {
                    FriendshipProfileAnalyzer.analyze(applicationContext, identity, text)
                }
            }

            val body = buildString {
                append("[").append(appLabel(pkg)).append("] ")
                if (title.isNotEmpty()) append(title).append(": ")
                append(text)
            }

            // 决策队列只保存来源摘要；标题和正文只在当前进程内存中短暂交给反射层判断。
            EventBus.publish(
                applicationContext,
                TiyoEvent(
                    type = TiyoEventType.NOTIFICATION,
                    summary = "${appLabel(pkg)}收到一条新通知",
                    sensitiveContext = buildString {
                        if (title.isNotEmpty()) append("标题：").append(title)
                        if (text.isNotEmpty()) {
                            if (isNotEmpty()) append("；")
                            append("正文：").append(text)
                        }
                    }
                )
            )

            scope.launch {
                EmailSender.sendReport(body, MailConfig.agentEmail(), "tiyo-notify-fwd")
            }
            Log.i(TAG, "notification forwarded pkg=$pkg")

            // Tiyo帮你回：微信/QQ 新消息自动拟写回复（开关打开 + 目标App + 联系人冷却通过）
            friendshipIdentity?.takeIf { canSuggest }?.let { identity ->
                val contact = identity.displayName
                val message = text
                val targetPkg = pkg
                val companionScope = CompanionScope.capture(applicationContext)
                scope.launch {
                    val reply = ReplyTextGenerator.generate(
                        applicationContext,
                        contact,
                        message,
                        identity.key,
                        companionScope
                    )
                    if (reply != null) {
                        ActionExecutor.suggestReply(
                            applicationContext,
                            contact,
                            reply,
                            targetPkg,
                            message,
                            "通知监听",
                            identity.key,
                            companionScope
                        )
                        Log.i(TAG, "reply suggestion created pkg=$targetPkg")
                    } else {
                        // 拟写失败（超时/没配key/模型报错）：清掉冷却，别占着 3 分钟
                        AutoReplyManager.resetCooldown(applicationContext, identity.key)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onNotificationPosted failed", e)
        }
    }

    /** 清掉 5 分钟前的去重记录，防止 map 无限增长 */
    private fun cleanup(now: Long) {
        val it = recent.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > 5 * 60_000) it.remove()
        }
    }

    private fun appLabel(pkg: String): String = when (pkg) {
        "com.tencent.mm" -> "微信"
        "com.android.messaging",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging" -> "短信"
        else -> try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    companion object {
        private const val TAG = "NotifForward"
    }
}
