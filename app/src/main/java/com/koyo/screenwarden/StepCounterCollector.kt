package com.koyo.screenwarden

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import java.text.SimpleDateFormat
import java.util.*

object StepCounterCollector {

    private const val PREFS_NAME = "tiyo_steps"
    private const val KEY_DATE = "step_date"
    private const val KEY_BASELINE = "step_baseline"
    private const val KEY_ACCUMULATED = "step_accumulated"
    private const val KEY_REBOOT_OFFSET = "step_reboot_offset"
    private const val KEY_LAST_SENSOR_TOTAL = "step_last_sensor_total"
    private const val KEY_ALGORITHM_VERSION = "step_algorithm_version"
    private const val ALGORITHM_VERSION = 2

    /**
     * 收到传感器事件时调用，计算并持久化当日步数。
     * sensorTotal = 传感器自开机以来的累计值。
     * 返回今日步数。
     */
    fun onStepEvent(context: Context, sensorTotal: Int): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = today()
        val savedDate = prefs.getString(KEY_DATE, "")
        val validState = savedDate == today &&
            prefs.getInt(KEY_ALGORITHM_VERSION, 0) == ALGORITHM_VERSION
        val previous = if (validState) {
            StepProgress(
                baseline = prefs.getInt(KEY_BASELINE, sensorTotal),
                rebootOffset = prefs.getInt(KEY_REBOOT_OFFSET, 0),
                lastSensorTotal = prefs.getInt(KEY_LAST_SENSOR_TOTAL, sensorTotal),
                todaySteps = prefs.getInt(KEY_ACCUMULATED, 0)
            )
        } else null
        val next = advanceStepProgress(previous, sensorTotal)
        prefs.edit()
            .putString(KEY_DATE, today)
            .putInt(KEY_ALGORITHM_VERSION, ALGORITHM_VERSION)
            .putInt(KEY_BASELINE, next.baseline)
            .putInt(KEY_REBOOT_OFFSET, next.rebootOffset)
            .putInt(KEY_LAST_SENSOR_TOTAL, next.lastSensorTotal)
            .putInt(KEY_ACCUMULATED, next.todaySteps)
            .apply()
        return next.todaySteps
    }

    /** 读取已保存的今日步数（不触发传感器） */
    fun getTodaySteps(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedDate = prefs.getString(KEY_DATE, "")
        if (savedDate != today() ||
            prefs.getInt(KEY_ALGORITHM_VERSION, 0) != ALGORITHM_VERSION
        ) return 0
        return prefs.getInt(KEY_ACCUMULATED, 0)
    }

    /**
     * 主动查询传感器获取最新步数并更新当日累计。
     * 用于后台 Worker 发报告时或首页加载时拿到最新值。
     * 5 秒超时，无权限或传感器不可用时返回 -1 或已保存值。
     */
    suspend fun refreshAndGetSteps(context: Context): Int {
        return withContext(Dispatchers.Main) {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val sensor = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (sensor == null) return@withContext -1

            try {
                withTimeout(5000L) {
                    suspendCancellableCoroutine { cont ->
                        val listener = object : SensorEventListener {
                            override fun onSensorChanged(event: SensorEvent) {
                                sm.unregisterListener(this)
                                val steps = onStepEvent(context, event.values[0].toInt())
                                cont.resume(steps)
                            }
                            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                        }
                        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                        cont.invokeOnCancellation {
                            try { sm.unregisterListener(listener) } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                // 超时（可能无权限）：返回已保存的值
                getTodaySteps(context)
            }
        }
    }

    /** 步数 → 距离（km），按平均步长 0.7m 估算 */
    fun stepsToKm(steps: Int): String {
        val km = steps * 0.7 / 1000.0
        return String.format(Locale.getDefault(), "%.1f", km)
    }

    /** 步数 → 卡路里（kcal），粗略估算 */
    fun stepsToKcal(steps: Int): Int {
        return (steps * 0.04).toInt()
    }

    private fun today(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }
}

internal data class StepProgress(
    val baseline: Int,
    val rebootOffset: Int,
    val lastSensorTotal: Int,
    val todaySteps: Int
)

/**
 * TYPE_STEP_COUNTER 给的是开机以来累计值，不能把完整差值在每次回调时反复相加。
 * 重启归零时把已确认的今日步数转为 offset，再从新基准继续累计。
 */
internal fun advanceStepProgress(previous: StepProgress?, rawSensorTotal: Int): StepProgress {
    val sensorTotal = rawSensorTotal.coerceAtLeast(0)
    if (previous == null) {
        return StepProgress(sensorTotal, 0, sensorTotal, 0)
    }
    if (sensorTotal < previous.lastSensorTotal) {
        return StepProgress(
            baseline = sensorTotal,
            rebootOffset = previous.todaySteps,
            lastSensorTotal = sensorTotal,
            todaySteps = previous.todaySteps
        )
    }
    val delta = (sensorTotal - previous.baseline).coerceAtLeast(0)
    val todaySteps = (previous.rebootOffset.toLong() + delta)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    return previous.copy(lastSensorTotal = sensorTotal, todaySteps = todaySteps)
}
