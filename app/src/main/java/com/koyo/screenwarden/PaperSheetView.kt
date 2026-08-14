package com.koyo.screenwarden

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 三档聊天共用的长内容纸页
 *
 * 流内只展示标题和两行摘要，点开后用全幅浮层阅读和复制完整内容
 */
class PaperSheetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val titleView = TextView(context)
    private val summaryView = TextView(context)
    private var fullText = ""
    private var sheetTitle = "长内容"

    init {
        orientation = VERTICAL
        isClickable = true
        isFocusable = true
        elevation = dp(2).toFloat()
        setPadding(dp(18), dp(16), dp(18), dp(15))
        setBackgroundResource(R.drawable.chat_focus_paper_bg)
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(34)
            marginEnd = dp(8)
            bottomMargin = dp(14)
        }

        titleView.apply {
            setTextColor(context.getColor(R.color.d_focus_ink))
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }
        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        summaryView.apply {
            setTextColor(context.getColor(R.color.d_focus_ink_2))
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(7), 0, 0)
        }
        addView(summaryView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val openHint = TextView(context).apply {
            text = "点开查看全文"
            setTextColor(context.getColor(R.color.d_focus_ink_3))
            textSize = 11f
            setPadding(0, dp(10), 0, 0)
        }
        addView(openHint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        setOnClickListener { showFullSheet(context, sheetTitle, fullText) }
    }

    fun bind(title: String, text: String): PaperSheetView {
        sheetTitle = title
        fullText = text
        titleView.text = title
        summaryView.text = summary(text)
        contentDescription = "$title，点开查看全文"
        return this
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val LONG_TEXT_THRESHOLD = 460
        private const val LONG_LINE_THRESHOLD = 11

        fun shouldUse(text: String): Boolean {
            if (text.length >= LONG_TEXT_THRESHOLD) return true
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.size >= LONG_LINE_THRESHOLD) return true
            if (text.contains("```")) return true
            val fileLikeLines = lines.count { line ->
                val value = line.trimStart('-', '*', ' ', '\t')
                value.contains('/') || value.contains('\\') ||
                    value.matches(Regex(".*\\.[A-Za-z0-9]{1,8}(:\\d+)?$"))
            }
            return fileLikeLines >= 5
        }

        fun titleFor(text: String): String = when {
            text.contains("```") || text.lineSequence().count {
                it.startsWith("    ") || it.trimStart().startsWith("fun ") ||
                    it.trimStart().startsWith("class ")
            } >= 3 -> "代码片段"
            text.lineSequence().count {
                val value = it.trimStart('-', '*', ' ', '\t')
                value.contains('/') || value.contains('\\')
            } >= 5 -> "文件列表"
            text.contains("报告") || text.contains("总结") || text.contains("结论") -> "报告"
            else -> "长内容"
        }

        private fun summary(text: String): String = text
            .replace(Regex("```[A-Za-z0-9_-]*"), "")
            .replace("```", "")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(4)
            .joinToString("  ")
            .take(240)

        private fun showFullSheet(context: Context, title: String, text: String) {
            val dialog = Dialog(context)
            val density = context.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density + 0.5f).toInt()

            val root = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(18))
                setBackgroundColor(context.getColor(R.color.d_focus_canvas))
            }
            val toolbar = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleView = TextView(context).apply {
                this.text = title
                textSize = 18f
                setTextColor(context.getColor(R.color.d_focus_ink))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            }
            toolbar.addView(
                titleView,
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            )

            val copy = ImageButton(context).apply {
                setImageResource(R.drawable.ic_focus_copy)
                background = context.getDrawable(android.R.drawable.list_selector_background)
                contentDescription = "复制全文"
                setPadding(dp(13), dp(13), dp(13), dp(13))
                setOnClickListener {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(title, text))
                    Toast.makeText(context, "已复制全文", Toast.LENGTH_SHORT).show()
                }
            }
            toolbar.addView(copy, LayoutParams(dp(48), dp(48)))

            val close = ImageButton(context).apply {
                setImageResource(R.drawable.ic_focus_close)
                background = context.getDrawable(android.R.drawable.list_selector_background)
                contentDescription = "关闭"
                setPadding(dp(13), dp(13), dp(13), dp(13))
                setOnClickListener { dialog.dismiss() }
            }
            toolbar.addView(close, LayoutParams(dp(48), dp(48)))
            root.addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

            val divider = TextView(context).apply {
                setBackgroundColor(context.getColor(R.color.d_focus_line))
            }
            root.addView(divider, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))

            val contentView = TextView(context).apply {
                this.text = text
                textSize = 15f
                setTextColor(context.getColor(R.color.d_focus_ink))
                setTextIsSelectable(true)
                setLineSpacing(dp(4).toFloat(), 1f)
                setPadding(dp(4), dp(18), dp(4), dp(28))
            }
            val scroll = ScrollView(context).apply {
                isFillViewport = true
                addView(
                    contentView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            root.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

            dialog.setContentView(root)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0.42f }
            }
            dialog.show()
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }
}
