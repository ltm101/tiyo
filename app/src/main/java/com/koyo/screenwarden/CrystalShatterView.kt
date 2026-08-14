package com.koyo.screenwarden

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Blue-crystal transition: fine cracks first, translucent shards second, never black broken glass. */
internal class CrystalShatterView(context: Context) : View(context) {
    private data class Ray(val angle: Float, val length: Float, val bend: Float)
    private data class Shard(val angle: Float, val radius: Float, val size: Float, val spin: Float)

    private val density = resources.displayMetrics.density
    private val seed = Random(0x4B4F594F)
    private val rays = List(15) { index ->
        val base = (index / 15f * PI * 2).toFloat()
        Ray(base + seed.nextFloat() * .14f, .35f + seed.nextFloat() * .58f, -.20f + seed.nextFloat() * .4f)
    }
    private val shards = List(18) { index ->
        Shard(
            angle = (index / 18f * PI * 2 + seed.nextFloat() * .18f).toFloat(),
            radius = .25f + seed.nextFloat() * .62f,
            size = 10f + seed.nextFloat() * 24f,
            spin = -1.1f + seed.nextFloat() * 2.2f
        )
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(7f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    private var originX = 0f
    private var originY = 0f
    private var animator: ValueAnimator? = null
    private var chargeAnimator: ValueAnimator? = null
    private var chargeProgress = 0f
    private var charging = false

    fun startCharge(x: Float, y: Float) {
        originX = x
        originY = y
        animator?.cancel()
        chargeAnimator?.cancel()
        charging = true
        chargeProgress = 0f
        progress = 0f
        alpha = 1f
        visibility = VISIBLE
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        chargeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 10_000L
            interpolator = LinearInterpolator()
            addUpdateListener {
                chargeProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun cancelCharge() {
        if (!charging) return
        charging = false
        chargeAnimator?.cancel()
        chargeAnimator = null
        animate().cancel()
        animate().alpha(0f).setDuration(150L).withEndAction {
            if (!charging && progress <= 0f) visibility = GONE
            alpha = 1f
            chargeProgress = 0f
            invalidate()
        }.start()
    }

    fun play(x: Float, y: Float, onRoomReveal: () -> Unit, onFinished: () -> Unit) {
        chargeAnimator?.cancel()
        chargeAnimator = null
        charging = false
        chargeProgress = 0f
        originX = x
        originY = y
        visibility = VISIBLE
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        animator?.cancel()
        var revealed = false
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 920L
            interpolator = DecelerateInterpolator(1.35f)
            addUpdateListener {
                progress = it.animatedValue as Float
                if (!revealed && progress >= .26f) {
                    revealed = true
                    onRoomReveal()
                }
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    visibility = GONE
                    progress = 0f
                    onFinished()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (charging) drawCharge(canvas)
        if (progress <= 0f) return
        val maxRadius = kotlin.math.hypot(width.toFloat(), height.toFloat()) * .68f
        val crackPhase = (progress / .58f).coerceIn(0f, 1f)
        val shardPhase = ((progress - .28f) / .72f).coerceIn(0f, 1f)
        val washAlpha = (115f * sin((progress * PI).toFloat())).toInt().coerceIn(0, 115)
        canvas.drawColor(Color.argb(washAlpha, 104, 189, 233))

        rays.forEachIndexed { index, ray ->
            val reach = maxRadius * ray.length * crackPhase
            val path = Path().apply {
                moveTo(originX, originY)
                val a1 = ray.angle + ray.bend * .35f
                val a2 = ray.angle - ray.bend * .25f
                lineTo(originX + cos(a1) * reach * .37f, originY + sin(a1) * reach * .37f)
                lineTo(originX + cos(a2) * reach * .72f, originY + sin(a2) * reach * .72f)
                lineTo(originX + cos(ray.angle) * reach, originY + sin(ray.angle) * reach)
            }
            glow.color = Color.argb((150 * (1f - progress * .7f)).toInt(), 99, 201, 255)
            glow.strokeWidth = (2.4f + index % 3) * density
            canvas.drawPath(path, glow)
            line.color = Color.argb((235 * (1f - progress * .62f)).toInt(), 222, 246, 255)
            line.strokeWidth = if (index % 4 == 0) 1.45f * density else .85f * density
            canvas.drawPath(path, line)
        }

        shards.forEachIndexed { index, shard ->
            val distance = maxRadius * shard.radius * shardPhase
            val cx = originX + cos(shard.angle) * distance
            val cy = originY + sin(shard.angle) * distance
            val size = shard.size * density * (1f + shardPhase * .35f)
            val rotation = shard.spin * shardPhase
            val path = Path().apply {
                val a = shard.angle + rotation
                moveTo(cx + cos(a) * size, cy + sin(a) * size)
                lineTo(cx + cos(a + 2.2f) * size * .72f, cy + sin(a + 2.2f) * size * .72f)
                lineTo(cx + cos(a - 2.0f) * size * .52f, cy + sin(a - 2.0f) * size * .52f)
                close()
            }
            fill.color = Color.argb(
                (118 * (1f - shardPhase)).toInt().coerceIn(0, 118),
                115 + index % 3 * 18,
                205,
                246
            )
            canvas.drawPath(path, fill)
            line.color = Color.argb((190 * (1f - shardPhase)).toInt().coerceIn(0, 190), 224, 249, 255)
            line.strokeWidth = .8f * density
            canvas.drawPath(path, line)
        }
    }

    private fun drawCharge(canvas: Canvas) {
        val breathe = .5f + .5f * sin((System.currentTimeMillis() % 1_500L) / 1_500f * PI * 2).toFloat()
        val radius = density * (22f + chargeProgress * 34f + breathe * 2.5f)
        glow.color = Color.argb((48 + chargeProgress * 82).toInt(), 103, 200, 247)
        glow.strokeWidth = density * (1.2f + chargeProgress * 1.6f)
        canvas.drawCircle(originX, originY, radius, glow)
        line.color = Color.argb((125 + chargeProgress * 100).toInt().coerceAtMost(235), 222, 248, 255)
        line.strokeWidth = density * 1.05f
        canvas.drawCircle(originX, originY, radius * .82f, line)
        repeat(6) { index ->
            val angle = index / 6f * PI.toFloat() * 2f + chargeProgress * .75f
            val distance = radius * (1.05f + .08f * sin(index + breathe))
            val cx = originX + cos(angle) * distance
            val cy = originY + sin(angle) * distance
            fill.color = Color.argb((105 + chargeProgress * 125).toInt().coerceAtMost(230), 201, 241, 255)
            canvas.drawCircle(cx, cy, density * (1.3f + chargeProgress * 1.5f), fill)
        }
        postInvalidateOnAnimation()
    }

    fun release() {
        animator?.cancel()
        animator = null
        chargeAnimator?.cancel()
        chargeAnimator = null
        charging = false
    }
}
