package com.koyo.screenwarden

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Invisible semantic hit layers aligned to the authored scene
 *
 * The artwork itself is never copied or darkened here. A tap receives only a tiny borderless
 * glint, which avoids the black crop rectangles that previously appeared around desk and shelf
 * props while keeping every object independently interactive
 */
internal class DeepInteractiveLayersView(
    context: Context,
    private val foreground: Boolean,
    private val onElementTapped: (String) -> Unit
) : View(context) {
    private data class Element(
        val id: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    private val density = resources.displayMetrics.density
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val hitRects = LinkedHashMap<String, RectF>()
    private var scene = DeepCompanionHostView.Scene.ROOM
    private var downId: String? = null
    private var downX = 0f
    private var downY = 0f
    private var shelfSwiping = false
    private var activeId: String? = null
    private var pulse = 0f
    private var animator: ValueAnimator? = null
    private var guideStyle = false
    private var released = false

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun showScene(next: DeepCompanionHostView.Scene) {
        animator?.cancel()
        scene = next
        activeId = null
        pulse = 0f
        guideStyle = false
        invalidate()
    }

    /** A quiet one-shot discovery cue; never adds labels or permanent chrome. */
    fun guideElement(id: String): Boolean {
        if (released || width <= 0 || height <= 0) return false
        rebuildHitRects()
        if (!hitRects.containsKey(id)) return false
        animator?.cancel()
        activeId = id
        guideStyle = true
        animator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 1_050L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (guideStyle) {
                        activeId = null
                        pulse = 0f
                        guideStyle = false
                        invalidate()
                    }
                }
            })
            start()
        }
        return true
    }

    fun cancelGuide() {
        if (!guideStyle) return
        animator?.cancel()
        activeId = null
        pulse = 0f
        guideStyle = false
        invalidate()
    }

    private fun elements(): List<Element> = when (scene) {
        DeepCompanionHostView.Scene.ROOM -> if (foreground) listOf(
            // 人物框只覆盖身体主体，给左右两侧真实书桌和记忆架留下可点击区域
            Element("room_koyo", .27f, .08f, .73f, .96f)
        ) else listOf(
            Element("room_crystal", .00f, .08f, .25f, .38f),
            Element("room_desk", .00f, .40f, .30f, .76f),
            Element("room_shelf", .68f, .08f, 1.00f, .58f),
            Element("room_lantern", .78f, .58f, 1.00f, .98f),
            Element("room_curtain", .00f, .00f, 1.00f, .25f)
        )
        DeepCompanionHostView.Scene.DESK -> if (foreground) listOf(
            Element("desk_koyo", .22f, .02f, 1.00f, .31f),
            Element("desk_paper", .10f, .27f, .96f, .93f)
        ) else listOf(
            Element("desk_timer", .02f, .02f, .23f, .13f),
            Element("desk_plan", .01f, .12f, .26f, .25f),
            Element("desk_sticker", .01f, .27f, .24f, .41f)
        )
        DeepCompanionHostView.Scene.SHELF -> if (foreground) listOf(
            Element("shelf_day1", .05f, .31f, .21f, .47f),
            Element("shelf_day2", .22f, .31f, .41f, .47f),
            Element("shelf_day3", .41f, .29f, .63f, .47f),
            Element("shelf_day4", .65f, .30f, .89f, .47f),
            Element("shelf_day5", .06f, .57f, .32f, .73f),
            Element("shelf_day6", .35f, .57f, .59f, .73f),
            Element("shelf_day7", .60f, .57f, .96f, .73f)
        ) else emptyList()
        DeepCompanionHostView.Scene.DIARY -> if (foreground) listOf(
            Element("diary_key", .04f, .50f, .29f, .67f),
            Element("diary_train", .43f, .74f, .86f, .91f)
        ) else listOf(
            Element("diary_star_top", .25f, .06f, .66f, .28f),
            Element("diary_crane", .20f, .14f, .48f, .34f),
            Element("diary_tea", .01f, .31f, .28f, .47f),
            Element("diary_star_bottom", .15f, .58f, .47f, .82f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        rebuildHitRects()
        val rect = activeId?.let(hitRects::get) ?: return
        if (guideStyle) {
            drawGuide(canvas, rect)
            return
        }
        val fade = (1f - pulse).coerceIn(0f, 1f)
        val radius = min(rect.width(), rect.height()) * (.10f + pulse * .16f)
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        glintPaint.strokeWidth = dp(1.25f).toFloat()
        glintPaint.color = withAlpha(0xFFF5D8A5.toInt(), (fade * 150).toInt())
        canvas.drawCircle(centerX, centerY, radius, glintPaint)

        val ray = dp(7f) * (1f + pulse * .45f)
        glintPaint.strokeWidth = dp(1f).toFloat()
        glintPaint.color = withAlpha(0xFFD8F3FF.toInt(), (fade * 185).toInt())
        canvas.drawLine(centerX - ray, centerY, centerX + ray, centerY, glintPaint)
        canvas.drawLine(centerX, centerY - ray, centerX, centerY + ray, glintPaint)
    }

    private fun drawGuide(canvas: Canvas, source: RectF) {
        val rect = RectF(source).apply { inset(-dp(3f).toFloat(), -dp(3f).toFloat()) }
        val corner = min(rect.width(), rect.height()) * .12f
        val alpha = (pulse.coerceIn(0f, 1f) * 115f).toInt()

        glintPaint.strokeWidth = dp(5f).toFloat()
        glintPaint.color = withAlpha(0xFFFFD99A.toInt(), alpha / 3)
        canvas.drawRoundRect(rect, corner, corner, glintPaint)

        glintPaint.strokeWidth = dp(1.15f).toFloat()
        glintPaint.color = withAlpha(0xFFFFE8BE.toInt(), alpha)
        canvas.drawRoundRect(rect, corner, corner, glintPaint)

        val glintX = rect.right - corner * .55f
        val glintY = rect.top + corner * .55f
        val ray = dp(4.5f).toFloat() * (.8f + pulse * .35f)
        glintPaint.strokeWidth = dp(.9f).toFloat()
        glintPaint.color = withAlpha(0xFFF7FAFF.toInt(), (alpha * 1.25f).toInt())
        canvas.drawLine(glintX - ray, glintY, glintX + ray, glintY, glintPaint)
        canvas.drawLine(glintX, glintY - ray, glintX, glintY + ray, glintPaint)
    }

    private fun rebuildHitRects() {
        hitRects.clear()
        val source = DeepSceneTransform.sourceSize(scene)
        val sourceWidth = source.width
        val sourceHeight = source.height
        val transform = DeepSceneTransform.calculate(
            width.toFloat(), height.toFloat(), sourceWidth, sourceHeight, DeepSceneTransform.mode(scene)
        )
        elements().forEach { element ->
            hitRects[element.id] = RectF(
                element.left * sourceWidth * transform.scale + transform.offsetX,
                element.top * sourceHeight * transform.scale + transform.offsetY,
                element.right * sourceWidth * transform.scale + transform.offsetX,
                element.bottom * sourceHeight * transform.scale + transform.offsetY
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rebuildHitRects()
                val hit = hitRects.entries.lastOrNull { it.value.contains(event.x, event.y) }?.key
                    ?: return false
                downId = hit
                downX = event.x
                downY = event.y
                shelfSwiping = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (scene == DeepCompanionHostView.Scene.SHELF &&
                    abs(dx) > dp(22f) && abs(dx) > abs(dy) * 1.2f
                ) {
                    shelfSwiping = true
                    return true
                }
                if (abs(dx) > dp(22f) || abs(dy) > dp(22f)) downId = null
                return downId != null || shelfSwiping
            }
            MotionEvent.ACTION_UP -> {
                if (shelfSwiping) {
                    val dx = event.x - downX
                    shelfSwiping = false
                    downId = null
                    if (abs(dx) >= dp(48f)) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onElementTapped(if (dx < 0f) "shelf_page_older" else "shelf_page_newer")
                        performClick()
                        return true
                    }
                }
                val hit = downId
                downId = null
                if (hit != null && hitRects[hit]?.contains(event.x, event.y) == true) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    playPulse(hit)
                    onElementTapped(hit)
                    performClick()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                downId = null
                shelfSwiping = false
            }
        }
        return false
    }

    private fun playPulse(id: String) {
        animator?.cancel()
        activeId = id
        guideStyle = false
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    activeId = null
                    pulse = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    override fun performClick(): Boolean = super.performClick()

    private fun withAlpha(color: Int, alpha: Int): Int =
        color and 0x00FFFFFF or (alpha.coerceIn(0, 255) shl 24)

    fun release() {
        released = true
        animator?.cancel()
        animator = null
        guideStyle = false
        hitRects.clear()
    }

    private fun dp(value: Float) = (value * density).toInt()
}
