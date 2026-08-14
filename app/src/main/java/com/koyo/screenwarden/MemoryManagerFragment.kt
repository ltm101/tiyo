package com.koyo.screenwarden

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "我的 → 记忆管理"。
 * 全量列出手机 agent 的记忆（无 3 天/5 条限制），重要置顶 + 更新时间倒序，
 * 支持标记重要 / 忘掉 / 查看全文。搜索框走 MemoryTimelineLoader.recall（按相关度+优先级）。
 * 与 TodayFragment 时间线共用 MemoryTimelineLoader。
 */
class MemoryManagerFragment : Fragment(R.layout.fragment_memory_manager) {

    private lateinit var memoryList: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var countText: TextView
    private lateinit var syncHint: TextView
    private lateinit var filterChip: TextView
    private lateinit var searchInput: EditText

    private var onlyImportant = false
    private var searchQuery = ""
    private var loadJob: Job? = null
    private val timeFormatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        memoryList = view.findViewById(R.id.memory_list)
        emptyText = view.findViewById(R.id.memory_empty)
        countText = view.findViewById(R.id.memory_count)
        syncHint = view.findViewById(R.id.memory_sync_hint)
        filterChip = view.findViewById(R.id.memory_filter_important)
        searchInput = view.findViewById(R.id.memory_search_input)

        view.findViewById<View>(R.id.memory_back_btn).setOnClickListener {
            activity?.onBackPressed()
        }
        filterChip.setOnClickListener {
            onlyImportant = !onlyImportant
            filterChip.alpha = if (onlyImportant) 1f else 0.6f
            refresh()
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim().orEmpty()
                refresh()
            }
        })

        val pending = TiyoMemoryBridge.outboxCount(requireContext())
        syncHint.text = if (pending > 0) "待同步 $pending 条本地记忆" else "电脑记忆已是最新"

        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            val ctx = requireContext()
            val all = withContext(Dispatchers.IO) {
                if (searchQuery.isNotBlank()) {
                    MemoryTimelineLoader.recall(ctx, searchQuery, limit = 50)
                } else {
                    MemoryTimelineLoader.scan(ctx)
                }
            }
            val sorted = if (searchQuery.isBlank()) {
                all.sortedWith(
                    compareByDescending<MemoryTimelineLoader.MemoryItem> {
                        MemoryTimelineLoader.isImportant(ctx, it.name)
                    }.thenByDescending { it.updatedMillis }
                )
            } else {
                all // recall 已按综合分排序
            }
            val shown = if (onlyImportant) {
                sorted.filter { MemoryTimelineLoader.isImportant(ctx, it.name) }
            } else sorted
            render(shown)
            countText.text = if (searchQuery.isNotBlank()) "找到 ${shown.size} 条相关记忆" else "共 ${shown.size} 条记忆"
        }
    }

    private fun render(items: List<MemoryTimelineLoader.MemoryItem>) {
        val ctx = requireContext()
        memoryList.removeAllViews()
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        items.forEach { item ->
            val row = layoutInflater.inflate(R.layout.item_memory_row, memoryList, false)
            val important = MemoryTimelineLoader.isImportant(ctx, item.name)

            row.findViewById<TextView>(R.id.memory_row_star).apply {
                text = if (important) "★" else "☆"
                setTextColor(
                    ctx.getColor(if (important) R.color.d_accent_deep else R.color.d_ink_3)
                )
            }
            row.findViewById<TextView>(R.id.memory_row_name).text = item.name
            row.findViewById<TextView>(R.id.memory_row_type_tag).apply {
                if (item.atomType != null) {
                    text = item.typeLabel + if (item.atomPriority != null) "·${item.atomPriority}" else ""
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }
            row.findViewById<TextView>(R.id.memory_row_desc).text = item.description
            row.findViewById<TextView>(R.id.memory_row_time).text =
                timeFormatter.format(Date(item.updatedMillis))

            row.setOnClickListener { showDetail(item) }
            row.setOnLongClickListener {
                showQuickMenu(item)
                true
            }
            memoryList.addView(row)
        }
    }

    private fun showDetail(item: MemoryTimelineLoader.MemoryItem) {
        val ctx = requireContext()
        val important = MemoryTimelineLoader.isImportant(ctx, item.name)
        val full = MemoryTimelineLoader.readContent(item) ?: item.description

        val body = TextView(ctx).apply {
            text = full
            textSize = 14f
            setTextColor(ctx.getColor(R.color.d_ink))
            setPadding(24, 16, 24, 16)
        }
        AlertDialog.Builder(ctx)
            .setTitle(item.name)
            .setView(body)
            .setPositiveButton(if (important) "取消重要" else "标记重要") { _, _ ->
                MemoryTimelineLoader.setImportant(ctx, item.name, !important)
                Toast.makeText(ctx, if (!important) "记下了，这条很重要" else "好，不标记了", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNeutralButton("忘掉") { _, _ ->
                if (MemoryTimelineLoader.forget(item)) {
                    Toast.makeText(ctx, "好，我忘掉它了", Toast.LENGTH_SHORT).show()
                }
                refresh()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showQuickMenu(item: MemoryTimelineLoader.MemoryItem) {
        val ctx = requireContext()
        val important = MemoryTimelineLoader.isImportant(ctx, item.name)
        AlertDialog.Builder(ctx)
            .setTitle(item.name)
            .setItems(
                arrayOf(
                    if (important) "取消重要" else "标记重要",
                    "忘掉"
                )
            ) { _, which ->
                when (which) {
                    0 -> {
                        MemoryTimelineLoader.setImportant(ctx, item.name, !important)
                        refresh()
                    }
                    1 -> {
                        if (MemoryTimelineLoader.forget(item)) {
                            Toast.makeText(ctx, "好，我忘掉它了", Toast.LENGTH_SHORT).show()
                        }
                        refresh()
                    }
                }
            }
            .show()
    }
}
