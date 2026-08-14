package com.koyo.screenwarden

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.sin

/**
 * Full-scene renderer for deep companion mode
 *
 * Koyo is painted into every reference frame, so hair, contact light and perspective remain part
 * of the room instead of being reconstructed with a keyed surface or a rectangular sprite layer
 */
internal class DeepCompanionSceneView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlay = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val loader = Executors.newSingleThreadExecutor()
    private val cache = LinkedHashMap<String, Bitmap>()
    private var baseAsset = ""
    private var accentAsset: String? = null
    private var baseBitmap: Bitmap? = null
    private var accentBitmap: Bitmap? = null
    private var backdropBitmap: Bitmap? = null
    private var renderMode = DeepSceneTransform.Mode.COVER
    private var requestGeneration = 0
    private var accentIntervalMs = 4_700L
    private var accentDurationMs = 520L
    private var nextAccentAt = 0L
    @Volatile private var released = false
    private var parallaxX = 0f
    private var parallaxY = 0f
    private var downX = 0f
    private var downY = 0f

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun showFrames(
        primary: String,
        accent: String? = null,
        intervalMs: Long = 4_700L,
        durationMs: Long = 520L
    ) {
        if (released) return
        baseAsset = primary
        renderMode = when {
            primary.contains("fullscreen") -> DeepSceneTransform.Mode.COVER
            primary.startsWith("scenes/desk") || primary.startsWith("scenes/shelf") ->
                DeepSceneTransform.Mode.FIT_WIDTH
            else -> DeepSceneTransform.Mode.COVER
        }
        accentAsset = accent
        accentIntervalMs = intervalMs.coerceAtLeast(700L)
        accentDurationMs = durationMs.coerceIn(160L, accentIntervalMs - 80L)
        nextAccentAt = System.currentTimeMillis() + minOf(accentIntervalMs, 1_400L)
        baseBitmap = cache[primary]
        accentBitmap = accent?.let(cache::get)
        val generation = ++requestGeneration
        load(primary, generation)
        accent?.let { load(it, generation) }
        alpha = if (baseBitmap == null) 0f else 1f
        invalidate()
    }

    fun pulseNow() {
        if (accentAsset != null) {
            nextAccentAt = System.currentTimeMillis()
            invalidate()
        }
    }

    private fun load(asset: String, generation: Int) {
        cache[asset]?.let {
            applyLoaded(asset, it, generation)
            return
        }
        loader.execute {
            val decoded = runCatching {
                CompanionAssetPack.sceneFile(context, asset)?.let { custom ->
                    BitmapFactory.decodeFile(custom.absolutePath)
                } ?: context.assets.open("deep_companion/$asset").use(BitmapFactory::decodeStream)
            }.getOrNull() ?: return@execute
            post {
                if (released) {
                    decoded.recycle()
                    return@post
                }
                cache.put(asset, decoded)?.takeIf { it !== decoded && !it.isRecycled }?.recycle()
                applyLoaded(asset, decoded, generation)
                trimCache()
            }
        }
    }

    private fun applyLoaded(asset: String, bitmap: Bitmap, generation: Int) {
        if (released || generation != requestGeneration) return
        when (asset) {
            baseAsset -> {
                baseBitmap = bitmap
                backdropBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                backdropBitmap = if (renderMode == DeepSceneTransform.Mode.FIT_WIDTH) {
                    Bitmap.createScaledBitmap(bitmap, 48, 72, true)
                } else null
                if (alpha < 1f) animate().alpha(1f).setDuration(360L).start()
            }
            accentAsset -> accentBitmap = bitmap
        }
        invalidate()
    }

    private fun trimCache() {
        while (cache.size > 4) {
            val removable = cache.entries.firstOrNull {
                it.value !== baseBitmap && it.value !== accentBitmap
            } ?: return
            cache.remove(removable.key)
            removable.value.recycle()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val maxShift = 5f * resources.displayMetrics.density
                parallaxX = ((event.x - downX) * .020f).coerceIn(-maxShift, maxShift)
                parallaxY = ((event.y - downY) * .014f).coerceIn(-maxShift, maxShift)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parallaxX = 0f
                parallaxY = 0f
                invalidate()
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val base = baseBitmap ?: return
        if (width <= 0 || height <= 0) return
        if (renderMode == DeepSceneTransform.Mode.FIT_WIDTH) {
            backdropBitmap?.let { drawCoverBitmap(canvas, it, 185) }
            overlay.color = 0x664A382B
            overlay.shader = null
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlay)
        }
        drawSceneBitmap(canvas, base, 255)

        val accent = accentBitmap
        val now = System.currentTimeMillis()
        if (accent != null && now >= nextAccentAt) {
            val elapsed = now - nextAccentAt
            if (elapsed <= accentDurationMs) {
                val phase = elapsed.toFloat() / accentDurationMs.toFloat()
                val blend = sin(phase * Math.PI).toFloat().coerceIn(0f, 1f)
                drawAccentBitmap(canvas, accent, (blend * 255).toInt())
            } else {
                nextAccentAt = now + accentIntervalMs
            }
        }

        overlay.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(0x1E120E0A, 0x00000000, 0x0A140E08, 0x35110B07),
            floatArrayOf(0f, .22f, .76f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlay)
        overlay.shader = null

        if (visibility == VISIBLE && !released) postInvalidateOnAnimation()
    }

    private fun drawSceneBitmap(canvas: Canvas, source: Bitmap, alpha: Int) {
        val transform = DeepSceneTransform.calculate(
            width.toFloat(), height.toFloat(), source.width.toFloat(), source.height.toFloat(), renderMode
        )
        matrix.reset()
        matrix.postScale(transform.scale, transform.scale)
        matrix.postTranslate(transform.offsetX + parallaxX, transform.offsetY + parallaxY)
        paint.alpha = alpha
        canvas.drawBitmap(source, matrix, paint)
        paint.alpha = 255
    }

    private fun drawAccentBitmap(canvas: Canvas, source: Bitmap, alpha: Int) {
        if (!baseAsset.contains("desk_fullscreen")) {
            drawSceneBitmap(canvas, source, alpha)
            return
        }
        // 生图闭眼帧连脸型、头发和桌面纹理都会发生细小变化
        // 这里只取两只眼睛各自约 60px 的闭眼贴片，其余像素始终使用同一张底图
        drawEyePatch(
            canvas, source, alpha,
            sourceRect = RectF(.455f, .116f, .535f, .154f),
            baseRect = RectF(.457f, .116f, .537f, .154f)
        )
        drawEyePatch(
            canvas, source, alpha,
            sourceRect = RectF(.530f, .101f, .612f, .140f),
            baseRect = RectF(.533f, .101f, .615f, .140f)
        )
    }

    private fun drawEyePatch(
        canvas: Canvas,
        source: Bitmap,
        alpha: Int,
        sourceRect: RectF,
        baseRect: RectF,
    ) {
        val base = baseBitmap ?: return
        val transform = DeepSceneTransform.calculate(
            width.toFloat(), height.toFloat(), base.width.toFloat(), base.height.toFloat(), renderMode
        )
        val destination = RectF(
            baseRect.left * base.width * transform.scale + transform.offsetX + parallaxX,
            baseRect.top * base.height * transform.scale + transform.offsetY + parallaxY,
            baseRect.right * base.width * transform.scale + transform.offsetX + parallaxX,
            baseRect.bottom * base.height * transform.scale + transform.offsetY + parallaxY,
        )
        val crop = android.graphics.Rect(
            (sourceRect.left * source.width).toInt(),
            (sourceRect.top * source.height).toInt(),
            (sourceRect.right * source.width).toInt(),
            (sourceRect.bottom * source.height).toInt(),
        )
        val layer = canvas.saveLayer(destination, null)
        paint.alpha = alpha
        canvas.drawBitmap(source, crop, destination, paint)
        paint.alpha = 255

        overlay.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        overlay.shader = LinearGradient(
            destination.left, 0f, destination.right, 0f,
            intArrayOf(0x00000000, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0x00000000),
            floatArrayOf(0f, .20f, .80f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(destination, overlay)
        overlay.shader = LinearGradient(
            0f, destination.top, 0f, destination.bottom,
            intArrayOf(0x00000000, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0x00000000),
            floatArrayOf(0f, .20f, .80f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(destination, overlay)
        overlay.shader = null
        overlay.xfermode = null
        canvas.restoreToCount(layer)
    }

    private fun drawCoverBitmap(canvas: Canvas, source: Bitmap, alpha: Int) {
        val transform = DeepSceneTransform.calculate(
            width.toFloat(), height.toFloat(), source.width.toFloat(), source.height.toFloat(), DeepSceneTransform.Mode.COVER
        )
        matrix.reset()
        matrix.postScale(transform.scale, transform.scale)
        matrix.postTranslate(transform.offsetX, transform.offsetY)
        paint.alpha = alpha
        canvas.drawBitmap(source, matrix, paint)
        paint.alpha = 255
    }

    fun release() {
        released = true
        loader.shutdownNow()
        cache.values.distinct().forEach { if (!it.isRecycled) it.recycle() }
        cache.clear()
        baseBitmap = null
        accentBitmap = null
        backdropBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        backdropBitmap = null
    }
}
