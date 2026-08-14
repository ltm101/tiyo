package com.koyo.screenwarden

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * A non-interactive light layer shared by the deep-companion scenes
 *
 * The source artwork stays untouched. Time changes the direction and warmth of the room light,
 * while weather only changes the light coming through the window, so rain never looks as if it is
 * falling inside the room
 */
internal class DeepCompanionAmbienceView(context: Context) : View(context) {
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val weatherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = resources.displayMetrics.density * .75f
        strokeCap = Paint.Cap.ROUND
    }
    private val beamPath = Path()
    private var weather = ""
    private var scene = DeepCompanionHostView.Scene.ROOM

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setWeather(value: String) {
        if (weather == value) return
        weather = value
        invalidate()
    }

    fun showScene(value: DeepCompanionHostView.Scene) {
        scene = value
        invalidate()
    }

    fun refreshClock() = invalidate()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY) + calendar.get(Calendar.MINUTE) / 60f
        val time = DeepCompanionAmbiencePolicy.forHour(hour)
        val weatherProfile = DeepCompanionAmbiencePolicy.forWeather(weather)
        val sceneStrength = when (scene) {
            DeepCompanionHostView.Scene.ROOM -> 1f
            DeepCompanionHostView.Scene.DESK -> .72f
            DeepCompanionHostView.Scene.SHELF -> .82f
            DeepCompanionHostView.Scene.DIARY -> .45f
        }

        drawTint(canvas, time.tintColor, (time.tintAlpha * sceneStrength).roundToInt())
        if (time.beamAlpha > 0) drawWindowBeam(canvas, hour, (time.beamAlpha * sceneStrength).roundToInt())
        if (weatherProfile.tintAlpha > 0) {
            drawTint(canvas, weatherProfile.tintColor, (weatherProfile.tintAlpha * sceneStrength).roundToInt())
        }
        if (scene == DeepCompanionHostView.Scene.ROOM) drawWindowWeather(canvas, weatherProfile)

        if (visibility == VISIBLE && (weatherProfile.rain || weatherProfile.snow)) {
            postInvalidateDelayed(48L)
        }
    }

    private fun drawTint(canvas: Canvas, color: Int, alpha: Int) {
        tintPaint.shader = null
        tintPaint.color = colorWithAlpha(color, alpha)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)
    }

    private fun drawWindowBeam(canvas: Canvas, hour: Float, alpha: Int) {
        val progress = ((hour - 6f) / 13f).coerceIn(0f, 1f)
        val topCenter = width * (.83f - progress * .11f)
        val bottomCenter = width * (.58f - progress * .18f)
        val topHalf = width * .13f
        val bottomHalf = width * .28f
        beamPath.reset()
        beamPath.moveTo(topCenter - topHalf, 0f)
        beamPath.lineTo(topCenter + topHalf, 0f)
        beamPath.lineTo(bottomCenter + bottomHalf, height.toFloat())
        beamPath.lineTo(bottomCenter - bottomHalf, height.toFloat())
        beamPath.close()
        beamPaint.shader = LinearGradient(
            topCenter,
            0f,
            bottomCenter,
            height.toFloat(),
            intArrayOf(
                colorWithAlpha(0xFFFFE0A6.toInt(), alpha),
                colorWithAlpha(0xFFFFE8BA.toInt(), (alpha * .48f).roundToInt()),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .46f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(beamPath, beamPaint)
        beamPaint.shader = null
    }

    private fun drawWindowWeather(canvas: Canvas, profile: DeepCompanionAmbiencePolicy.WeatherProfile) {
        if (!profile.rain && !profile.snow) return
        val now = System.currentTimeMillis()
        canvas.save()
        canvas.clipRect(width * .72f, 0f, width.toFloat(), height * .45f)
        if (profile.rain) {
            val phase = ((now % 2_200L) / 2_200f) * height * .14f
            weatherPaint.color = 0x667DB6D4
            repeat(17) { index ->
                val x = width * (.70f + ((index * 37) % 31) / 100f)
                val y = ((index * height * .073f + phase) % (height * .52f)) - height * .07f
                canvas.drawLine(x, y, x - width * .018f, y + height * .045f, weatherPaint)
            }
        }
        if (profile.snow) {
            val phase = ((now % 5_600L) / 5_600f) * height * .16f
            weatherPaint.color = 0x99F2FAFF.toInt()
            repeat(14) { index ->
                val x = width * (.72f + ((index * 19) % 28) / 100f)
                val y = ((index * height * .091f + phase) % (height * .54f)) - height * .04f
                canvas.drawCircle(x, y, resources.displayMetrics.density * (1f + index % 3 * .35f), weatherPaint)
            }
        }
        canvas.restore()
    }

    private fun colorWithAlpha(color: Int, alpha: Int): Int =
        color and 0x00FFFFFF or (alpha.coerceIn(0, 255) shl 24)
}

internal object DeepCompanionAmbiencePolicy {
    data class TimeProfile(val tintColor: Int, val tintAlpha: Int, val beamAlpha: Int)
    data class WeatherProfile(
        val tintColor: Int,
        val tintAlpha: Int,
        val rain: Boolean = false,
        val snow: Boolean = false
    )

    fun forHour(hour: Float): TimeProfile = when {
        hour < 5f -> TimeProfile(0xFF0C1735.toInt(), 88, 0)
        hour < 7f -> TimeProfile(0xFF735B73.toInt(), 48, 18)
        hour < 10f -> TimeProfile(0xFFFFD19A.toInt(), 18, 42)
        hour < 16f -> TimeProfile(0xFFFFF1D2.toInt(), 7, 24)
        hour < 18.5f -> TimeProfile(0xFFFFB56C.toInt(), 31, 50)
        hour < 21f -> TimeProfile(0xFF344461.toInt(), 54, 8)
        else -> TimeProfile(0xFF101C3B.toInt(), 82, 0)
    }

    fun forWeather(raw: String): WeatherProfile {
        val value = raw.lowercase()
        return when {
            value.contains("雷") || value.contains("thunder") ->
                WeatherProfile(0xFF17243E.toInt(), 54, rain = true)
            value.contains("雨") || value.contains("rain") || value.contains("drizzle") ->
                WeatherProfile(0xFF294E68.toInt(), 38, rain = true)
            value.contains("雪") || value.contains("snow") || value.contains("sleet") ->
                WeatherProfile(0xFFC5DBE7.toInt(), 24, snow = true)
            value.contains("阴") || value.contains("云") || value.contains("雾") ||
                value.contains("cloud") || value.contains("overcast") || value.contains("fog") ->
                WeatherProfile(0xFF3D4D5C.toInt(), 27)
            else -> WeatherProfile(Color.TRANSPARENT, 0)
        }
    }
}
