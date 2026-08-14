package com.koyo.screenwarden

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

class TiyoTaskCard(
    private val context: Context,
    private val parent: LinearLayout,
    private val onCancel: () -> Unit,
    private val onApprove: (String, String) -> Unit
) {
    private data class ToolRow(
        val callId: String,
        val root: LinearLayout,
        val mark: TextView,
        val title: TextView,
        val state: TextView,
        val detail: TextView,
        val approval: LinearLayout
    )

    private val root = LayoutInflater.from(context)
        .inflate(R.layout.item_chat_task, parent, false)
    private val title = root.findViewById<TextView>(R.id.task_title)
    private val progress = root.findViewById<TextView>(R.id.task_progress)
    private val summary = root.findViewById<TextView>(R.id.task_summary)
    private val steps = root.findViewById<LinearLayout>(R.id.task_steps)
    private val toggle = root.findViewById<TextView>(R.id.task_toggle)
    private val stop = root.findViewById<TextView>(R.id.task_stop)
    private val mark = root.findViewById<TextView>(R.id.task_mark)
    private val rows = linkedMapOf<String, ToolRow>()
    private val startedAt = System.currentTimeMillis()

    private var detailsExpanded = false
    private var completed = false
    private var loopDescription = ""

    init {
        parent.addView(root)
        toggle.setOnClickListener {
            detailsExpanded = !detailsExpanded
            toggle.text = if (detailsExpanded) "收起" else "详情"
            rows.values.forEach(::updateDetailVisibility)
        }
        stop.setOnClickListener {
            stop.isEnabled = false
            stop.alpha = 0.5f
            progress.text = "正在停止"
            onCancel()
        }
    }

    fun isCompleted(): Boolean = completed

    fun handle(event: JSONObject): Boolean {
        when (event.optString("event_type")) {
            "tool_start" -> onToolStart(event)
            "tool_running" -> updateTool(event.optString("call_id"), "…", "执行中")
            "tool_done" -> onToolDone(event)
            "tool_cache_hit" -> updateTool(event.optString("call_id"), "✓", "已复用")
            "tool_approval_request" -> onApproval(event)
            "loop_step_start" -> {
                loopDescription = event.optString("step_description")
                val index = event.optInt("step_index")
                val total = event.optInt("total_steps")
                summary.text = loopDescription.ifBlank { "正在处理第 $index 步" }
                progress.text = if (total > 0) "$index / $total" else "正在规划"
            }
            "loop_progress" -> {
                val current = event.optInt("current_step")
                val total = event.optInt("total_steps")
                if (total > 0) progress.text = "$current / $total"
            }
            "loop_step_done" -> {
                val index = event.optInt("step_index")
                val total = event.optInt("total_steps")
                summary.text = event.optString("step_description").ifBlank { "第 $index 步已经完成" }
                if (total > 0) progress.text = "$index / $total"
            }
            "loop_issue_created" -> {
                title.text = "发现需要处理的问题"
                summary.text = event.optString("description").ifBlank { "Tiyo 已把问题加入后续步骤" }
            }
            "bg_task_detached" -> onBackgroundDetached(event)
            "bg_task_completed" -> onBackgroundCompleted(event)
            "agent_cancelled" -> finish(false, "任务已停止")
            "turn_end" -> finish(true, null)
            else -> return false
        }
        return true
    }

    fun fail(message: String) {
        finish(false, message)
    }

    private fun onToolStart(event: JSONObject) {
        val callId = event.optString("call_id")
        if (callId.isBlank()) return
        val toolName = event.optString("tool_name")
        val arguments = event.optJSONObject("arguments") ?: JSONObject()
        val row = rows[callId] ?: createToolRow(callId, toolName, arguments)
        row.mark.text = "○"
        row.state.text = "准备中"
        title.text = "正在执行"
        summary.text = loopDescription.ifBlank { "Tiyo 正在调用本机工具" }
        updateProgress()
    }

    private fun onToolDone(event: JSONObject) {
        val callId = event.optString("call_id")
        val row = rows[callId] ?: return
        val failed = event.optBoolean("is_error")
        val elapsed = event.optDouble("elapsed", 0.0)
        row.mark.text = if (failed) "×" else "✓"
        row.mark.setTextColor(context.getColor(if (failed) R.color.tiyo_error else R.color.tiyo_success))
        row.state.text = if (failed) "失败" else if (elapsed > 0) formatElapsed(elapsed) else "完成"
        row.state.setTextColor(context.getColor(if (failed) R.color.tiyo_error else R.color.tiyo_success))
        val preview = event.optString("result_preview").trim()
        if (preview.isNotBlank()) row.detail.text = preview.take(900)
        row.approval.removeAllViews()
        row.approval.visibility = View.GONE
        updateDetailVisibility(row)
        updateProgress()
    }

    private fun onApproval(event: JSONObject) {
        val callId = event.optString("call_id")
        if (callId.isBlank()) return
        val toolName = event.optString("tool_name")
        val arguments = event.optJSONObject("arguments") ?: JSONObject()
        val row = rows[callId] ?: createToolRow(callId, toolName, arguments)
        row.mark.text = "!"
        row.mark.setTextColor(context.getColor(R.color.tiyo_warning))
        row.state.text = "等待确认"
        row.state.setTextColor(context.getColor(R.color.tiyo_warning))
        val risk = event.optString("risk_summary")
        if (risk.isNotBlank()) row.detail.text = risk
        row.approval.removeAllViews()
        row.approval.visibility = View.VISIBLE
        row.approval.addView(actionButton("拒绝", false) {
            row.approval.removeAllViews()
            row.approval.visibility = View.GONE
            updateTool(callId, "×", "已拒绝")
            onApprove(callId, "deny")
        })
        row.approval.addView(actionButton("允许一次", true) {
            row.approval.removeAllViews()
            row.approval.visibility = View.GONE
            updateTool(callId, "…", "执行中")
            onApprove(callId, "allow")
        })
        row.approval.addView(actionButton("始终允许", true) {
            row.approval.removeAllViews()
            row.approval.visibility = View.GONE
            updateTool(callId, "…", "执行中")
            onApprove(callId, "always")
        })
        detailsExpanded = true
        toggle.text = "收起"
        updateDetailVisibility(row)
        title.text = "需要你的确认"
        summary.text = "这一步可能会改变手机里的内容"
    }

    private fun onBackgroundDetached(event: JSONObject) {
        val taskId = event.optString("task_id").ifBlank { rows.size.toString() }
        val callId = "background-$taskId"
        val toolName = event.optString("tool_name").ifBlank { "background_task" }
        val row = rows[callId] ?: createToolRow(callId, toolName, JSONObject())
        row.mark.text = "↗"
        row.state.text = "后台运行"
        row.detail.text = "后台任务 #$taskId 已开始，聊天可以继续"
        title.text = "任务仍在继续"
        summary.text = "有一项工作已经转入后台"
        updateProgress()
    }

    private fun onBackgroundCompleted(event: JSONObject) {
        val taskId = event.optString("task_id")
        val callId = "background-$taskId"
        val failed = event.optBoolean("is_error")
        val row = rows[callId] ?: createToolRow(
            callId,
            event.optString("tool_name").ifBlank { "background_task" },
            JSONObject()
        )
        row.mark.text = if (failed) "×" else "✓"
        row.state.text = if (failed) "后台失败" else "后台完成"
        row.detail.text = event.optString("result_preview").ifBlank {
            if (failed) "后台任务没有完成" else "后台任务已经完成"
        }
        row.mark.setTextColor(context.getColor(if (failed) R.color.tiyo_error else R.color.tiyo_success))
        row.state.setTextColor(context.getColor(if (failed) R.color.tiyo_error else R.color.tiyo_success))
        updateDetailVisibility(row)
        updateProgress()
    }

    private fun createToolRow(callId: String, toolName: String, arguments: JSONObject): ToolRow {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(10), dp(11), dp(9))
            setBackgroundResource(R.drawable.tiyo_task_step_bg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(7) }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val mark = textView("○", 12f, R.color.tiyo_accent_dark, Typeface.BOLD).apply {
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        }
        val title = textView(toolLabel(toolName), 12f, R.color.tiyo_ink, Typeface.BOLD).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val state = textView("准备中", 10f, R.color.tiyo_muted, Typeface.NORMAL)
        header.addView(mark)
        header.addView(title)
        header.addView(state)
        root.addView(header)

        val detail = textView(safeArguments(arguments), 10.5f, R.color.tiyo_ink_soft, Typeface.NORMAL).apply {
            setLineSpacing(0f, 1.15f)
            setPadding(dp(24), dp(6), 0, 0)
            visibility = View.GONE
            setTextIsSelectable(true)
        }
        root.addView(detail)

        val approval = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        root.addView(approval)
        steps.addView(root)

        return ToolRow(callId, root, mark, title, state, detail, approval).also {
            rows[callId] = it
        }
    }

    private fun updateTool(callId: String, mark: String, state: String) {
        rows[callId]?.let { row ->
            row.mark.text = mark
            row.state.text = state
            updateProgress()
        }
    }

    private fun updateProgress() {
        val done = rows.values.count { it.state.text in setOf("完成", "已复用", "失败", "已拒绝") || it.mark.text == "✓" }
        progress.text = if (rows.isEmpty()) "正在准备" else "$done / ${rows.size} 个步骤"
    }

    private fun finish(success: Boolean, message: String?) {
        if (completed) return
        completed = true
        val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
        mark.text = if (success) "✓" else "×"
        mark.setTextColor(context.getColor(if (success) R.color.tiyo_success else R.color.tiyo_error))
        title.text = if (success) "任务完成" else "任务没有完成"
        progress.text = "${rows.size} 个步骤 · ${formatElapsed(seconds)}"
        summary.text = message ?: if (rows.any { it.value.mark.text == "×" }) {
            "任务结束了，其中有步骤没有成功"
        } else {
            "执行记录已保留，需要时可以展开查看"
        }
        stop.visibility = View.GONE
        if (!detailsExpanded) rows.values.forEach(::updateDetailVisibility)
    }

    private fun updateDetailVisibility(row: ToolRow) {
        row.detail.visibility = if (detailsExpanded || row.approval.visibility == View.VISIBLE) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit): TextView {
        return textView(
            label,
            10f,
            if (primary) android.R.color.white else R.color.tiyo_ink_soft,
            Typeface.BOLD
        ).apply {
            gravity = android.view.Gravity.CENTER
            setBackgroundResource(if (primary) R.drawable.chat_send_bg else R.drawable.chat_secondary_button_bg)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(if (label.length > 4) 76 else 58), dp(34)).apply {
                marginStart = dp(6)
            }
        }
    }

    private fun textView(text: String, size: Float, color: Int, style: Int): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(context.getColor(color))
            typeface = Typeface.create("sans-serif", style)
        }
    }

    private fun safeArguments(arguments: JSONObject): String {
        if (arguments.length() == 0) return "等待工具参数"
        val keys = arguments.keys().asSequence().toList()
        val preferred = listOf("path", "file_path", "command", "query", "url", "operation")
            .firstOrNull(keys::contains)
        val value = preferred?.let { arguments.opt(it)?.toString() }
            ?: arguments.toString()
        return value.replace(Regex("(?i)(api[_-]?key|token|password)\\s*[:=]\\s*[^,\\s]+"), "\$1=[已隐藏]")
            .take(700)
    }

    private fun toolLabel(name: String): String = when (name.lowercase()) {
        "local_shell", "shell" -> "运行手机命令"
        "read_file" -> "读取文件"
        "write_file" -> "写入文件"
        "edit_file", "apply_patch" -> "修改文件"
        "list_files", "list_dir" -> "浏览目录"
        "search_files", "grep", "grep_files", "search" -> "搜索内容"
        "web_search" -> "搜索网络"
        "fetch_url" -> "读取网页"
        "download_url" -> "下载文件"
        "view_image" -> "查看图片"
        "configure_mcp" -> "配置扩展工具"
        "install_skill" -> "安装能力包"
        "spawn_agent" -> "启动后台 Agent"
        "wait_agent" -> "等待后台 Agent"
        "memory_read", "memory_search", "memory_list" -> "读取本地记忆"
        "memory_write", "memory_delete" -> "更新本地记忆"
        "background_task" -> "后台任务"
        "request_file_import" -> "选择手机文件"
        "request_file_export" -> "导出文件"
        else -> name.replace('_', ' ').ifBlank { "调用工具" }
    }

    private fun formatElapsed(seconds: Double): String {
        return if (seconds < 1.0) "<1 秒" else if (seconds < 60) {
            "${seconds.toInt()} 秒"
        } else {
            "${(seconds / 60).toInt()} 分 ${seconds.toInt() % 60} 秒"
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
