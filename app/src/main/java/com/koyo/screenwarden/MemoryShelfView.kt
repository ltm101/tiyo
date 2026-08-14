package com.koyo.screenwarden

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** 记忆架全屏层；右下角无字侧躺书只接受瞌睡状态下的慢速横向拖动 */
class MemoryShelfView(
    context: Context,
    private val isDrowsy: () -> Boolean,
    private val onClose: () -> Unit,
    private val onOpenJournal: () -> Unit
) : FrameLayout(context) {
    private val detail = TextView(context)
    private val density = resources.displayMetrics.density
    private val items = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private var loadedCount = 0

    init {
        elevation = dp(20).toFloat()
        background = ColorDrawable(Color.BLACK)
        addView(ImageView(context).apply {
            setImageResource(R.drawable.memory_shelf_no_hand)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, match())

        addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_focus_close)
            setColorFilter(Color.WHITE)
            background = context.getDrawable(android.R.drawable.list_selector_background)
            contentDescription = "关闭记忆架"
            setOnClickListener { onClose() }
        }, LayoutParams(dp(52), dp(52)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(16)
            marginEnd = dp(12)
        })

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(0xDDF9E9CF.toInt())
                cornerRadii = floatArrayOf(dp(22f), dp(22f), dp(22f), dp(22f), 0f, 0f, 0f, 0f)
            }
        }
        detail.apply {
            text = "每一次对话，都是一件小小的珍藏"
            textSize = 14f
            setTextColor(0xFF4F3A2B.toInt())
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(4), 0, dp(4), dp(9))
        }
        panel.addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        loadOlderPage()
        panel.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(items)
        })
        addView(panel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.BOTTOM })

        // 无视觉背景、无 contentDescription：它必须继续像画面里普通的一本侧躺书
        val hiddenBook = View(context)
        addView(hiddenBook, LayoutParams(dp(116), dp(176)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = dp(138)
            marginEnd = 0
        })
        var downX = 0f
        var downAt = 0L
        hiddenBook.setOnTouchListener { _, event ->
            if (!isDrowsy()) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downAt = event.eventTime
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = (event.eventTime - downAt).coerceAtLeast(1L)
                    val distance = downX - event.x
                    val speed = distance / elapsed.toFloat()
                    if (distance >= dp(72) && elapsed >= 850L && speed <= density * .22f) {
                        onOpenJournal()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
    }

    private fun loadOlderPage() {
        val page = MemoryShelfStore.pagedEntries(context, loadedCount, 14)
        items.findViewWithTag<View>(LOAD_MORE_TAG)?.let(items::removeView)
        var insertAt = 0
        page.sortedBy { it.date }.forEach { entry ->
            items.addView(createEntryView(entry), insertAt++)
        }
        loadedCount += page.size
        if (loadedCount < MemoryShelfStore.totalEntryCount(context)) {
            items.addView(TextView(context).apply {
                tag = LOAD_MORE_TAG
                text = "更早"
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(0xFF6D513C.toInt())
                background = GradientDrawable().apply {
                    setColor(0x9AEEE0C8.toInt())
                    cornerRadius = dp(12).toFloat()
                }
                setOnClickListener { loadOlderPage() }
            }, 0, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginEnd = dp(8)
            })
        }
    }

    private fun createEntryView(entry: MemoryShelfStore.Entry) = TextView(context).apply {
        text = "${entry.objectName.ifBlank { objectLabel(entry.objectId) }}\n${entry.date.takeLast(5)}"
        gravity = Gravity.CENTER
        textSize = 12f
        setTextColor(0xFF594333.toInt())
        background = GradientDrawable().apply {
            setColor(0xB8FFF8EA.toInt())
            setStroke(dp(1), 0x709B7650)
            cornerRadius = dp(12).toFloat()
        }
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener {
            detail.text = "${entry.date} · ${entry.mood}\n${entry.summary}"
        }
        layoutParams = LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8)
        }
    }

    private fun objectLabel(id: String): String = when (id) {
        "screw" -> "螺丝"
        "glass_orb" -> "玻璃珠"
        "paper_crane" -> "纸鹤"
        "key" -> "钥匙"
        "pressed_flower" -> "押花"
        "paper_ball" -> "纸团"
        else -> "旧书"
    }

    private fun match() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    private fun dp(value: Int) = (value * density).toInt()
    private fun dp(value: Float) = value * density

    companion object {
        private const val LOAD_MORE_TAG = "memory_shelf_load_more"
    }
}
