package com.koyo.screenwarden

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Best-effort five-minute phone-state heartbeat
 *
 * The heartbeat only collects local state and pushes it to the LAN gateway
 * It never invokes an LLM and keeps the hourly Gmail draft as an independent
 * fallback
 */
class RealtimeStateWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val state = collectState(applicationContext)
            val pushed = pushState(applicationContext, state)
            Log.i(TAG, "State heartbeat pushed=$pushed")
            Result.success()
        } catch (error: Exception) {
            Log.w(TAG, "State heartbeat failed", error)
            Result.success()
        } finally {
            if (inputData.getBoolean(KEY_CONTINUOUS, false)) {
                enqueueNext(applicationContext)
            }
        }
    }

    private suspend fun collectState(context: Context): String {
        val screenReport = ScreenUsageCollector(context).collectDailyUsage()
        val topApps = screenReport.lines()
            .filter {
                it.contains(":") &&
                    !it.startsWith("=") &&
                    !it.startsWith("-") &&
                    !it.startsWith("Screen") &&
                    !it.startsWith("Total")
            }
            .take(3)
            .joinToString(", ") { it.trim() }

        val steps = StepCounterCollector.refreshAndGetSteps(context)
        val battery = getBatteryPercent(context)
        val timestamp = SimpleDateFormat(
            "yyyy-MM-dd HH:mm",
            Locale.getDefault(),
        ).format(Date())

        val loc = LocationCollector.format(LocationCollector.getCurrentLocation(context))
        return buildString {
            appendLine("tiyo-state $timestamp")
            appendLine("Top: ${topApps.ifBlank { "—" }}")
            if (steps >= 0) {
                appendLine(
                    "Steps: $steps 步 ≈${StepCounterCollector.stepsToKm(steps)}km",
                )
            }
            appendLine("Location: $loc")
            if (battery >= 0) appendLine("Battery: $battery%")
        }.trim()
    }

    private fun getBatteryPercent(context: Context): Int {
        return try {
            val status = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ) ?: return -1
            val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) level * 100 / scale else -1
        } catch (_: Exception) {
            -1
        }
    }

    private fun pushState(context: Context, state: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_GATEWAY_URL, "").orEmpty()
        if (cached.isNotBlank() && postState(cached, state)) return true

        val discovered = discoverGateway() ?: return false
        if (!postState(discovered, state)) return false
        prefs.edit().putString(KEY_GATEWAY_URL, discovered).apply()
        return true
    }

    private fun postState(baseUrl: String, state: String): Boolean {
        return try {
            val connection = (
                URL("${baseUrl.trimEnd('/')}/tiyo/state")
                    .openConnection() as HttpURLConnection
                ).apply {
                connectTimeout = 2500
                readTimeout = 2500
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val payload = JSONObject()
                .put("timestamp", System.currentTimeMillis())
                .put("text", state)
                .toString()
                .toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val success = connection.responseCode in 200..299
            connection.disconnect()
            success
        } catch (_: Exception) {
            false
        }
    }

    private fun discoverGateway(): String? {
        return try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = DISCOVERY_TIMEOUT_MS
                val query = DISCOVERY_QUERY.toByteArray(Charsets.US_ASCII)
                val destinations = linkedSetOf(InetAddress.getByName("255.255.255.255"))
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    if (!networkInterface.isUp || networkInterface.isLoopback) continue
                    networkInterface.interfaceAddresses.forEach { address ->
                        address.broadcast?.let(destinations::add)
                    }
                }
                destinations.forEach { destination ->
                    socket.send(
                        DatagramPacket(
                            query,
                            query.size,
                            destination,
                            DISCOVERY_PORT,
                        ),
                    )
                }

                val response = DatagramPacket(ByteArray(128), 128)
                socket.receive(response)
                val text = String(
                    response.data,
                    0,
                    response.length,
                    Charsets.US_ASCII,
                )
                if (text.trim() == DISCOVERY_REPLY) {
                    "http://${response.address.hostAddress}:8888"
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "RealtimeStateWorker"
        private const val UNIQUE_CHAIN = "tiyo_realtime_state"
        private const val PERIODIC_FALLBACK = "tiyo_state_fallback"
        private const val KEY_CONTINUOUS = "continuous"
        private const val PREFS_NAME = "tiyo_realtime_state"
        private const val KEY_GATEWAY_URL = "gateway_url"
        private const val DISCOVERY_QUERY = "KOYO_GATEWAY_DISCOVER"
        private const val DISCOVERY_REPLY = "KOYO_GATEWAY"
        private const val DISCOVERY_PORT = 4211
        private const val DISCOVERY_TIMEOUT_MS = 1800

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(context: Context) {
            val first = OneTimeWorkRequestBuilder<RealtimeStateWorker>()
                .setInputData(workDataOf(KEY_CONTINUOUS to true))
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_CHAIN,
                ExistingWorkPolicy.KEEP,
                first,
            )

            val fallback = PeriodicWorkRequestBuilder<RealtimeStateWorker>(
                15,
                TimeUnit.MINUTES,
            )
                .setConstraints(constraints())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.MINUTES,
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_FALLBACK,
                ExistingPeriodicWorkPolicy.UPDATE,
                fallback,
            )
        }

        private fun enqueueNext(context: Context) {
            val next = OneTimeWorkRequestBuilder<RealtimeStateWorker>()
                .setInitialDelay(5, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_CONTINUOUS to true))
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_CHAIN,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                next,
            )
        }
    }
}
