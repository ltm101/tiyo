package com.koyo.screenwarden

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

internal data class ChatModeLine(
    val role: String,
    val text: String,
    val timestamp: Long
)

/** 房间的原生图层：窗光、墙面、书柜与地板都随主题换光线，不把概念稿硬贴成截图 */
internal class RoomSceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var palette = ThemeManager.roomPalette()
    private var customWindow = ThemeManager.roomWindowBitmap(context)

    fun refreshTheme() {
        palette = ThemeManager.roomPalette()
        customWindow = ThemeManager.roomWindowBitmap(context)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        paint.shader = LinearGradient(0f, 0f, w, h, palette.wallTop, palette.wallBottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        val window = RectF(w * .055f, h * .08f, w * .36f, h * .52f)
        val custom = customWindow
        if (custom != null) {
            canvas.save()
            canvas.clipRect(window)
            canvas.drawBitmap(custom, null, window, paint)
            canvas.restore()
        } else {
            paint.color = palette.window
            canvas.drawRoundRect(window, 8f * density, 8f * density, paint)
        }
        paint.color = palette.frame
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f * density
        canvas.drawRoundRect(window, 8f * density, 8f * density, paint)
        paint.strokeWidth = 2f * density
        canvas.drawLine(window.centerX(), window.top, window.centerX(), window.bottom, paint)
        canvas.drawLine(window.left, window.centerY(), window.right, window.centerY(), paint)
        paint.style = Paint.Style.FILL

        paint.color = palette.lightBeam
        val beam = android.graphics.Path().apply {
            moveTo(window.left, window.bottom)
            lineTo(window.right, window.bottom)
            lineTo(w * .78f, h)
            lineTo(w * .18f, h)
            close()
        }
        canvas.drawPath(beam, paint)

        paint.color = palette.furniture
        canvas.drawRoundRect(w * .76f, h * .13f, w * .98f, h * .72f, 10f * density, 10f * density, paint)
        paint.color = palette.shelfLine
        repeat(4) { index ->
            val y = h * (.24f + index * .11f)
            canvas.drawRect(w * .78f, y, w * .96f, y + 3f * density, paint)
        }
        paint.color = palette.floor
        canvas.drawRect(0f, h * .77f, w, h, paint)
        paint.color = Color.argb(30, 80, 48, 30)
        repeat(7) { index ->
            val y = h * .78f + index * h * .035f
            canvas.drawRect(0f, y, w, y + 1f, paint)
        }
    }
}

/** 书桌原生图层：木纹与中央卷轴由 View 绘制，消息再落到真正的可访问文本层 */
internal class DeskSceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        paint.shader = LinearGradient(0f, 0f, w, h, 0xFF8A5F3F.toInt(), 0xFF4F3425.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        paint.color = Color.argb(35, 42, 23, 14)
        val step = min(width, height).coerceAtLeast(1) / 9f
        var y = step
        while (y < h) {
            canvas.drawRect(0f, y, w, y + 2f, paint)
            y += step
        }
        paint.color = Color.argb(35, 255, 239, 210)
        canvas.drawOval(-w * .15f, -h * .1f, w * .75f, h * .55f, paint)
    }
}

/** 房间墙上的缩略记忆架，用纯矢量路径画，不带那只手 */
internal class ShelfView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density

    init {
        isClickable = true
        contentDescription = "记忆架"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        paint.color = 0x7A6D462E
        canvas.drawRoundRect(w * .08f, h * .22f, w * .92f, h * .84f, 9f * density, 9f * density, paint)
        paint.color = 0xFF9A6843.toInt()
        canvas.drawRoundRect(w * .02f, h * .72f, w * .98f, h * .86f, 5f * density, 5f * density, paint)
        val colors = intArrayOf(0xFF90B9D8.toInt(), 0xFFCBDFF0.toInt(), 0xFF7AC5E7.toInt(), 0xFFB49362.toInt(), 0xFFE2B963.toInt(), 0xFFD9D1C6.toInt())
        repeat(6) { index ->
            val cx = w * (.14f + index * .145f)
            val cy = h * .64f
            paint.color = colors[index]
            when (index) {
                2 -> {
                    val path = android.graphics.Path().apply {
                        moveTo(cx, cy - 20f * density)
                        lineTo(cx + 14f * density, cy + 8f * density)
                        lineTo(cx, cy + 2f * density)
                        lineTo(cx - 14f * density, cy + 8f * density)
                        close()
                    }
                    canvas.drawPath(path, paint)
                }
                3 -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 4f * density
                    canvas.drawCircle(cx, cy - 5f * density, 8f * density, paint)
                    canvas.drawLine(cx, cy + 3f * density, cx, cy + 20f * density, paint)
                    paint.style = Paint.Style.FILL
                }
                else -> canvas.drawCircle(cx, cy, (8 + index % 3 * 2) * density, paint)
            }
        }
    }
}
