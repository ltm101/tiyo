package com.koyo.screenwarden

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import org.json.JSONObject

/**
 * Talks to the already-running normal chat through its real views
 * so deep mode never forks the conversation or context window
 */
internal class DeepCompanionChatBridge(private val activity: Activity) {

    private val companionScope = CompanionScope.capture(activity)
    private val assistantName = companionScope.displayName
    private val userName = UserPrefs.displayName(activity)

    data class Line(val speaker: String, val text: String, val sticker: String? = null)

    fun isChatVisible(): Boolean {
        val host = activity as? AppCompatActivity
        val chat = host?.let { findChat(it.supportFragmentManager.fragments) }
        return chat?.isVisible == true && chat.view?.isShown == true
    }

    fun modelStatus(): String {
        val model = find<TextView>("chat_model_name")?.text?.toString().orEmpty()
        val context = find<TextView>("chat_context_meter")?.text?.toString().orEmpty()
        return listOf(model, context).filter { it.isNotBlank() }.joinToString("  ·  ")
    }

    fun send(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        val input = find<EditText>("chat_input") ?: return false
        val send = find<View>("btn_chat_send") ?: return false
        input.setText(clean)
        input.setSelection(input.text?.length ?: 0)
        return send.performClick()
    }

    fun triggerVoice(): Boolean = find<View>("btn_chat_mic")?.performClick() == true

    fun triggerImage(): Boolean = chatFragment()?.openImagePickerFromDeepMode() == true

    fun triggerFile(): Boolean = chatFragment()?.openFilePickerFromDeepMode() == true

    fun pendingAttachmentNames(): List<String> =
        chatFragment()?.pendingAttachmentNamesForDeepMode().orEmpty()

    fun clearPendingAttachments(): Boolean =
        chatFragment()?.clearPendingAttachmentsFromDeepMode() == true

    fun recordPlanCompleted(text: String) {
        val clean = text.trim().take(120)
        if (clean.isBlank()) return
        val args = JSONObject()
            .put("name", "完成计划-${System.currentTimeMillis()}")
            .put("description", "用户在${assistantName}书桌勾选完成了一项计划")
            .put("type", "project")
            .put("content", "用户刚刚完成计划：$clean。后续回复和休息邀请应考虑这项进展。")
        TiyoMemoryBridge.saveLocalMemory(activity, companionScope, args)
        TiyoMemoryBridge.enqueueMemoryWrite(activity, companionScope, args)
    }

    fun deliverCountdownFinished(label: String): String {
        val cleanLabel = label.trim().take(40).ifBlank { "专注时间" }
        val message = "$cleanLabel 结束了，先停一下，我陪你看看刚才做得怎么样"
        val sticker = "截止日期到了".takeIf { StickerStore.has(activity, it, companionScope) }
        val delivered = chatFragment()?.appendLocalAssistantMessageFromDeepMode(message, sticker) == true
        if (!delivered) {
            TiyoSessionStore.appendAssistantMessage(
                activity,
                companionScope,
                TiyoSessionStore.activeId(activity, companionScope),
                message,
                sticker
            )
        }
        val args = JSONObject()
            .put("name", "书桌倒计时结束-${System.currentTimeMillis()}")
            .put("description", "用户设置的${assistantName}书桌倒计时已经结束")
            .put("type", "event")
            .put("content", "用户的书桌倒计时“$cleanLabel”刚刚结束，$assistantName 已经提醒用户休息并回顾刚才的进展")
        TiyoMemoryBridge.saveLocalMemory(activity, companionScope, args)
        TiyoMemoryBridge.enqueueMemoryWrite(activity, companionScope, args)
        return message
    }

    fun recentLines(limit: Int = 14): List<Line> {
        val history = fragmentHistory()
        if (history.isNotEmpty()) return history.takeLast(limit)
        return visibleHistory(limit)
    }

    /**
     * Reads the canonical ChatFragment message list instead of scraping bubbles
     *
     * Long replies are rendered as PaperSheetView and streaming replies may replace their original
     * TextView, so the view hierarchy is not a reliable conversation source
     */
    private fun fragmentHistory(): List<Line> {
        val host = activity as? AppCompatActivity ?: return emptyList()
        val chat = findChat(host.supportFragmentManager.fragments) ?: return emptyList()
        val field = generateSequence(chat.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { type -> runCatching { type.getDeclaredField("messages") }.getOrNull() }
            .firstOrNull() ?: return emptyList()
        field.isAccessible = true
        val values = runCatching { field.get(chat) as? Iterable<*> }.getOrNull() ?: return emptyList()
        return values.mapNotNull { item ->
            item ?: return@mapNotNull null
            val role = readProperty(item, "role") ?: return@mapNotNull null
            val text = readProperty(item, "text")?.trim().orEmpty()
            val sticker = readProperty(item, "sticker")?.trim()?.takeIf(String::isNotBlank)
            if ((text.isBlank() && sticker == null) || role !in setOf("user", "assistant")) return@mapNotNull null
            Line(if (role == "user") userName else assistantName, text, sticker)
        }
    }

    private fun chatFragment(): ChatFragment? {
        val host = activity as? AppCompatActivity ?: return null
        return findChat(host.supportFragmentManager.fragments) as? ChatFragment
    }

    private fun findChat(fragments: List<Fragment>): Fragment? {
        fragments.asReversed().forEach { fragment ->
            if (fragment.javaClass.simpleName == "ChatFragment" && fragment.isAdded) return fragment
            findChat(fragment.childFragmentManager.fragments)?.let { return it }
        }
        return null
    }

    private fun readProperty(target: Any, name: String): String? {
        val getter = "get" + name.replaceFirstChar { it.uppercaseChar() }
        runCatching {
            target.javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }
                ?.invoke(target)?.toString()
        }.getOrNull()?.let { return it }
        return generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { type -> runCatching { type.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()
            ?.let { property ->
                property.isAccessible = true
                runCatching { property.get(target)?.toString() }.getOrNull()
            }
    }

    private fun visibleHistory(limit: Int): List<Line> {
        val list = find<ViewGroup>("chat_messages") ?: return emptyList()
        val textId = id("chat_message_text")
        val avatarId = id("chat_avatar")
        val out = ArrayList<Line>()
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index)
            val message = row.findViewById<TextView>(textId)?.text?.toString()?.trim().orEmpty()
            if (message.isBlank() || message == "正在想…") continue
            val gravity = (row.layoutParams as? LinearLayout.LayoutParams)?.gravity ?: Gravity.NO_GRAVITY
            val avatar = row.findViewById<View>(avatarId)
            val roleTag = avatar?.tag?.toString().orEmpty()
            val description = avatar?.contentDescription?.toString().orEmpty()
            val isUser = roleTag == "user" || description == "我" ||
                gravity and Gravity.END == Gravity.END || row.translationX > 0f
            out += Line(if (isUser) userName else assistantName, message)
        }
        return out.takeLast(limit)
    }

    fun latestKoyoLine(): String? = recentLines(10)
        .lastOrNull { it.speaker == assistantName && it.text.isNotBlank() }
        ?.text

    fun latestKoyoSticker(): String? = fragmentHistory()
        .asReversed()
        .firstOrNull { it.speaker == assistantName && !it.sticker.isNullOrBlank() }
        ?.sticker

    private inline fun <reified T : View> find(name: String): T? =
        activity.findViewById<T>(id(name))

    private fun id(name: String): Int =
        activity.resources.getIdentifier(name, "id", activity.packageName)
}
