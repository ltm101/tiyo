package com.koyo.screenwarden

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 处理建议回复通知的按钮：
 * - 「复制」：把Tiyo拟的话放进剪贴板
 * - 「复制并打开」：复制 + 拉起对应 App（微信/QQ）
 */
class ReplyActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_REPLY_TEXT) ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("tiyo_reply", text))
        // 追溯：把这条历史标记成已使用
        if (intent.hasExtra(EXTRA_HISTORY_ID)) {
            val opened = intent.action == ACTION_COPY_AND_OPEN
            val companionScope = CompanionScope.of(
                intent.getStringExtra(EXTRA_COMPANION_ID)
                    ?: CompanionProfileRules.DEFAULT_COMPANION_ID,
                intent.getStringExtra(EXTRA_COMPANION_NAME).orEmpty()
            )
            AutoReplyHistory.markUsed(
                context,
                intent.getLongExtra(EXTRA_HISTORY_ID, 0L),
                opened,
                companionScope
            )
        }
        intent.getStringExtra(EXTRA_FRIENDSHIP_KEY)
            ?.takeIf { it.isNotBlank() }
            ?.let { FriendshipProfileStore.recordChosen(context, it) }
        if (intent.action == ACTION_COPY_AND_OPEN) {
            val pkg = intent.getStringExtra(EXTRA_TARGET_PKG) ?: "com.tencent.mm"
            launchApp(context, pkg)
            Toast.makeText(context, "已复制，去粘贴发送", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "已复制，去微信长按粘贴", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchApp(context: Context, pkg: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
    }

    companion object {
        const val ACTION_COPY = "com.koyo.screenwarden.ACTION_COPY_REPLY"
        const val ACTION_COPY_AND_OPEN = "com.koyo.screenwarden.ACTION_COPY_AND_OPEN"
        const val EXTRA_REPLY_TEXT = "reply_text"
        const val EXTRA_TARGET_PKG = "target_pkg"
        const val EXTRA_HISTORY_ID = "history_id"
        const val EXTRA_FRIENDSHIP_KEY = "friendship_key"
        const val EXTRA_COMPANION_ID = "companion_id"
        const val EXTRA_COMPANION_NAME = "companion_name"
    }
}
