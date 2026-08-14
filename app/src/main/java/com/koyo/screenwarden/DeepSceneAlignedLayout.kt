package com.koyo.screenwarden

import android.content.Context
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup

/** Positions interactive paper props in the same authored coordinate space as the scene bitmap */
internal class DeepSceneAlignedLayout(
    context: Context,
    private val scene: DeepCompanionHostView.Scene
) : ViewGroup(context) {
    class SourceLayoutParams(val rect: RectF) : LayoutParams(0, 0)

    fun addSourceView(view: View, rect: RectF) {
        addView(view, SourceLayoutParams(RectF(rect)))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val source = DeepSceneTransform.sourceSize(scene)
        val transform = DeepSceneTransform.calculate(
            w.toFloat(), h.toFloat(), source.width, source.height, DeepSceneTransform.mode(scene)
        )
        for (i in 0 until childCount) {
            val lp = getChildAt(i).layoutParams as SourceLayoutParams
            val cw = (lp.rect.width() * source.width * transform.scale).toInt().coerceAtLeast(1)
            val ch = (lp.rect.height() * source.height * transform.scale).toInt().coerceAtLeast(1)
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val source = DeepSceneTransform.sourceSize(scene)
        val transform = DeepSceneTransform.calculate(
            (r - l).toFloat(), (b - t).toFloat(), source.width, source.height, DeepSceneTransform.mode(scene)
        )
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val rect = (child.layoutParams as SourceLayoutParams).rect
            val left = (transform.offsetX + rect.left * source.width * transform.scale).toInt()
            val top = (transform.offsetY + rect.top * source.height * transform.scale).toInt()
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }
}
