package com.koyo.screenwarden

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.OvershootInterpolator
import android.widget.EdgeEffect
import androidx.core.widget.NestedScrollView
import kotlin.math.min

/**
 * Today 页专用滚动容器。
 *
 * 唯一职责：页面在顶部时继续下拉 → 拦截并转成 0..1 的拉伸系数回调给
 * 宿主（拉猫耳朵），同时在顶部画出暖色边缘发光；松手弹性回位。
 * Android 原生没有 iOS 回弹，这个效果只给 Today，不外溢到其他页面。
 */
class TodayScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : NestedScrollView(context, attrs) {

    /** 拉伸回调：0 松手 ~ 1 拉满 */
    var onStretch: ((Float) -> Unit)? = null

    private val maxPull = dp(180f)
    private var downY = 0f
    private var pulling = false
    private var stretch = 0f
    private var settleAnimator: ValueAnimator? = null

    private val topGlow = EdgeEffect(context).apply {
        color = context.getColor(R.color.d_accent)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settleAnimator?.cancel()
                downY = ev.y
                pulling = false
            }
            MotionEvent.ACTION_MOVE -> {
                // 已到顶且手指继续往下拖：进入拉猫模式
                if (!canScrollVertically(-1) && ev.y - downY > 0) {
                    val raw = ev.y - downY
                    // 越拉越重：阻尼随距离衰减
                    stretch = min(raw * 0.42f, maxPull) / maxPull
                    pulling = true
                    onStretch?.invoke(stretch)
                    topGlow.onPull(stretch * 0.6f)
                    postInvalidateOnAnimation()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pulling) {
                    pulling = false
                    settleBack()
                    topGlow.onRelease()
                    postInvalidateOnAnimation()
                    return true
                }
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun settleBack() {
        settleAnimator?.cancel()
        val from = stretch
        settleAnimator = ValueAnimator.ofFloat(from, 0f).apply {
            duration = 420
            interpolator = OvershootInterpolator(1.4f)
            addUpdateListener {
                stretch = (it.animatedValue as Float).coerceAtLeast(0f)
                onStretch?.invoke(stretch)
            }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (!topGlow.isFinished) {
            val restore = canvas.save()
            topGlow.setSize(width, height / 3)
            topGlow.draw(canvas)
            canvas.restoreToCount(restore)
            postInvalidateOnAnimation()
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
