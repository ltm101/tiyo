package com.koyo.screenwarden

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/** 房间相处模式：平时只有房间、可又与真实物件，触摸后才浮出输入光线 */
internal class RoomModeController(
    private val context: Context,
    val container: FrameLayout,
    private val onSend: (String) -> Unit,
    private val onImage: () -> Unit,
    private val onCamera: () -> Unit,
    private val onVoice: () -> Unit,
    private val onDesk: () -> Unit,
    private val onFocus: () -> Unit,
    private val onShelf: () -> Unit
) {
    val heroSlot: View
    private val scene = RoomSceneView(context)
    private val speech = TextView(context)
    private val replay = LinearLayout(context)
    private val inputBar = LinearLayout(context)
    private val edit = EditText(context)
    private val handler = Handler(Looper.getMainLooper())
    private var fadeSpeech: Runnable? = null
    private var paper: View? = null
    private var history: List<ChatModeLine> = emptyList()
    private var downY = 0f
    private var replayShowing = false

    init {
        container.removeAllViews()
        container.isClickable = true
        container.isFocusable = true
        container.background = ThemeManager.buildRoomBackground(context)
        container.addView(scene, match())

        heroSlot = View(context)
        container.addView(heroSlot, FrameLayout.LayoutParams(dp(292), dp(292)).apply {
            gravity = Gravity.CENTER
            topMargin = dp(20)
        })

        speech.apply {
            setTextColor(0xFFF8F2EA.toInt())
            textSize = 18f
            setLineSpacing(dp(7).toFloat(), 1f)
            letterSpacing = .04f
            setShadowLayer(dp(5).toFloat(), 0f, dp(2).toFloat(), 0x8A4A3025.toInt())
            alpha = 0f
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        container.addView(speech, FrameLayout.LayoutParams(dp(154), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            marginEnd = dp(20)
            topMargin = dp(30)
        })

        val shelf = ShelfView(context).apply { setOnClickListener { onShelf() } }
        container.addView(shelf, FrameLayout.LayoutParams(dp(92), dp(170)).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            marginStart = dp(5)
            topMargin = dp(28)
        })

        replay.apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            alpha = 0f
            background = rounded(0xE8FFF9EF.toInt(), dp(22).toFloat())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            elevation = dp(8).toFloat()
        }
        container.addView(replay, FrameLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        buildInputBar()
        container.addView(inputBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            marginStart = dp(14)
            marginEnd = dp(14)
            bottomMargin = dp(24)
        })

        container.setOnClickListener { revealInput() }
        container.setOnLongClickListener {
            onVoice()
            true
        }
        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!replayShowing && downY - event.y > dp(72)) {
                        showReplay()
                        replayShowing = true
                        true
                    } else replayShowing
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (replayShowing) {
                        hideReplay()
                        replayShowing = false
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    private fun buildInputBar() {
        inputBar.orientation = LinearLayout.HORIZONTAL
        inputBar.gravity = Gravity.CENTER_VERTICAL
        inputBar.visibility = View.GONE
        inputBar.alpha = 0f
        inputBar.background = GradientDrawable().apply {
            setColor(0xA8FFF9EF.toInt())
            setStroke(dp(1), 0xB8FFFFFF.toInt())
            cornerRadius = dp(22).toFloat()
        }
        inputBar.elevation = dp(6).toFloat()
        edit.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            background = null
            hint = "轻轻输入你想对她说的话…"
            setHintTextColor(0xFF8D7464.toInt())
            setTextColor(0xFF3A2C25.toInt())
            textSize = 15f
            maxLines = 3
            setPadding(dp(15), 0, dp(6), 0)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    sendText()
                    true
                } else false
            }
        }
        inputBar.addView(edit)
        inputBar.addView(lightButton(R.drawable.ic_focus_image, "选择图片", onImage))
        inputBar.addView(lightButton(R.drawable.ic_focus_camera, "拍照", onCamera))
        inputBar.addView(lightButton(R.drawable.ic_focus_mic, "语音输入", onVoice))
        inputBar.addView(lightButton(R.drawable.ic_focus_workspace, "到桌子边", onDesk))
        inputBar.addView(lightButton(R.drawable.ic_focus_send, "发送") { sendText() })
    }

    private fun lightButton(icon: Int, description: String, action: () -> Unit): ImageButton =
        ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setImageResource(icon)
            setColorFilter(0xFF715A4A.toInt())
            background = context.getDrawable(android.R.drawable.list_selector_background)
            contentDescription = description
            setOnClickListener { action() }
        }

    fun revealInput() {
        if (inputBar.visibility != View.VISIBLE) {
            inputBar.visibility = View.VISIBLE
            inputBar.translationY = dp(8).toFloat()
            inputBar.animate().alpha(1f).translationY(0f).setDuration(240L).start()
        }
        edit.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
    }

    fun setRecognizedText(text: String) {
        revealInput()
        edit.setText(text)
        edit.setSelection(text.length)
    }

    private fun sendText() {
        val text = edit.text.toString().trim()
        if (text.isEmpty()) return
        edit.isEnabled = false
        inputBar.animate().alpha(0f).translationY(-dp(76).toFloat()).setDuration(280L).withEndAction {
            edit.setText("")
            edit.isEnabled = true
            inputBar.visibility = View.GONE
            inputBar.translationY = 0f
            onSend(text)
        }.start()
    }

    fun render(lines: List<ChatModeLine>) {
        history = lines
        lines.lastOrNull { it.role == "assistant" && it.text.isNotBlank() }?.let {
            showAssistant(it.text, autoFade = false)
        }
    }

    fun onMessage(line: ChatModeLine) {
        history = (history + line).takeLast(60)
        if (line.role == "assistant") showAssistant(line.text, autoFade = true)
    }

    fun setThinking(thinking: Boolean) {
        if (thinking) {
            showAssistant("我在想…", autoFade = false)
        } else if (speech.text == "我在想…") {
            speech.animate().alpha(0f).setDuration(180L).withEndAction {
                speech.visibility = View.GONE
            }.start()
        }
    }

    private fun showAssistant(text: String, autoFade: Boolean) {
        if (PaperSheetView.shouldUse(text)) {
            speech.visibility = View.GONE
            paper?.let { (it.parent as? ViewGroup)?.removeView(it) }
            val sheet = PaperSheetView(context).bind(PaperSheetView.titleFor(text), text)
            paper = sheet
            container.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
                marginStart = dp(18)
                marginEnd = dp(18)
                bottomMargin = dp(96)
            })
            sheet.alpha = 0f
            sheet.translationY = dp(12).toFloat()
            sheet.animate().alpha(1f).translationY(0f).setDuration(320L).start()
            return
        }
        paper?.let { old ->
            old.animate().alpha(0f).setDuration(160L).withEndAction {
                (old.parent as? ViewGroup)?.removeView(old)
            }.start()
        }
        paper = null
        fadeSpeech?.let(handler::removeCallbacks)
        speech.text = text.split(Regex("(?<=[。！？!?])"))
            .map(String::trim).filter(String::isNotEmpty).take(5).joinToString("\n\n")
        speech.visibility = View.VISIBLE
        speech.animate().cancel()
        speech.translationY = dp(8).toFloat()
        speech.animate().alpha(1f).translationY(0f).setDuration(360L).start()
        if (autoFade) {
            val delay = if (text.length > 80) 12_000L else 9_000L
            fadeSpeech = Runnable {
                speech.animate().alpha(0f).translationY(-dp(5).toFloat()).setDuration(420L)
                    .withEndAction { speech.visibility = View.GONE }.start()
            }.also { handler.postDelayed(it, delay) }
        }
    }

    private fun showReplay() {
        replay.removeAllViews()
        history.filter { it.role == "user" || it.role == "assistant" }.takeLast(10).forEach { line ->
            replay.addView(TextView(context).apply {
                text = line.text.take(120)
                textSize = if (line.role == "assistant") 14f else 13f
                setTextColor(if (line.role == "assistant") 0xFF3B3029.toInt() else 0xFF76685E.toInt())
                setPadding(dp(9), dp(7), dp(9), dp(7))
                setOnClickListener {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("chat", line.text))
                }
            })
        }
        replay.visibility = View.VISIBLE
        replay.translationY = dp(28).toFloat()
        replay.animate().alpha(1f).translationY(0f).setDuration(260L).start()
    }

    private fun hideReplay() {
        replay.animate().alpha(0f).translationY(-dp(18).toFloat()).setDuration(240L)
            .withEndAction { replay.visibility = View.GONE }.start()
    }

    fun refreshTheme() {
        container.background = ThemeManager.buildRoomBackground(context)
        scene.refreshTheme()
    }

    fun release() {
        fadeSpeech?.let(handler::removeCallbacks)
        paper = null
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
