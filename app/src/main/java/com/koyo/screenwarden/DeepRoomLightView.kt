package com.koyo.screenwarden

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

/** Grounds the transparent model with contact shadow and the room's mixed moon/amber light. */
internal class DeepRoomLightView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        paint.shader = RadialGradient(
            width * .50f,
            height * .82f,
            width * .24f,
            intArrayOf(0x6201060D, 0x2601060D, 0x0001060D),
            floatArrayOf(0f, .46f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.maskFilter = BlurMaskFilter(9f * density, BlurMaskFilter.Blur.NORMAL)
        canvas.drawOval(width * .31f, height * .79f, width * .69f, height * .86f, paint)
        paint.maskFilter = null

        paint.shader = LinearGradient(
            0f, height * .15f, width.toFloat(), height * .68f,
            intArrayOf(0x10EBAF75, 0x00000000, 0x0F78AEE0),
            floatArrayOf(0f, .52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }
}
