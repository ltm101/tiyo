package com.koyo.screenwarden

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 书桌共事模式：同一份消息历史被排成中央长卷，用户消息成为右缘批注 */
internal class DeskModeController(
    private val context: Context,
    val container: FrameLayout,
    private val onSend: (String) -> Unit,
    private val onImage: () -> Unit,
    private val onFile: () -> Unit,
    private val onClearAttachments: () -> Unit,
    private val onRoom: () -> Unit,
    private val onFocus: () -> Unit,
    private val onSessions: () -> Unit
) {
    val heroSlot: View
    private val messageList = LinearLayout(context)
    private val scroll = ScrollView(context)
    private val edit = EditText(context)
    private val attachmentStatus = TextView(context)
    private val time = SimpleDateFormat("HH:mm", Locale.CHINA)

    init {
        container.removeAllViews()
        container.addView(DeskSceneView(context), match())

        val folder = TextView(context).apply {
            text = "会话文件"
            textSize = 13f
            setTextColor(0xFFF5E6CD.toInt())
            gravity = Gravity.CENTER
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_focus_file, 0, 0, 0)
            compoundDrawablePadding = dp(7)
            background = rounded(0xC05B3C2B.toInt(), dp(11).toFloat())
            setOnClickListener { onSessions() }
        }
        container.addView(folder, FrameLayout.LayoutParams(dp(126), dp(48)).apply {
            gravity = Gravity.TOP or Gravity.START
            marginStart = dp(16)
            topMargin = dp(18)
        })

        val modeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(modeButton(R.drawable.d_ic_chat, "回到房间", onRoom))
            addView(modeButton(R.drawable.ic_focus_quick, "进入专注", onFocus))
        }
        container.addView(modeRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
            gravity = Gravity.TOP or Gravity.END
            marginEnd = dp(14)
            topMargin = dp(18)
        })

        heroSlot = View(context)
        container.addView(heroSlot, FrameLayout.LayoutParams(dp(178), dp(178)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(2)
        })

        messageList.orientation = LinearLayout.VERTICAL
        messageList.setPadding(dp(12), dp(25), dp(12), dp(34))
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(messageList)
        scroll.background = GradientDrawable().apply {
            setColor(0xF7F7E9D1.toInt())
            setStroke(dp(1), 0x759B7650)
            cornerRadius = dp(5).toFloat()
        }
        scroll.elevation = dp(7).toFloat()
        container.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.TOP or Gravity.BOTTOM
            marginStart = dp(28)
            marginEnd = dp(28)
            topMargin = dp(118)
            bottomMargin = dp(154)
        })

        val quick = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf("继续刚才的" to "继续刚才的工作", "整理一下" to "整理一下目前的进度", "下一步" to "告诉我接下来最该做什么").forEach { (label, prompt) ->
                    addView(TextView(context).apply {
                        text = label
                        textSize = 12f
                        setTextColor(0xFF4F3829.toInt())
                        gravity = Gravity.CENTER
                        background = rounded(0xFFF2D69B.toInt(), dp(8).toFloat())
                        setPadding(dp(13), 0, dp(13), 0)
                        setOnClickListener { onSend(prompt) }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginEnd = dp(8) })
                }
            })
        }
        container.addView(quick, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
            gravity = Gravity.BOTTOM
            marginStart = dp(18)
            marginEnd = dp(18)
            bottomMargin = dp(102)
        })

        attachmentStatus.apply {
            visibility = View.GONE
            textSize = 11f
            setTextColor(0xFF6E543E.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(0xDDF0DEC0.toInt(), dp(10).toFloat())
            setOnClickListener { onClearAttachments() }
        }
        container.addView(attachmentStatus, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)).apply {
            gravity = Gravity.BOTTOM
            marginStart = dp(20)
            marginEnd = dp(20)
            bottomMargin = dp(68)
        })

        val composer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xF7FFF8E9.toInt(), dp(18).toFloat())
            elevation = dp(5).toFloat()
        }
        composer.addView(composerButton(R.drawable.ic_focus_image, "选择图片", onImage))
        composer.addView(composerButton(R.drawable.ic_focus_file, "选择文件", onFile))
        edit.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            background = null
            hint = "在卷轴边写下你的话…"
            setHintTextColor(0xFF9C846B.toInt())
            setTextColor(0xFF392A22.toInt())
            textSize = 15f
            setPadding(dp(15), 0, dp(8), 0)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_SEND || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    send(); true
                } else false
            }
        }
        composer.addView(edit)
        composer.addView(modeButton(R.drawable.ic_focus_send, "发送") { send() })
        container.addView(composer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            gravity = Gravity.BOTTOM
            marginStart = dp(16)
            marginEnd = dp(16)
            bottomMargin = dp(10)
        })
    }

    private fun send() {
        val text = edit.text.toString().trim()
        if (text.isEmpty()) return
        edit.setText("")
        onSend(text)
    }

    fun setRecognizedText(text: String) {
        edit.setText(text)
        edit.setSelection(text.length)
        edit.requestFocus()
    }

    fun setPendingAttachments(names: List<String>) {
        attachmentStatus.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
        attachmentStatus.text = if (names.isEmpty()) "" else {
            "已放上桌：${names.take(3).joinToString("、")}" +
                if (names.size > 3) " 等${names.size}项 · 点此清空" else " · 点此清空"
        }
    }

    fun focusInput() {
        edit.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    fun render(lines: List<ChatModeLine>) {
        messageList.removeAllViews()
        lines.forEach(::addLine)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    fun onMessage(line: ChatModeLine) {
        addLine(line)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addLine(line: ChatModeLine) {
        if (line.text.isBlank()) return
        if (line.role != "system" && PaperSheetView.shouldUse(line.text)) {
            messageList.addView(PaperSheetView(context).bind(PaperSheetView.titleFor(line.text), line.text))
            return
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (line.role == "user") Gravity.END or Gravity.TOP else Gravity.START or Gravity.TOP
            setPadding(0, dp(6), 0, dp(12))
        }
        row.addView(TextView(context).apply {
            text = time.format(Date(line.timestamp))
            textSize = 10f
            setTextColor(0x76947C65)
            gravity = Gravity.TOP
            setPadding(0, dp(3), dp(10), 0)
        }, LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(context).apply {
            text = line.text
            textSize = when (line.role) { "user" -> 13f; "system" -> 12f; else -> 15f }
            setTextColor(when (line.role) { "user" -> 0xFF75624F.toInt(); "system" -> 0xFF967F68.toInt(); else -> 0xFF3C3026.toInt() })
            gravity = if (line.role == "user") Gravity.END else Gravity.START
            setLineSpacing(dp(3).toFloat(), 1f)
            if (line.role == "user") {
                background = GradientDrawable().apply {
                    setColor(0x16A77E54)
                    setStroke(0, 0)
                    cornerRadius = dp(4).toFloat()
                }
                setPadding(dp(12), dp(5), dp(8), dp(5))
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        messageList.addView(row)
    }

    private fun modeButton(icon: Int, description: String, action: () -> Unit): ImageButton = ImageButton(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setImageResource(icon)
        setColorFilter(0xFFF4E4CC.toInt())
        background = context.getDrawable(android.R.drawable.list_selector_background)
        contentDescription = description
        setOnClickListener { action() }
    }

    private fun composerButton(icon: Int, description: String, action: () -> Unit): ImageButton =
        ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(48))
            setImageResource(icon)
            setColorFilter(0xFF694C36.toInt())
            background = context.getDrawable(android.R.drawable.list_selector_background)
            contentDescription = description
            setOnClickListener { action() }
        }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
