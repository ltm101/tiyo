package com.koyo.screenwarden

import android.content.Context
import android.graphics.Paint
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

/**
 * 今日任务卡片渲染助手：工作台（和曾经的 Today）共用同一套交互。
 * 勾选完成、长按删除、＋添加（内容 + 可选时间）。
 */
object TaskUi {

    fun render(context: Context, inflater: LayoutInflater, container: LinearLayout) {
        container.removeAllViews()
        TaskStore.list(context).take(4).forEach { task ->
            val row = inflater.inflate(R.layout.item_today_task, container, false)
            val check = row.findViewById<TextView>(R.id.task_check)
            val text = row.findViewById<TextView>(R.id.task_text)
            val time = row.findViewById<TextView>(R.id.task_time)

            text.text = task.text
            time.text = task.timeLabel
            applyStyle(context, check, text, task.done)

            check.setOnClickListener {
                TaskStore.toggle(context, task.id)
                render(context, inflater, container)
            }
            row.setOnLongClickListener {
                AlertDialog.Builder(context)
                    .setTitle("删掉这个任务？")
                    .setMessage(task.text)
                    .setPositiveButton("删掉") { _, _ ->
                        TaskStore.remove(context, task.id)
                        render(context, inflater, container)
                    }
                    .setNegativeButton("留着", null)
                    .show()
                true
            }
            container.addView(row)
        }
    }

    fun showAddDialog(context: Context, inflater: LayoutInflater, container: LinearLayout) {
        val dialogView = inflater.inflate(R.layout.dialog_add_task, null)
        val inputText = dialogView.findViewById<android.widget.EditText>(R.id.task_input_text)
        val inputTime = dialogView.findViewById<android.widget.EditText>(R.id.task_input_time)
        AlertDialog.Builder(context)
            .setTitle("添加任务")
            .setView(dialogView)
            .setPositiveButton("添加") { _, _ ->
                val text = inputText.text.toString().trim()
                if (text.isNotEmpty()) {
                    TaskStore.add(context, text, inputTime.text.toString())
                    render(context, inflater, container)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyStyle(context: Context, check: TextView, text: TextView, done: Boolean) {
        if (done) {
            check.setBackgroundResource(R.drawable.d_task_done)
            check.setTextColor(ContextCompat.getColor(context, R.color.d_accent_deep))
            text.paintFlags = text.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            text.setTextColor(ContextCompat.getColor(context, R.color.d_ink_3))
        } else {
            check.setBackgroundResource(R.drawable.d_task_ring)
            check.setTextColor(ContextCompat.getColor(context, android.R.color.transparent))
            text.paintFlags = text.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            text.setTextColor(ContextCompat.getColor(context, R.color.d_ink))
        }
    }
}
