package com.koyo.screenwarden

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 轻量定位采集，使用系统 LocationManager（无需 Google Play Services）
 *
 * 策略：GPS 优先（精度高）、网络回退（省电）
 * Android 11+ 后台定位需要 ACCESS_BACKGROUND_LOCATION
 */
object LocationCollector {

    private const val TAG = "LocationCollector"
    private const val TIMEOUT_MS = 15_000L
    private const val MIN_ACCURACY_M = 100f   // 精度低于 100m 弃用
    private const val PREFS_NAME = "tiyo_location"
    private const val KEY_ENABLED = "loc_enabled"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * 获取当前位置，超时 15 秒则返回 null
     */
    suspend fun getCurrentLocation(context: Context): Location? {
        if (!isEnabled(context)) return null

        val hasFineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFineLocation && !hasCoarseLocation) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        // 先查缓存（GPS → 网络）
        val cached = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        // 缓存不超过 5 分钟就直接用
        if (cached != null && System.currentTimeMillis() - cached.time < 300_000) {
            return cached
        }

        // 请求单次定位
        return suspendCancellableCoroutine { cont ->
            var finished = false
            var gpsListener: LocationListener? = null
            var netListener: LocationListener? = null

            val done: (Location?) -> Unit = { loc ->
                if (!finished) {
                    finished = true
                    gpsListener?.let { lm.removeUpdates(it) }
                    netListener?.let { lm.removeUpdates(it) }
                    cont.resume(loc)
                }
            }

            // 超时
            val timeoutTask = Runnable {
                done(cached)
            }
            val handler = android.os.Handler(Looper.getMainLooper())
            handler.postDelayed(timeoutTask, TIMEOUT_MS)

            cont.invokeOnCancellation {
                handler.removeCallbacks(timeoutTask)
                gpsListener?.let { lm.removeUpdates(it) }
                netListener?.let { lm.removeUpdates(it) }
            }

            // GPS 监听
            gpsListener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    if (loc.accuracy <= MIN_ACCURACY_M) done(loc)
                }
                override fun onProviderDisabled(provider: String) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) {}
            }

            // 网络监听（GPS 不行时兜底）
            netListener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    if (loc.accuracy <= MIN_ACCURACY_M) done(loc)
                }
                override fun onProviderDisabled(provider: String) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) {}
            }

            try {
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, gpsListener!!, Looper.getMainLooper())
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, netListener!!, Looper.getMainLooper())
            } catch (e: SecurityException) {
                done(cached)
            } catch (e: Exception) {
                done(cached)
            }
        }
    }

    /**
     * 格式化为可读字符串
     */
    fun format(location: Location?): String {
        if (location == null) return "—"
        val lat = "%.5f".format(location.latitude)
        val lng = "%.5f".format(location.longitude)
        return "$lat,$lng"
    }
}
