package com.koyo.screenwarden

import android.content.Context
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog

/** 功能优先的相处档案查看器，后续视觉改版只需替换这一层，不动存储结构 */
object FriendshipProfilesDialog {
    fun show(context: Context) {
        val profiles = FriendshipProfileStore.all(context)
        if (profiles.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("相处档案")
                .setMessage(
                    "还没有学到好友档案\n\n开启后，${CompanionProfileStore.activeName(context)}" +
                        "只会从新收到的微信或 QQ 消息里提炼表达习惯，不读取旧聊天记录"
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val labels = profiles.map { profile ->
            buildString {
                append(profile.displayName).append(" · ").append(profile.platform)
                if (profile.relationship.isNotBlank()) append("\n").append(profile.relationship)
                append(" · ").append(profile.incomingCount).append(" 条来信")
                if (profile.paused) append(" · 已暂停")
            }
        }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("相处档案")
            .setItems(labels) { _, which -> showDetail(context, profiles[which]) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showDetail(context: Context, profile: FriendshipProfile) {
        val actions = arrayOf(
            "修改关系备注",
            if (profile.paused) "继续学习这个会话" else "暂停学习这个会话",
            "删除这份档案"
        )
        AlertDialog.Builder(context)
            .setTitle(profile.displayName)
            .setMessage(FriendshipProfileStore.detailText(profile))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> editRelationship(context, profile)
                    1 -> {
                        FriendshipProfileStore.setPaused(context, profile.key, !profile.paused)
                        show(context)
                    }
                    2 -> confirmDelete(context, profile)
                }
            }
            .setNegativeButton("返回") { _, _ -> show(context) }
            .show()
    }

    private fun editRelationship(context: Context, profile: FriendshipProfile) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(profile.relationship)
            hint = "例如：高中同学、表哥、队友"
            setSelection(text.length)
            setPadding(48, 24, 48, 8)
        }
        AlertDialog.Builder(context)
            .setTitle("关系备注")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                FriendshipProfileStore.setRelationship(context, profile.key, input.text.toString())
                show(context)
            }
            .setNegativeButton("取消") { _, _ -> showDetail(context, profile) }
            .show()
    }

    private fun confirmDelete(context: Context, profile: FriendshipProfile) {
        AlertDialog.Builder(context)
            .setTitle("删除 ${profile.displayName} 的档案")
            .setMessage("只删除 tiyo 里的相处档案，不会影响微信或 QQ 聊天")
            .setPositiveButton("删除") { _, _ ->
                FriendshipProfileStore.delete(context, profile.key)
                show(context)
            }
            .setNegativeButton("取消") { _, _ -> showDetail(context, profile) }
            .show()
    }
}
