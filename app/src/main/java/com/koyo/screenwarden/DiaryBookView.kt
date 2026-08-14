package com.koyo.screenwarden

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.text.BreakIterator
import java.util.Locale

/** 双重锁后的无入口翻书层：只呈现 journal_cache 中已经落盘的页面 */
class DiaryBookView(
    context: Context,
    private val onClose: () -> Unit
) : View(context) {
    private val pages = MemoryShelfStore.journalEntries(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var page = (pages.size - 1).coerceAtLeast(0)
    private var downX = 0f
    private var downY = 0f
    private val density = resources.displayMetrics.density

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        requestFocus()
        setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onClose(); true
            } else false
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xE83B2A20.toInt())
        val margin = 18f * density
        val book = RectF(margin, margin * 1.7f, width - margin, height - margin * 1.5f)
        paint.color = 0xFFF3E4C8.toInt()
        paint.setShadowLayer(18f * density, 0f, 8f * density, 0x9A000000.toInt())
        canvas.drawRoundRect(book, 10f * density, 10f * density, paint)
        paint.clearShadowLayer()
        paint.color = 0x225A3D2B
        canvas.drawRect(book.centerX() - density, book.top, book.centerX() + density, book.bottom, paint)

        if (pages.isEmpty()) return
        val (date, text) = pages[page]
        paint.typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        paint.color = 0xFF6A4F3C.toInt()
        paint.textSize = 16f * density
        canvas.drawText(date, book.left + 26f * density, book.top + 42f * density, paint)
        paint.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        paint.textSize = 15f * density
        val maxWidth = book.width() - 52f * density
        val lineHeight = 28f * density
        var y = book.top + 84f * density
        wrap(text, maxWidth).take(18).forEach { line ->
            canvas.drawText(line, book.left + 26f * density, y, paint)
            y += lineHeight
        }
    }

    private fun wrap(text: String, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        text.lines().forEach { paragraph ->
            if (paragraph.isBlank()) {
                lines += ""
            } else {
                var current = ""
                val iterator = BreakIterator.getCharacterInstance(Locale.getDefault()).apply { setText(paragraph) }
                var start = iterator.first()
                var end = iterator.next()
                while (end != BreakIterator.DONE) {
                    val next = current + paragraph.substring(start, end)
                    if (paint.measureText(next) > maxWidth && current.isNotEmpty()) {
                        lines += current
                        current = paragraph.substring(start, end)
                    } else current = next
                    start = end
                    end = iterator.next()
                }
                if (current.isNotEmpty()) lines += current
            }
        }
        return lines
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (dy > 90f * density) {
                    onClose()
                } else if (kotlin.math.abs(dx) > 64f * density && pages.isNotEmpty()) {
                    val next = if (dx < 0) (page + 1).coerceAtMost(pages.lastIndex) else (page - 1).coerceAtLeast(0)
                    if (next != page) {
                        animate().alpha(0f).translationX(if (dx < 0) -24f * density else 24f * density)
                            .setDuration(150L).withEndAction {
                                page = next
                                translationX = if (dx < 0) 24f * density else -24f * density
                                invalidate()
                                animate().alpha(1f).translationX(0f).setDuration(190L).start()
                            }.start()
                    }
                }
                return true
            }
        }
        return true
    }
}
