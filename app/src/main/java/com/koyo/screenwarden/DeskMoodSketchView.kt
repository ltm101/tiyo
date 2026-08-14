package com.koyo.screenwarden

import android.animation.AnimatorListenerAdapter
import android.animation.Animator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/** One borderless paper doodle cropped from the generated four-mood Koyo atlas */
internal class DeskMoodSketchView(
    context: Context,
    initialMood: Int,
    private val onMoodChanged: (Int) -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val destination = RectF()
    private var mood = initialMood.mod(MOOD_COUNT)
    private var pressedInside = false
    private var restingRotation = 0f

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateDescription()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = atlas(context) ?: return
        val halfW = bitmap.width / 2
        val halfH = bitmap.height / 2
        val column = mood % 2
        val row = mood / 2
        val inset = (minOf(halfW, halfH) * .025f).toInt()
        source.set(
            column * halfW + inset,
            row * halfH + inset,
            (column + 1) * halfW - inset,
            (row + 1) * halfH - inset
        )
        destination.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(bitmap, source, destination, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedInside = true
                restingRotation = rotation
                animate().scaleX(.965f).scaleY(.965f).rotation(restingRotation + .7f).setDuration(90L).start()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                pressedInside = event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val activate = pressedInside
                pressedInside = false
                animate().scaleX(1f).scaleY(1f).rotation(restingRotation).setDuration(150L).start()
                if (activate) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedInside = false
                animate().scaleX(1f).scaleY(1f).rotation(restingRotation).setDuration(150L).start()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        animate().cancel()
        animate().alpha(.18f).scaleX(.94f).scaleY(.94f).setDuration(115L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    mood = (mood + 1) % MOOD_COUNT
                    updateDescription()
                    invalidate()
                    alpha = .18f
                    scaleX = .94f
                    scaleY = .94f
                    animate().alpha(.94f).scaleX(1f).scaleY(1f).setDuration(210L)
                        .setListener(null).start()
                    onMoodChanged(mood)
                }
            }).start()
        return true
    }

    private fun updateDescription() {
        contentDescription = "${CompanionProfileStore.activeName(context)}的心情素描，${MOOD_NAMES[mood]}，轻点换一张"
    }

    private companion object {
        const val MOOD_COUNT = 4
        val MOOD_NAMES = arrayOf("平静", "走神", "开心", "犯困")
        @Volatile private var cachedAtlas: Bitmap? = null

        fun atlas(context: Context): Bitmap? {
            cachedAtlas?.takeUnless(Bitmap::isRecycled)?.let { return it }
            return synchronized(this) {
                cachedAtlas?.takeUnless(Bitmap::isRecycled) ?: runCatching {
                    context.assets.open("deep_companion/desk/koyo_mood_sketch_atlas_v1.png")
                        .use(BitmapFactory::decodeStream)
                }.getOrNull()?.also { cachedAtlas = it }
            }
        }
    }
}
